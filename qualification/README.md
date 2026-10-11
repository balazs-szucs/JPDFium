# JPDFium differential qualification harness

A **differential qualification pipeline** for the font-intelligence /
structural-repair branch. It answers four questions separately, with independent
evidence, instead of a single "repair success rate":

1. Does it recover the intended information on repairable inputs?
2. Does it preserve healthy PDFs?
3. Does it refuse unsupported cases without changing anything?
4. Does it stay bounded, deterministic and contained on hostile inputs?

The conclusion this harness is designed to produce is **not** "N tests passed",
but: *on this frozen input set, with these pinned engines and budgets, every
required case executed; preserving operations introduced zero unexplained
regressions attributable to the code under test; unsupported cases refused
without publication.*

## Lanes

| Lane | Input | Operation | Required result |
|---|---|---|---|
| `save_control` | healthy PDF | `save` (open + re-save, no mutation) | the **serialization baseline** everything else is compared against |
| `healthy_preservation` | healthy PDF | `font`, `font-plan`, `preserve` | no repair-attributable change in pages / text / render / qpdf |
| `robustness_containment` | malformed / adversarial | any | exit ∈ {0,3}, no crash/hang, an explicit refusal publishes no bytes |
| `paired_mutation_recovery` | healthy PDF, then a controlled `/ToUnicode` deletion | `font-plan` on the mutated copy | a page the mutation disturbed returns to the pristine oracle; the mutation never disturbs a page the repair then leaves wrong |

A safe refusal is never scored as a recovery. A fixture that is *expected* to be
rejected is never scored as a regression merely for being rejected. An
unavailable probe is recorded explicitly and never turned into a pass.

## Workspace layout

```
qualification/
  inputs/       immutable frozen inputs (general/ generated/ pathological/)
  manifests/    inputs.jsonl  — pre-repair facts per input
  baselines/    <group>/<id>.json + <id>.control.json — independent probes
  outputs/      <group>/<id>/<op>.pdf (+ .report.txt for preserve)
  reports/      summary.json, run-*.log
  failures/     <group>/<id>/<op>/ — input/output/logs/probes/pixels
  mutate-tounicode.js             controlled /ToUnicode deletion for the recovery lane
  recovery/
    mutated/    <group>/<id>      generated mutation (gitignored)
    repaired/   <group>/<id>      repair output        (gitignored)
    records/    <group>__<id>.json per-case oracle ladder (committed fixtures)
    reports/    recovery-<group>.json, recovery-<group>.md
```

Inputs are never written over. Every output goes under `outputs/`.

## Reproduce

```sh
# 1. real native + test classpath (Java 25 required)
bash native/build-real.sh
./gradlew :jpdfium:qualificationClasspath        # -> jpdfium/build/qualification-classpath.txt

# 2. frozen, pinned/generated inputs (NO external corpus downloads)
./gradlew :jpdfium:generateCorpus -Pcorpus.count=300 -Pcorpus.seed=42
CP=$(cat jpdfium/build/qualification-classpath.txt)
java --enable-native-access=ALL-UNNAMED -cp "$CP" \
  stirling.software.jpdfium.qualification.QualificationFixtures \
  qualification/inputs/pathological
# plus the bundled real PDFs copied from jpdfium/src/test/resources/pdfs/general

# 3. freeze the pre-repair manifest (SHA-256, bytes, pages, qpdf --check code)
bash qualification/build-manifest.sh

# 4. run a bounded qualification set
python3 qualification/qualify.py \
  --group all --limit 12 \
  --ops save,font,font-plan,preserve --idempotence --timeout 60

# 5. run the paired-mutation recovery lane (separate report, never overwrites step 4)
python3 qualification/qualify.py --lane recovery --group general   --limit 0 --timeout 60
python3 qualification/qualify.py --lane recovery --group generated --limit 0 --timeout 60
```

The driver launches exactly one JVM per (input, operation) so a native crash is
contained to one case. It does not start Gradle per PDF.

## Pinned engines

Java 25 (Temurin); the real PDFium native bridge (not the stub); `qpdf`;
poppler (`pdftoppm`, `pdfinfo`, `pdftotext`); mupdf (`mutool`). Rendering and
text extraction compare each engine against **itself**, before vs after, with
identical settings.

## Budgets / limits

