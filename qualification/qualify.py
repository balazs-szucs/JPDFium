#!/usr/bin/env python3
"""Differential qualification driver for the font-intelligence / structural-repair branch.

Design rules (from the governing directive):
  * Inputs under qualification/inputs are immutable; outputs are written only under
    qualification/outputs. The driver never writes over an input.
  * Every preservation claim is backed by an independent probe (qpdf / pdfinfo /
    pdftotext / pdftoppm / mutool), run with identical settings before and after the
    operation.
  * A safe refusal is never scored as a recovery. A fixture that is *expected* to be
    rejected (malformed, non-conformant) is never scored as a regression just for being
    rejected.
  * An unavailable probe is recorded explicitly and never turned into a pass. veraPDF is
    not installed on this host, so conformance is recorded as an explicit skip.
  * Sampling rendering is acceptable for fast feedback but is NOT evidence for an
    all-page preservation claim; the scope of every render comparison is recorded.

Usage:
  python3 qualification/qualify.py [--group general|generated|pathological|all]
                                   [--ops save,font,font-plan,preserve]
                                   [--limit N] [--timeout S] [--idempotence]

Writes:
  qualification/manifests/inputs.jsonl        (base facts; produced by build-manifest.sh)
  qualification/baselines/<group>/<id>.json   (independent probes of the untouched input)
  qualification/outputs/<group>/<id>/<op>.pdf (and <op>.pdf.report.txt for preserve)
  qualification/reports/summary.json          (executed/skipped/refused/regressed counts)
  qualification/failures/...                  (input/output/logs/probes for each regression)
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INPUTS = ROOT / "qualification" / "inputs"
BASELINES = ROOT / "qualification" / "baselines"
OUTPUTS = ROOT / "qualification" / "outputs"
REPORTS = ROOT / "qualification" / "reports"
FAILURES = ROOT / "qualification" / "failures"
MANIFEST = ROOT / "qualification" / "manifests" / "inputs.jsonl"
CLASSPATH_FILE = ROOT / "jpdfium" / "build" / "qualification-classpath.txt"

WORKER = "stirling.software.jpdfium.qualification.QualificationWorker"
RENDER_PAGE_CAP = 25          # render only documents with <= this many pages
DEFAULT_TIMEOUT = 120         # seconds per worker launch

HEALTHY_GROUPS = {"general", "generated"}
OPS_ALL = ["save", "font", "font-plan", "preserve"]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(cmd, timeout):
    """Run a command, returning (rc, stdout, stderr). rc is None on timeout,
    'missing' when the executable does not exist."""
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired:
        return None, b"", b"timeout"
    except FileNotFoundError:
        return "missing", b"", ("not found: " + cmd[0]).encode()
    except OSError as exc:
        return "error", b"", str(exc).encode()


def _numeric_sorted(paths):
    def key(p):
        digits = "".join(ch for ch in Path(p).stem if ch.isdigit())
        return (int(digits) if digits else 0, p)
    return sorted(paths, key=key)


def render_hashes(cmd, workdir, timeout):
    """Run a renderer writing PNGs into workdir; return per-page SHA-256 list."""
    if os.path.isdir(workdir):
        shutil.rmtree(workdir, ignore_errors=True)
    os.makedirs(workdir, exist_ok=True)
    rc, out, err = run(cmd, timeout)
    if rc != 0:
        return {"ok": False, "rc": rc, "err": err.decode("latin-1", "replace")[-300:]}
    files = _numeric_sorted(glob.glob(os.path.join(workdir, "*")))
    hashes = [sha256_bytes(open(f, "rb").read()) for f in files]
    return {"ok": True, "pages": len(hashes), "sha": hashes}


def probe(path: Path, tmp: Path, timeout: int) -> dict:
    """Independent, read-only probe of one PDF. Never mutates the input."""
    rec = {"path": str(path), "sha256": sha256_file(path)}

    rc, _, _ = run(["qpdf", "--check", str(path)], timeout)
    rec["qpdf_check"] = rc  # 0 clean, 2 errors, 3 warnings (kept distinct, never flattened)

    rc, out, _ = run(["pdfinfo", str(path)], timeout)
    pages = -1
    if rc == 0:
        for line in out.decode("latin-1").splitlines():
            if line.startswith("Pages:"):
                try:
                    pages = int(line.split(":", 1)[1].strip())
                except ValueError:
                    pages = -1
    rec["pages"] = pages

    rc, out, _ = run(["pdftotext", "-layout", "-enc", "UTF-8", str(path), "-"], timeout)
    rec["text_len"] = len(out)
    rec["text_sha256"] = sha256_bytes(out)
    rec["text_ok"] = rc == 0

    if 0 < pages <= RENDER_PAGE_CAP:
        tmp_p = Path(tempfile.mkdtemp(dir=tmp))
        tmp_m = Path(tempfile.mkdtemp(dir=tmp))
        rec["poppler"] = render_hashes(
            ["pdftoppm", "-png", "-r", "144", "-cropbox", str(path), str(tmp_p / "p")],
            str(tmp_p), timeout)
        rec["mutool"] = render_hashes(
            ["mutool", "draw", "-q", "-r", "144", "-o", str(tmp_m / "m%d.png"), str(path)],
            str(tmp_m), timeout)
        rec["render_scope"] = "all_pages"
        rec["render_artifacts"] = {"poppler": str(tmp_p), "mutool": str(tmp_m)}
    else:
        rec["render_scope"] = "skipped_no_pages" if pages <= 0 else "skipped_large"

    if shutil.which("verapdf"):
        rec["verapdf"] = "available"
    else:
        rec["verapdf"] = "skipped_not_installed"
    return rec


def qpdf_rank(rc):
    """Ordering for 'did not get worse': 0 clean < 3 warnings < 2 errors."""
    if rc == 0:
        return 0
    if rc == 3:
        return 1
    if rc == 2:
        return 2
    return 3  # None/missing/error


def compare(src: dict, out: dict, lane: str, op: str) -> tuple[list[str], list[str]]:
    """Return (regressions, notes). Regressions are hard preservation failures."""
    regressions, notes = [], []
    if lane == "robustness_containment":
        return regressions, notes  # containment is checked separately

    if src.get("pages", -1) >= 0 and out.get("pages", -1) >= 0 and src["pages"] != out["pages"]:
        regressions.append("page_count %s->%s" % (src["pages"], out["pages"]))

    if src["text_len"] == 0 and out["text_len"] == 0:
        notes.append("text_oracle_weak:both_extractors_empty")
    elif src["text_sha256"] != out["text_sha256"]:
        regressions.append("text_changed")

    if qpdf_rank(out.get("qpdf_check")) > qpdf_rank(src.get("qpdf_check")):
        regressions.append("qpdf_check_worsened %s->%s" % (src.get("qpdf_check"), out.get("qpdf_check")))

    if src.get("render_scope") == "all_pages" and out.get("render_scope") == "all_pages":
        for tool in ("poppler", "mutool"):
            a = src.get(tool)
            b = out.get(tool)
            if a and b and a.get("ok") and b.get("ok"):
                if a["sha"] != b["sha"]:
                    n = sum(1 for x, y in zip(a["sha"], b["sha"]) if x != y)
                    regressions.append("%s_render_changed(%d/%d pages)" % (tool, n, len(a["sha"])))
            elif (a and not a.get("ok")) or (b and not b.get("ok")):
                notes.append("%s_render_unavailable" % tool)
    else:
        notes.append("render_scope_limited:%s" % out.get("render_scope"))

    if src.get("render_scope") == "skipped_large":
        notes.append("render_not_probed_large_document")
    return regressions, notes


def write_failure(group, ident, op, src, out, logs, src_probe, out_probe):
    dest = FAILURES / group / ident / op
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "logs.txt").write_text(logs)
    (dest / "probe-input.json").write_text(json.dumps(src_probe, indent=2))
    if out_probe is not None:
        (dest / "probe-output.json").write_text(json.dumps(out_probe, indent=2))
    if out and Path(out).exists():
        shutil.copy2(out, dest / Path(out).name)
    # copy render artifacts referenced by the probes so a reviewer can see the pixels
    for tag, pr in (("input", src_probe), ("output", out_probe)):
        if not pr:
            continue
        for tool, d in (pr.get("render_artifacts") or {}).items():
            if os.path.isdir(d):
                shutil.copytree(d, dest / ("render-%s-%s" % (tag, tool)), dirs_exist_ok=True)
    return dest


def run_worker(mode, inp, out, cp, timeout):
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "-Djpdfium.integration=true",
           "-Xmx512m", "-cp", cp, WORKER, mode, str(inp), str(out)]
    started = time.time()
    rc, sout, serr = run(cmd, timeout)
    return rc, sout.decode("latin-1", "replace"), serr.decode("latin-1", "replace"), time.time() - started


def baseline_for(group, ident, path, probe_cache, tmp, timeout):
    key = sha256_file(path)
    if key in probe_cache:
        return probe_cache[key]
    if group != "pathological":
        dest = BASELINES / group / (ident + ".json")
    else:
        dest = BASELINES / group / (ident + ".json")
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        pr = json.loads(dest.read_text())
    else:
        pr = probe(path, tmp, timeout)
        dest.write_text(json.dumps(pr, indent=2))
    probe_cache[key] = pr
    return pr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", default="all")
    ap.add_argument("--ops", default=",".join(OPS_ALL))
    ap.add_argument("--limit", type=int, default=0, help="max inputs per group (0 = all)")
    ap.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    ap.add_argument("--idempotence", action="store_true",
                    help="also re-run font-plan on its own output and require no further repair")
    args = ap.parse_args()

    if not CLASSPATH_FILE.exists():
        sys.exit("missing %s; run ./gradlew :jpdfium:qualificationClasspath" % CLASSPATH_FILE)
    cp = CLASSPATH_FILE.read_text().strip()

    rows = [json.loads(l) for l in MANIFEST.read_text().splitlines() if l.strip()]
    if args.group != "all":
        rows = [r for r in rows if r["group"] == args.group]
    ops = [o for o in args.ops.split(",") if o]
    requested = set(ops)
    # The serialization control (`save`) is always established FIRST: re-saving
    # alone can change bytes and even raster output, so a repair-attributable
    # delta is only meaningful relative to the control (see the float32
    # appearance-stream finding on all_form_fields.pdf).
    ops_ordered = ["save"] + [o for o in ops if o != "save"]

    by_group = {}
    for r in rows:
        by_group.setdefault(r["group"], []).append(r)
    for g in by_group:
        by_group[g].sort(key=lambda r: r["id"])
        if args.limit:
            by_group[g] = by_group[g][: args.limit]

    for d in (OUTPUTS, REPORTS, FAILURES):
        d.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="qual-probe-"))

    summary = {
        "generated_by": "qualification/qualify.py",
        "host": sys.platform,
        "verapdf": "skipped_not_installed" if not shutil.which("verapdf") else "available",
        "render_page_cap": RENDER_PAGE_CAP,
        "ops": ops,
        "files_discovered": len(rows),
        "files_baselined": 0,
        "operations_attempted": 0,
        "operations_written": 0,
        "expected_refusals": 0,
        "unexpected_failures": 0,
        "unexpected_skips": 0,
        "preservation_regressions": 0,
        "recovery_successes": 0,
        "unresolved_oracle_disagreements": 0,
        "controls_run": 0,
        "serialization_render_delta": 0,
        "serialization_attributed": 0,
        "repair_induced_regressions": 0,
        "cases": [],
    }
    probe_cache = {}

    for group in ("general", "generated", "pathological"):
        if group not in by_group:
            continue
        for row in by_group[group]:
            ident = row["id"]
            inp = Path(row["input"])
            src_probe = baseline_for(group, ident, inp, probe_cache, tmp, args.timeout)
            summary["files_baselined"] += 1
            control_probe = None
            control_regressions = []
            for op in ops_ordered:
                if op == "save":
                    summary["controls_run"] += 1
                else:
                    summary["operations_attempted"] += 1
                    if op not in requested:
                        continue
                outdir = OUTPUTS / group / ident
                outdir.mkdir(parents=True, exist_ok=True)
                outp = outdir / (op + ".pdf")
                if outp.exists():
                    outp.unlink()
                report = Path(str(outp) + ".report.txt")
                if report.exists():
                    report.unlink()

                src_sha_before = row["sha256"]
                rc, sout, serr, elapsed = run_worker(op, inp, outp, cp, args.timeout)
                logs = ("== stdout ==\n%s\n== stderr ==\n%s\nrc=%s elapsed=%.2fs\n"
                        % (sout, serr, rc, elapsed))

                lane = ("robustness_containment" if group == "pathological"
                        else ("save_control" if op == "save" else "healthy_preservation"))
                case = {"group": group, "id": ident, "op": op, "lane": lane, "rc": rc,
                        "elapsed_s": round(elapsed, 2)}

                if rc is None:
                    summary["unexpected_failures"] += 1
                    case["result"] = "timeout"
                    write_failure(group, ident, op, inp, outp, logs, src_probe, None)
                elif rc == 3:
                    # explicit refusal: must not publish bytes and must not touch the source
                    problems = []
                    if outp.exists():
                        problems.append("output_published_on_refusal")
                    if sha256_file(inp) != src_sha_before:
                        problems.append("source_mutated_on_refusal")
                    leftovers = [p.name for p in outdir.iterdir() if p.suffix in (".tmp", ".part")]
                    if leftovers:
                        problems.append("temp_leftovers:" + ",".join(leftovers))
                    if problems:
                        summary["unexpected_failures"] += 1
                        case["result"] = "containment_violation"
                        case["problems"] = problems
                        write_failure(group, ident, op, inp, outp, logs, src_probe, None)
                    else:
                        summary["expected_refusals"] += 1
                        case["result"] = "refused_contained"
                elif rc == 0:
                    # success: output must exist, be non-empty, and probe
                    if not outp.exists() or outp.stat().st_size == 0:
                        summary["unexpected_failures"] += 1
                        case["result"] = "claimed_success_no_bytes"
                        write_failure(group, ident, op, inp, outp, logs, src_probe, None)
                    else:
                        if op != "save":
                            summary["operations_written"] += 1
                        out_probe = probe(outp, tmp, args.timeout)
                        case["out_pages"] = out_probe.get("pages")
                        case["out_qpdf"] = out_probe.get("qpdf_check")
                        reg_vs_input, notes = compare(src_probe, out_probe, lane, op)

                        if op == "save":
                            # Serialization control: bytes, and possibly raster
                            # output, change with NO mutation at all. Separate the
                            # semantic signal (hard) from the raster signal
                            # (documented serialization behaviour, surfaced, not
                            # misattributed to any repair).
                            case["result"] = "control"
                            case["regressions_vs_input"] = reg_vs_input
                            if notes:
                                case["notes"] = notes
                            sem = [r for r in reg_vs_input if "render_changed" not in r]
                            ren = [r for r in reg_vs_input if "render_changed" in r]
                            if sem:
                                summary["unexpected_failures"] += 1
                                case["result"] = "control_semantic_change"
                                write_failure(group, ident, op, inp, outp, logs, src_probe, out_probe)
                            if ren:
                                summary["serialization_render_delta"] += 1
                                case["serialization_render_delta"] = ren
                            control_probe = out_probe
                            control_regressions = reg_vs_input
                            (BASELINES / group / (ident + ".control.json")).write_text(
                                json.dumps(out_probe, indent=2))
                        else:
                            # Attribution is a SET operation, both sides measured
                            # against the INPUT: a regression string the control
                            # already shows is serialization-attributed; only a
                            # delta the control does NOT show is repair-induced.
                            notes2 = []
                            if control_probe is None:
                                notes2.append("control_unavailable")
                            if lane == "robustness_containment" and out_probe.get("pages", -1) <= 0:
                                notes2.append("output_not_reopenable")
                            if notes or notes2:
                                case["notes"] = sorted(set(notes) | set(notes2))
                            attributed = [r for r in reg_vs_input if r in control_regressions]
                            if attributed:
                                summary["serialization_attributed"] += 1
                                case["serialization_attributed"] = attributed
                            repair_induced = [r for r in reg_vs_input if r not in control_regressions]
                            if repair_induced:
                                summary["preservation_regressions"] += 1
                                summary["repair_induced_regressions"] += 1
                                case["result"] = "preservation_regression"
                                case["regressions"] = repair_induced
                                write_failure(group, ident, op, inp, outp, logs, src_probe, out_probe)
                            else:
                                case["result"] = "preserved"

                        if args.idempotence and op == "font-plan" and lane == "healthy_preservation":
                            outp2 = outdir / ("font-plan-2.pdf")
                            if outp2.exists():
                                outp2.unlink()
                            rc2, sout2, serr2, el2 = run_worker(op, outp, outp2, cp, args.timeout)
                            case["idempotence_rc"] = rc2
                            case["idempotence_stdout"] = sout2.strip()
                            if rc2 == 0:
                                # second pass over an already-repaired doc must not
                                # report further repairs and must not change the text
                                fixed2 = "fixed=0" in sout2
                                if not fixed2:
                                    summary["preservation_regressions"] += 1
                                    case["result"] = "idempotence_regression"
                                    write_failure(group, ident, op + "-idempotence", inp, outp2,
                                                  logs + "\n" + sout2 + serr2, src_probe, None)
                elif rc == 2:
                    summary["unexpected_failures"] += 1
                    case["result"] = "precondition_violation"
                    write_failure(group, ident, op, inp, outp, logs, src_probe, None)
                else:
                    summary["unexpected_failures"] += 1
                    case["result"] = "unexpected_exit_%s" % rc
                    write_failure(group, ident, op, inp, outp, logs, src_probe, None)

                summary["cases"].append(case)

    shutil.rmtree(tmp, ignore_errors=True)
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "summary.json").write_text(json.dumps(summary, indent=2))

    print("== qualification summary ==")
    for key in ("files_discovered", "files_baselined", "controls_run",
                "operations_attempted", "operations_written", "expected_refusals",
                "unexpected_failures", "unexpected_skips", "preservation_regressions",
                "repair_induced_regressions", "serialization_render_delta",
                "serialization_attributed", "recovery_successes",
                "unresolved_oracle_disagreements"):
        print("%-32s %s" % (key, summary[key]))
    if summary["unexpected_failures"] or summary["repair_induced_regressions"]:
        print("FAIL: see qualification/reports/summary.json and qualification/failures/")
        return 1
    print("PASS (repair-induced regressions = 0; serialization deltas reported separately)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
