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
- No **paired-mutation recovery lane** yet (healthy original → controlled
  mutation → repair → compare to the original oracle), so
  `recovery_successes` is always 0.
- `unresolved_oracle_disagreements` is always 0 until a second independent
  oracle (e.g. PDF.js), and the veraPDF lane, are added.
- Render comparison covers documents ≤ 25 pages and only the first N pages when
  the cap applies; it is not an all-page claim.
- The full frozen corpus (391 inputs) has not been run to completion in CI; only
  bounded `--limit` sets have been exercised.