- `RENDER_PAGE_CAP = 25`: documents with more pages are recorded as
  `render_scope=skipped_large` and **not** claimed render-preserved. Sampling is
  fast feedback, never evidence for an all-page claim.
- Per-case process timeout (default 120 s; pass `--timeout`) enforced with a
  Python subprocess timeout, because macOS has no GNU `timeout`.
- `java -Xmx512m` bounds the JVM heap only. It does **not** bound native
  PDFium/QPDF/FreeType allocations; a cgroup/container is the correct boundary
  and is not applied here.

## veraPDF

veraPDF is not installed on this host. Every probe records
`verapdf=skipped_not_installed`; the conformance lane is an **explicit skip**,
never a pass. Install a pinned veraPDF and add the flavour/rule-diff lane before
making any conformance claim.

## Differential attribution (why the `save` control exists)

Re-serialization alone changes bytes — and, on some AcroForm PDFs, changes
raster output. So a repair-attributable delta is only meaningful **relative to
the `save` control**:

- `save` control vs input → `serialization_render_delta` (**reported, not a
  hard failure**; it is a codec property, not the code under test).
- `font` / `font-plan` / `preserve` vs the `save` control → the
  **repair-attributable** set. A delta that also exists in the control is
  recorded as `serialization_attributed`; only a delta **beyond** the control is
  a `preservation_regression` (hard gate).

### Known serializer finding (surfaced, not hidden)

`PdfDocument.saveBytes()` re-encodes numeric literals in AcroForm/widget
appearance streams at ~float32 precision, e.g. `.8` → `.80000001`,
`726.8898` → `726.88977`. Registering item: `general/all_form_fields.pdf`.
Poppler's rasterizer deterministically amplifies this into glyph-edge pixel
flips — max channel delta 255, 25142 / 2005644 pixels changed (1.2536%) — while
mutool render and `pdftotext` output are unchanged. The qpdf-based `preserve`
path is pixel-clean. This is a serializer/codec behaviour, not a font-repair
defect, and is deliberately **surfaced with a documented fixture** rather than
tolerated silently. Fixing the serializer's float precision is out of scope for
this branch.

## Paired-mutation recovery lane

The preservation lane proves the repair changes nothing. This lane proves it can
change the *right* thing. It is offline (does not touch the preservation
reports) and is run with `--lane recovery`.

Per applicable input it builds a pair:

- **A** — the pristine input. Its own declared `/ToUnicode`, read back through
  `pdftotext`, is the **oracle**.
- **B** — A with `/ToUnicode` deleted from every `Type=Font` dictionary by
  `qualification/mutate-tounicode.js` (deterministic, derived only from A, no
  external corpus).
- **R** — B after `FontIntelligence` `font-plan`.

Each page is classified against the oracle (`O`, `M`, `R` are the page's
extracted text in A, B, R):

| outcome | meaning |
|---|---|
| `intact` | the mutation did not disturb this page and R is byte-equal to the oracle |
| `intact_nfkc` | as above but equal only after NFKC |
| `recovered` | a disturbed page returned exactly to the oracle |
| `recovered_nfkc` | a disturbed page returned to the oracle after NFKC |
| `improved` | a disturbed page is strictly closer to the oracle than the mutation left it |
| `unrecovered` | the repair left the page materially unchanged (R NFKC-equal to M) |
| `changed_correct_page` | a page the mutation never disturbed was altered — a disagreement |
| `worsened` | a disturbed page moved *away* from the oracle — a disagreement |

`unresolved_oracle_disagreements` = `changed_correct_page` + `worsened`. A
non-zero count is a **finding** (exit 1) with a per-page fixture in
`recovery/records/`. A refusal (`rc == 3`) is recorded as `refused`, never as a
recovery and never as corruption. An input the mutation cannot round-trip
(mutool refuses to save) is recorded as `mutation_failed` and excluded from the
denominator — a harness limitation, not a repair result.

**Tolerance (documented, surfaced, not hidden):** NFKC compatibility
normalisation. The repair reconstructs `/ToUnicode` from the font's own CMap,
which maps ligature glyphs to their compatibility codepoints (`ﬁ` U+FB01) where
an author's hand-written map may have decomposed them to ASCII (`fi`). NFKC
folds these; pages where that is the only difference are `*_nfkc` and listed
explicitly. This is a Unicode-compatibility normalisation disagreement, not
corruption.

