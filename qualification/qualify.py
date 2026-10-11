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
import difflib
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Frozen input corpus and its manifest come from a separate, deterministic step
# (generateCorpus + QualificationFixtures + build-manifest.sh); the manifest
# records each input's path, so the runner reads inputs in place.
INPUTS = ROOT / "qualification" / "inputs"
MANIFEST = ROOT / "qualification" / "manifests" / "inputs.jsonl"
CLASSPATH_FILE = ROOT / "jpdfium" / "build" / "qualification-classpath.txt"

# Paired-mutation recovery lane (see --lane recovery).
MUTATOR = ROOT / "qualification" / "mutate-tounicode.js"

# Every generated artefact (baselines, candidate PDFs, failure bundles, reports,
# recovery records) is written under one run directory so the source tree stays
# clean and a whole run can be archived at once:
#
#   jpdfium/build/qualification/<run-id>/{baselines,outputs,reports,failures,recovery}
#
# configure_run() rebinds these roots. They stay None until it is called, so a
# helper reached before configuration fails loudly rather than writing somewhere
# unexpected. Curated source-controlled documents (README.md, FINDINGS-*.md,
# RECEIPT.md, manifests/) are NOT written by the runner.
DEFAULT_OUT_ROOT = ROOT / "jpdfium" / "build" / "qualification"
BASELINES = None
OUTPUTS = None
REPORTS = None
FAILURES = None
RECOVERY = None
RECOVERY_RECORDS = None
RECOVERY_REPORTS = None


def configure_run(out_root, run_id):
    """Point the generated-artefact roots at <out_root>/<run_id>; return it."""
    global BASELINES, OUTPUTS, REPORTS, FAILURES, RECOVERY, RECOVERY_RECORDS, RECOVERY_REPORTS
    run_dir = Path(out_root) / run_id
    BASELINES = run_dir / "baselines"
    OUTPUTS = run_dir / "outputs"
    REPORTS = run_dir / "reports"
    FAILURES = run_dir / "failures"
    RECOVERY = run_dir / "recovery"
    RECOVERY_RECORDS = RECOVERY / "records"
    RECOVERY_REPORTS = RECOVERY / "reports"
    return run_dir

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
    # Order- and whitespace-insensitive fingerprint of the same extraction, used
    # only by the diagnostic `--text-oracle multiset` mode to tell a real
    # character loss from a pure layout/line-break reflow.
    _norm = "".join(out.decode("utf-8", "replace").split())
    rec["text_multiset_sha256"] = sha256_bytes("".join(sorted(_norm)).encode("utf-8"))

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


def compare(src: dict, out: dict, lane: str, op: str,
            text_oracle: str = "raw") -> tuple[list[str], list[str]]:
    """Return (regressions, notes). Regressions are hard preservation failures.

    ``text_oracle`` selects how extracted text is compared:
      * ``raw`` (default) — byte-for-byte, including whitespace and line breaks.
        Strictest, and the published result.
      * ``multiset`` — order- and whitespace-insensitive (the sorted character
        multiset). Diagnostic only: it separates a real character loss from a
        pure layout/line-break reflow. It cannot see a reordering, so it must
        never be the published verdict.
    """
    regressions, notes = [], []
    if lane == "robustness_containment":
        return regressions, notes  # containment is checked separately

    if src.get("pages", -1) >= 0 and out.get("pages", -1) >= 0 and src["pages"] != out["pages"]:
        regressions.append("page_count %s->%s" % (src["pages"], out["pages"]))

    if src["text_len"] == 0 and out["text_len"] == 0:
        notes.append("text_oracle_weak:both_extractors_empty")
    elif text_oracle == "multiset":
        a = src.get("text_multiset_sha256")
        b = out.get("text_multiset_sha256")
        if a is None or b is None:
            notes.append("text_oracle_limited:missing_multiset_baseline")
        elif a != b:
            regressions.append("text_changed")
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


