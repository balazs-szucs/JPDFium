# Full-sweep qualification result — FAIL (honest, with evidence)

> **Status update.** This documents the *pre-fix* run (revision `ff9b06e8`). The
> one genuine hard-gate failure below — `pdfjs_ZapfDingbats.pdf` — is fixed in
> `99bd09ca` (the planner now declines the font, `SYMBOLIC_FONT_UNSUPPORTED`,
> instead of synthesising an ASCII identity `/ToUnicode`; regression
> `FontSymbolicEncodingTest`). The raw `summary.json` / `run-full.log` quoted
> here are archived evidence, not tracked.
>
> **Post-fix re-run (same host, native with `99bd09ca`):** `preservation_regressions`
> and `repair_induced_regressions` drop **10 → 8**, removing exactly the two
> `pdfjs_ZapfDingbats.pdf` cases. The gate still fails on the raw byte-exact text
> oracle, but the eight remaining flags are the documented oracle artifacts
> (2 recoveries, 1 NFKC-equivalent ligature set, 1 neutral hyphen↔soft-hyphen)
> plus the `save` reflow — no genuine text/mapping loss remains. Counters and the
> per-case classification are in `reports/RECEIPT.md`.

Command:

```sh
python3 qualification/qualify.py --group all --limit 0 \
  --ops save,font,font-plan,preserve --idempotence --timeout 60
```

Raw counts (`qualification/reports/run-full.log`, `summary.json`, 1564 cases):

```
files_discovered 391   files_baselined 391   controls_run 391
operations_attempted 1173   operations_written 1164   expected_refusals 10
unexpected_failures 1   unexpected_skips 0   missing_fixtures 0
preservation_regressions 10   repair_induced_regressions 10
serialization_render_delta 201   serialization_attributed 404
recovery_successes 0   unresolved_oracle_disagreements 0
verapdf skipped_not_installed
exit 1  =>  FAIL
```

Note: the sweep process was launched before the `missing_fixtures` counter was
added, so its `summary.json` has no such key; the counter is present from the
next run on (see the diagnostic run below).

## Hard-gate failures (11 cases, 6 inputs)

Every failure has `regressions=['text_changed']`, `rc=0`, `out_qpdf=0`, and a
passing idempotence re-run (`fixed=0`, output bytes == input bytes). Attribution
was measured on the `font-plan` output against the pristine input.

| input | op(s) | `fixed` | evidence | verdict |
|---|---|---|---|---|
| `pdfjs_ZapfDingbats.pdf` | font, font-plan | 7 | ~180 dingbat codepoints (U+27xx) replaced by ASCII identity chars; repaired `/ToUnicode 68 0 R` is a 1-byte identity CMap (`<4B> <004B>`, …) | **GENUINE mapping regression** |
| `mozilla_tracemonkey.pdf` | font, font-plan | 15 | diffs are `fi`→`ﬁ` (U+FB01) / `fl`→`ﬂ` (U+FB02); `NFKC` of both extractions is **equal** | Unicode compatibility only |
| `pdfjs_ThuluthFeatures.pdf` | font, font-plan | 2 | original extraction is a code-offset garbage layer (`JDWXUHV` = `GATURES`); repaired output is readable English | recovery / improvement |
| `pdfjs_Test-plusminus.pdf` | font, font-plan | 2 | 115→116 chars, the one change is an inserted `±` | recovery / improvement |
| `pdfjs_XiaoBiaoSong.pdf` | font, font-plan | 2 | exactly 104 `-` (U+002D) → 104 U+00AD; all 586 other chars identical | neutral / inconclusive |
| `pdfjs_Type3WordSpacing.pdf` | save | 0 | char **multiset equal** (48 == 48); only `-layout` line/word grouping differs | serializer reflow artifact |

The only hard failure is `pdfjs_ZapfDingbats.pdf`. It is reproducible from the
committed fixture `jpdfium/src/test/resources/pdfs/general/pdfjs_ZapfDingbats.pdf`
(no external corpus). The failure bundle (`qualification/failures/`, gitignored,
regenerable) holds the input, output, logs, probe JSON and both engines' PNGs.

## Mechanism (ZapfDingbats)

The repaired file contains `/BaseFont /ZapfDingbats /Subtype /Type1` with no
`/Encoding` (a built-in-encoded Type1) and an added `/ToUnicode 68 0 R` whose
CMap is an ASCII identity map. The font's real encoding is ZapfDingbats, so code
`0x4B` should decode to U+272B but the synthesised map says U+004B. The
document's own content labels (`a102 [x2762]`, `a43 [x2730]`) agree with
poppler's un-repaired fallback (`❢` = U+2762). Rendering is unchanged; only the
text layer is wrong.

`qpdf --json=2 --json-key=objects` and
`qpdf --qdf --object-streams=disable <f> - | LC_ALL=C grep -a` were used.

## Oracle weaknesses exposed by the same run

The harness's text oracle is byte-exact and unconditional; three weakness
classes follow. They are **not** used to excuse the ZapfDingbats failure.

1. Byte-exact comparison flags a pure line-break reflow (`md5`-level). A
   diagnostic, order/whitespace-insensitive mode exists:
   `--text-oracle multiset` (never the published verdict — it cannot see a
   reordering). Re-run of the six inputs with it
   (`qualification/reports/summary-diagnostic-multiset.json`) clears **only**
   `pdfjs_Type3WordSpacing.pdf` (`save`), i.e. it separates that reflow from a
   real loss; it does not clear the other ten.
2. No `NFKC` tolerance, so ligature normalisation (`mozilla_tracemonkey`) is
   flagged although `NFKC` makes both extractions equal.
3. No notion of "the input declared no usable `/ToUnicode`", so a genuine
   recovery on a document whose fallback extraction was garbage
   (`ThuluthFeatures`, `Test-plusminus`) is scored as a preservation regression.

Fixing 2 and 3 means capability-aware classification (compare against whichever
oracle the input itself supported), which is a semantic change to the gate and
is **not** applied here without an explicit decision. Fixing 1 is done, as a
diagnostic only.

## Reproduce

```sh
bash native/build-real.sh
./gradlew :jpdfium:qualificationClasspath
python3 qualification/qualify.py --group general \
  --only pdfjs_ZapfDingbats --ops save,font,font-plan --timeout 60   # expect FAIL
python3 qualification/qualify.py --group general --only pdfjs_Type3WordSpacing \
  --ops save --timeout 60 --text-oracle multiset                     # expect PASS
```