**Oracle caveat.** The oracle is poppler's extraction of the pristine file's own
declared `/ToUnicode` — an end-to-end, user-visible oracle — not a
decoder-independent comparison of the decoded CMap.

### Measured result and finding (both corpora, reported as-is)

`general` (`recovery-general.md`): 50 inputs, 36 `not_applicable` (no declared
`/ToUnicode` to remove), 1 `mutation_failed`, 13 run. Of 12 disturbed pages: 1
`improved` (`basic-text.pdf`, residual char diff 1531 → 1), 11 `unrecovered`;
30 `intact`, 12 `intact_nfkc`, 0 disagreements, 0 containment/structural
violations. Headline: reconstructing `/ToUnicode` repairs a badly damaged page
but does not restore extraction where the planner declines or cannot fully map.

`generated` (`recovery-generated.md`): 300 inputs, 205 `not_applicable`, 95 run,
0 failed. Of 357 disturbed pages: **0 `recovered`, 0 `improved`, 357
`unrecovered`**; 12 `intact`, 0 disagreements, 0 containment/structural
violations.

Measured cause on the synthetic corpus (sample, not a general proof): every
generated original declares exactly one `/ToUnicode`, on a font whose CMap uses
a **2-byte** codespace (`<0000> <FFFF>`, `beginbfrange`). Each repaired file
re-emits 15–42 `/ToUnicode` maps, **all 1-byte** (`<00> <FF>`, identity ASCII
`<20>`→U+0020 …), and never re-emits a 2-byte map. Poppler's per-page extraction
of the repaired file is byte-identical to the damaged input on all 357 disturbed
pages — i.e. the re-emitted maps do not carry the deleted information for those
fonts. The synthetic generator's maps are an author's arbitrary CID map, not
recoverable from the font program, so reconstruct-from-evidence has a real
ceiling here. Recorded as a limitation, not misattributed to corruption, and not
claimed as recovery.

## Hard release gate

```
unexpected_failures            = 0
repair_induced_regressions     = 0
```

plus, reported and kept in the denominator rather than removed: safe refusals,
unsupported cases, `serialization_render_delta`, limited render scopes, and
`unresolved_oracle_disagreements`.

## Documented gaps (honest, not over-claimed)

- No **independent `/ToUnicode` mapping oracle** yet: declared-mapping
  preservation is currently proxied by `pdftotext` + render + `qpdf`, not by a
  decoder-independent comparison of the decoded CMap.
- A **paired-mutation recovery lane** now exists (`--lane recovery`), run over
  `general` and `generated`. On `general`
  (`recovery/reports/recovery-general.md`): of 12 pages the mutation actually
  disturbed, 0 returned exactly to the oracle, 1 was `improved`
  (`basic-text.pdf`, residual char distance 1531 → 1), and 11 were `unrecovered`
  (mostly small residuals: 1–17 chars on the IRS forms, plus pages where the
  planner rewrote 0 fonts because the font is
  `MANUAL_CONFIRMATION_REQUIRED`/`REPLACEMENT_REQUIRED` and correctly declined —
  `irs_*`, `pdfjs_ArabicCIDTrueType`, `pdfjs_PDFJS-9279-reduced`, `w3c_dummy`).
  On `generated` (`recovery/reports/recovery-generated.md`): 357 disturbed
  pages, **0 recovered, 0 improved, 357 unrecovered**, 0 disagreements — the
  disturbed font is a CID font whose 2-byte author map is not re-emitted (all
  re-emitted maps are 1-byte), so nothing is restored; a documented ceiling of
  reconstruct-from-evidence, not a corruption and not a recovery. Headline
  honest result: reconstructing `/ToUnicode` improves a badly-damaged page but
  does not by itself restore extraction where the damage is in a font the
  planner declines or cannot fully map. It is not a "recovery success rate" and
  is never read as one.
- `unresolved_oracle_disagreements` is reported by the recovery lane
  (`recovery/reports/recovery-<group>.json`); it is 0 on both `general` and
  `generated`. A second independent oracle (e.g. PDF.js) and the veraPDF lane are
  still absent.
- Render comparison covers documents ≤ 25 pages and only the first N pages when
  the cap applies; it is not an all-page claim.
- The full frozen corpus (391 inputs) has not been run to completion in CI; only
  bounded `--limit` sets have been exercised.