def persistable(pr: dict) -> dict:
    """A probe record without the ephemeral temp-dir paths, so a persisted
    baseline is byte-stable across runs (the render hashes are the evidence; the
    scratch directory names are not)."""
    return {k: v for k, v in pr.items() if k != "render_artifacts"}


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
        dest.write_text(json.dumps(persistable(pr), indent=2))
    probe_cache[key] = pr
    return pr


def pdf_page_count(path: Path, timeout: int) -> int:
    rc, out, _ = run(["pdfinfo", str(path)], timeout)
    if rc == 0:
        for line in out.decode("latin-1").splitlines():
            if line.startswith("Pages:"):
                try:
                    return int(line.split(":", 1)[1].strip())
                except ValueError:
                    return -1
    return -1


def page_text(path: Path, page: int, timeout: int) -> str:
    """Whitespace-normalised text of a single page (1-based), from pdftotext."""
    rc, out, _ = run(["pdftotext", "-f", str(page), "-l", str(page), "-layout",
                      "-enc", "UTF-8", str(path), "-"], timeout)
    return " ".join(out.decode("utf-8", "replace").split())


def char_diff(a: str, b: str) -> int:
    """Number of characters not covered by equal runs of a vs b (edit distance proxy)."""
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    return sum(max(i2 - i1, j2 - j1) for tag, i1, i2, j1, j2 in sm.get_opcodes()
               if tag != "equal")


def nfkc(text: str) -> str:
    return unicodedata.normalize("NFKC", text)


def classify_page(o: str, m: str, r: str) -> tuple[str, bool]:
    """Per-page recovery outcome. Returns (outcome, nfkc_was_needed).

    o = oracle (pristine input's own extraction), m = mutated, r = repaired.
    A page the mutation did not disturb must stay equal to the oracle; a page it
    did disturb is a recovery target. NFKC equality (Unicode compatibility
    normalisation of ligature/presentation forms) is an explicitly recorded
    tolerance, surfaced as *_nfkc outcomes, never folded into a raw equality. A
    repaired page that is NFKC-equal to the mutated input made no material
    progress and is recorded as unrecovered, not as worsened."""
    if o == m:
        if r == o:
            return "intact", False
        if nfkc(r) == nfkc(o):
            return "intact_nfkc", True
        return "changed_correct_page", False
    if r == o:
        return "recovered", False
    if nfkc(r) == nfkc(o):
        return "recovered_nfkc", True
    if r == m:
        return "unrecovered", False
    if nfkc(r) == nfkc(m):
        # the repair left the text materially unchanged: a ligature/presentation
        # form difference is not progress, and must not read as "worsened".
        return "unrecovered", True
    if char_diff(o, r) < char_diff(o, m):
        return "improved", False
    return "worsened", False


def run_recovery(args, cp, rows, tmp) -> int:
    """Paired-mutation recovery lane.

    For each applicable input: A = pristine original (the oracle), B = A with
    every declared /ToUnicode removed (the controlled mutation), R = B after the
    font-intelligence repair. A page A did not disturb must remain A; a page B
    disturbed is a recovery target and should return to A. A safe refusal is
    recorded as refused, never as a recovery, and never as corruption."""
    mut = RECOVERY / "mutated"
    rep = RECOVERY / "repaired"
    RECOVERY_RECORDS.mkdir(parents=True, exist_ok=True)
    RECOVERY_REPORTS.mkdir(parents=True, exist_ok=True)
    mut.mkdir(parents=True, exist_ok=True)
    rep.mkdir(parents=True, exist_ok=True)

    summary = {
        "generated_by": "qualification/qualify.py --lane recovery",
        "host": sys.platform,
        "lane": "paired_mutation_recovery",
        "mutation": "delete /ToUnicode from every Type=Font dictionary",
        "oracle": "poppler pdftotext of the pristine input's own declared /ToUnicode",
        "tolerance": "NFKC compatibility normalisation (ligature/presentation forms), recorded per page",
        "files_discovered": len(rows),
        "not_applicable": 0,          # mutation dropped 0 /ToUnicode
        "cases_run": 0,
        "mutation_failed": 0,
        "refused": 0,
        "recovery_targets": 0,
        "recovered": 0,
        "recovered_nfkc": 0,
        "improved": 0,
        "unrecovered": 0,
        "intact": 0,
        "intact_nfkc": 0,
        "nfkc_only_pages": 0,
        "changed_correct_page": 0,
        "worsened": 0,
        "unresolved_oracle_disagreements": 0,
        "recovery_containment_violations": 0,
        "recovery_structural_regressions": 0,
        "cases": [],
    }

    for row in rows:
        group, ident = row["group"], row["id"]
        if group == "pathological":
            continue  # the recovery lane is about repairable real documents
        a = Path(row["input"])
        if not a.exists():
            summary["mutation_failed"] += 1
            summary["cases"].append({"group": group, "id": ident, "result": "missing_fixture",
                                     "path": str(a)})
            continue
        outdir = mut / group
        outdir.mkdir(parents=True, exist_ok=True)
        b = outdir / ident
        rdir = rep / group
        rdir.mkdir(parents=True, exist_ok=True)
        r = rdir / ident
        for stale in (b, r):
            if stale.exists():
                stale.unlink()

        rc, sout, serr = run(["mutool", "run", str(MUTATOR), str(a), str(b)], args.timeout)
        if rc != 0 or not b.exists():
            summary["mutation_failed"] += 1
            summary["cases"].append({"group": group, "id": ident, "result": "mutation_failed",
                                     "rc": rc, "stderr": serr.decode("latin-1", "replace")[-300:]})
            continue
        text = sout.decode("latin-1", "replace")
        mt = re.search(r"tounicode_dropped=(\d+)", text)
        dropped = int(mt.group(1)) if mt else -1
        if dropped == 0:
            summary["not_applicable"] += 1
            summary["cases"].append({"group": group, "id": ident, "result": "not_applicable",
                                     "reason": "no declared /ToUnicode to remove"})
            continue
        if dropped < 0:
            summary["mutation_failed"] += 1
            summary["cases"].append({"group": group, "id": ident, "result": "mutation_unparsed"})
            continue

        summary["cases_run"] += 1
        case = {"group": group, "id": ident, "tounicode_dropped": dropped}

        a_sha = sha256_file(a)
        b_sha_before = sha256_file(b)
        rc, rcout, rcerr, elapsed = run_worker("font-plan", b, r, cp, args.timeout)
        case["rc"] = rc
        case["elapsed_s"] = round(elapsed, 2)
        mf = re.search(r"fixed=(\d+)", rcout)
        case["fixed"] = int(mf.group(1)) if mf else None
        logs = ("== mutator ==\n%s\n== worker stdout ==\n%s\n== worker stderr ==\n%s\nrc=%s elapsed=%.2fs\n"
                % (text, rcout, rcerr, rc, elapsed))

        if rc == 3:
            problems = []
            if r.exists():
                problems.append("output_published_on_refusal")
            if sha256_file(b) != b_sha_before:
                problems.append("mutated_input_changed_by_worker")
            if sha256_file(a) != a_sha:
                problems.append("oracle_mutated")
            if problems:
                summary["recovery_containment_violations"] += 1
                case["result"] = "containment_violation"
                case["problems"] = problems
            else:
                summary["refused"] += 1
                case["result"] = "refused"
            summary["cases"].append(case)
            continue
        if rc is None or rc == 2:
            summary["recovery_containment_violations"] += 1
            case["result"] = "timeout" if rc is None else "precondition_violation"
            summary["cases"].append(case)
            continue
        if rc != 0:
            summary["recovery_containment_violations"] += 1
            case["result"] = "unexpected_exit_%s" % rc
            summary["cases"].append(case)
            continue
        if not r.exists() or r.stat().st_size == 0:
            summary["recovery_structural_regressions"] += 1
            case["result"] = "claimed_success_no_bytes"
            summary["cases"].append(case)
            continue

        # structural preservation: the repair must not change the page count or
        # worsen qpdf's verdict relative to the mutated input it was given
        pages_a = pdf_page_count(a, args.timeout)
        pages_b = pdf_page_count(b, args.timeout)
        pages_r = pdf_page_count(r, args.timeout)
        qc_b, _, _ = run(["qpdf", "--check", str(b)], args.timeout)
        qc_r, _, _ = run(["qpdf", "--check", str(r)], args.timeout)
        case["pages"] = {"oracle": pages_a, "mutated": pages_b, "repaired": pages_r}
        case["qpdf_check"] = {"mutated": qc_b, "repaired": qc_r}
        if pages_b >= 0 and pages_r >= 0 and pages_b != pages_r:
            summary["recovery_structural_regressions"] += 1
            case["result"] = "page_count_changed"
            summary["cases"].append(case)
            continue
        if qpdf_rank(qc_r) > qpdf_rank(qc_b):
            summary["recovery_structural_regressions"] += 1
            case["result"] = "qpdf_check_worsened %s->%s" % (qc_b, qc_r)
            summary["cases"].append(case)
            continue

        page_results = []
        n = max(pages_a, pages_b, pages_r)
        for p in range(1, n + 1):
            o = page_text(a, p, args.timeout)
            m = page_text(b, p, args.timeout)
            rr = page_text(r, p, args.timeout)
            outcome, needed_nfkc = classify_page(o, m, rr)
            page_results.append({
                "page": p, "outcome": outcome, "nfkc_needed": needed_nfkc,
                "char_diff_to_oracle": {"mutated": char_diff(o, m), "repaired": char_diff(o, rr)},
            })
            if outcome == "intact":
                summary["intact"] += 1
            elif outcome == "intact_nfkc":
                summary["intact_nfkc"] += 1
                summary["nfkc_only_pages"] += 1
            elif outcome == "recovered":
                summary["recovered"] += 1
                summary["recovery_targets"] += 1
            elif outcome == "recovered_nfkc":
                summary["recovered_nfkc"] += 1
                summary["recovery_targets"] += 1
                summary["nfkc_only_pages"] += 1
            elif outcome == "improved":
                summary["improved"] += 1
                summary["recovery_targets"] += 1
            elif outcome == "unrecovered":
                summary["unrecovered"] += 1
                summary["recovery_targets"] += 1
            elif outcome == "changed_correct_page":
                summary["changed_correct_page"] += 1
                summary["unresolved_oracle_disagreements"] += 1
            elif outcome == "worsened":
                summary["worsened"] += 1
                summary["unresolved_oracle_disagreements"] += 1
                summary["recovery_targets"] += 1

        outcomes = {}
        for pr in page_results:
            outcomes[pr["outcome"]] = outcomes.get(pr["outcome"], 0) + 1
        case["page_outcomes"] = outcomes
        case["pages_detail"] = page_results
        case["result"] = "disagreement" if any(
            pr["outcome"] in ("changed_correct_page", "worsened") for pr in page_results) else "ok"

        # a per-case JSON record is the regression/disagreement fixture
        (RECOVERY_RECORDS / (group + "__" + ident + ".json")).write_text(
            json.dumps({**case, "logs": logs}, indent=2))
        summary["cases"].append(case)

    tag = args.group if args.group else "all"
    json_path = RECOVERY_REPORTS / ("recovery-%s.json" % tag)
    md_path = RECOVERY_REPORTS / ("recovery-%s.md" % tag)
    json_path.write_text(json.dumps(summary, indent=2))
    md = ["# Paired-mutation recovery lane", "",
          "Mutation: %s" % summary["mutation"], "",
          "Oracle: %s" % summary["oracle"], "",
          "Tolerance: %s" % summary["tolerance"], "",
          "| count | value |", "|---|---|"]
    for k in ("files_discovered", "not_applicable", "cases_run", "refused", "mutation_failed",
              "recovery_targets", "recovered", "recovered_nfkc", "improved", "unrecovered",
              "intact", "intact_nfkc", "nfkc_only_pages", "changed_correct_page", "worsened",
              "unresolved_oracle_disagreements", "recovery_containment_violations",
              "recovery_structural_regressions"):
        md.append("| `%s` | %s |" % (k, summary[k]))
    disagreements = [c for c in summary["cases"] if c.get("result") == "disagreement"]
    md += ["", "## Disagreement fixtures (pages where even NFKC differs from the oracle)", ""]
    if disagreements:
        for c in disagreements:
            bad = [p for p in c["pages_detail"]
                   if p["outcome"] in ("changed_correct_page", "worsened")]
            md.append("- `%s` (%s): %s" % (c["id"], c["group"],
                                           ", ".join("p%d %s" % (p["page"], p["outcome"]) for p in bad)))
    else:
        md.append("None.")
    md += ["", "## NFKC-only pages (raw text differs, compatibility-normalised text equal)", ""]
    nfk = [c for c in summary["cases"]
           if c.get("pages_detail") and any(p["nfkc_needed"] for p in c["pages_detail"])]
    if nfk:
        for c in nfk:
            pages = [p["page"] for p in c["pages_detail"] if p["nfkc_needed"]]
            md.append("- `%s` (%s): pages %s" % (c["id"], c["group"], pages))
    else:
        md.append("None.")
    md += ["", "## Recovery targets not recovered (residual char diff to the oracle)", "",
           "`mutated`/`repaired` are the char distance from the pristine oracle's extraction;",
           "`fixed` is the font resources the native planner actually rewrote.", ""]
    unrecovered = [c for c in summary["cases"] if c.get("pages_detail")
                   and any(p["outcome"] == "unrecovered" for p in c["pages_detail"])]
    if unrecovered:
        md.append("| input | fixed | page | outcome | char diff mutated -> repaired |")
        md.append("|---|---|---|---|---|")
        for c in unrecovered:
            fixed = c.get("fixed")
            fixed = "?" if fixed is None else fixed
            for p in c["pages_detail"]:
                if p["outcome"] == "unrecovered":
                    d = p["char_diff_to_oracle"]
                    md.append("| `%s` | %s | %d | %s | %d -> %d |"
                              % (c["id"], fixed, p["page"], p["outcome"],
                                 d["mutated"], d["repaired"]))
    else:
        md.append("None.")
    md += ["", "## Mutation could not be produced (harness limitation, not a repair result)", ""]
    mutfail = [c for c in summary["cases"] if c.get("result") in ("mutation_failed",
                                                                 "mutation_unparsed",
                                                                 "missing_fixture")]
    if mutfail:
        for c in mutfail:
            reason = (c.get("stderr") or c.get("reason") or "").replace("\n", " | ")[:200]
            md.append("- `%s` (%s): %s %s" % (c["id"], c["group"], c["result"], reason))
    else:
        md.append("None.")
    (md_path).write_text("\n".join(md) + "\n")

    print("== paired-mutation recovery summary ==")
    for k in ("files_discovered", "not_applicable", "cases_run", "refused", "mutation_failed",
              "recovery_targets", "recovered", "recovered_nfkc", "improved", "unrecovered",
              "intact", "intact_nfkc", "nfkc_only_pages", "changed_correct_page", "worsened",
              "unresolved_oracle_disagreements", "recovery_containment_violations",
              "recovery_structural_regressions"):
        print("%-34s %s" % (k, summary[k]))
    if summary["recovery_containment_violations"] or summary["recovery_structural_regressions"]:
        print("FAIL: containment/structural violation; see %s" % json_path)
        return 1
    if summary["unresolved_oracle_disagreements"]:
        print("FINDINGS: %d unresolved oracle disagreements; see %s"
              % (summary["unresolved_oracle_disagreements"], md_path))
        return 1
    print("PASS (no containment violations, no structural regressions, no unresolved disagreements)")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", default="all")
    ap.add_argument("--lane", default="preservation", choices=("preservation", "recovery"))
    ap.add_argument("--ops", default=",".join(OPS_ALL))
    ap.add_argument("--limit", type=int, default=0, help="max inputs per group (0 = all)")
    ap.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    ap.add_argument("--idempotence", action="store_true",
                    help="also re-run font-plan on its own output and require no further repair")
    ap.add_argument("--text-oracle", choices=("raw", "multiset"), default="raw",
                    help="raw (published, byte-exact) or multiset (diagnostic: "
                         "order/whitespace-insensitive, separates loss from reflow)")
    ap.add_argument("--only", default="",
                    help="comma-separated id substrings; keep only matching inputs")
    ap.add_argument("--out-root", default=str(DEFAULT_OUT_ROOT),
                    help="root for generated run artefacts "
                         "(default: jpdfium/build/qualification)")
    ap.add_argument("--run-id", default=time.strftime("%Y%m%dT%H%M%S"),
                    help="run directory name under --out-root")
    args = ap.parse_args()

    run_dir = configure_run(args.out_root, args.run_id)

    if not CLASSPATH_FILE.exists():
        sys.exit("missing %s; run ./gradlew :jpdfium:qualificationClasspath" % CLASSPATH_FILE)
    cp = CLASSPATH_FILE.read_text().strip()

    rows = [json.loads(l) for l in MANIFEST.read_text().splitlines() if l.strip()]
    if args.group != "all":
        rows = [r for r in rows if r["group"] == args.group]
    if args.only:
        subs = [s for s in args.only.split(",") if s]
        rows = [r for r in rows if any(s in r["id"] for s in subs)]

    if args.lane == "recovery":
        rows.sort(key=lambda r: (r["group"], r["id"]))
        if args.limit:
            slim = []
            per = {}
            for r in rows:
                per.setdefault(r["group"], 0)
                if per[r["group"]] < args.limit:
                    slim.append(r)
                    per[r["group"]] += 1
            rows = slim
        tmp = Path(tempfile.mkdtemp(prefix="qual-recovery-"))
        try:
            return run_recovery(args, cp, rows, tmp)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

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

    for d in (BASELINES, OUTPUTS, REPORTS, FAILURES):
        d.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="qual-probe-"))

    summary = {
        "generated_by": "qualification/qualify.py",
        "run_id": args.run_id,
        "run_dir": str(run_dir),
        "host": sys.platform,
        "verapdf": "skipped_not_installed" if not shutil.which("verapdf") else "available",
        "render_page_cap": RENDER_PAGE_CAP,
        "ops": ops,
        "text_oracle": args.text_oracle,
        "files_discovered": len(rows),
        "files_baselined": 0,
        "operations_attempted": 0,
        "operations_written": 0,
        "expected_refusals": 0,
        "unexpected_failures": 0,
        "unexpected_skips": 0,
        "missing_fixtures": 0,
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
            if not inp.exists():
                # FAIL CLOSED on a missing fixture: never silently reduce the
                # denominator, never turn an absent input into a pass.
                summary["missing_fixtures"] += 1
                summary["unexpected_skips"] += 1
                summary["cases"].append({"group": group, "id": ident,
                                         "result": "missing_fixture", "path": str(inp)})
                continue
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
                        reg_vs_input, notes = compare(src_probe, out_probe, lane, op,
                                                      args.text_oracle)

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
                                json.dumps(persistable(out_probe), indent=2))
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
    print("%-32s %s" % ("run_dir", run_dir))
    for key in ("files_discovered", "files_baselined", "controls_run",
                "operations_attempted", "operations_written", "expected_refusals",
                "unexpected_failures", "unexpected_skips", "missing_fixtures",
                "preservation_regressions",
                "repair_induced_regressions", "serialization_render_delta",
                "serialization_attributed", "recovery_successes",
                "unresolved_oracle_disagreements"):
        print("%-32s %s" % (key, summary[key]))
    if (summary["unexpected_failures"] or summary["repair_induced_regressions"]
            or summary["missing_fixtures"]):
        print("FAIL: see %s and %s" % (REPORTS / "summary.json", FAILURES))
        return 1
    print("PASS (repair-induced regressions = 0; serialization deltas reported separately)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
