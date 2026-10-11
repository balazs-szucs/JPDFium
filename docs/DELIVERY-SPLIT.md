# Delivery split

The font-intelligence work landed as one branch. This document maps the single
delivery into **three reviewable deliverables** so it can be reviewed (and,
where useful, merged) independently. The branch was rebuilt off the upstream
merge-base and the content re-committed in three path-grouped commits; the
resulting tree is byte-identical to the original branch head.

- Base (upstream merge-base): `0d0945aff5a4100f65a2d5f13dbb8ab87748a5ac`
- Original branch: `feat/font-intelligence` (kept as the evidence trail)
- Clean branch: `feat/font-intelligence-clean`
- Total: 86 files, 16776 insertions(+), 209 deletions(-)

| # | Deliverable | Commit | Files |
|---|-------------|--------|-------|
| 1 | Structural repair correctness | `47d41916` | 24 |
| 2 | Font intelligence + safe mapping repair | `7e27eecb` | 41 |
| 3 | Qualification tooling + corpus | `dbfa3701` | 21 |

## 1. Structural repair correctness — `47d41916`

`fix(repair): truthful structural repair with preservation guards`

| Path | Role |
|------|------|
| `native/bridge/src/jpdfium_repair.cpp` | native structural-repair path |
| `jpdfium/src/main/java/stirling/software/jpdfium/panama/RepairLib.java` | Panama binding |
| `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfRepair.java` | repair entry point |
| `jpdfium/src/main/java/stirling/software/jpdfium/doc/RepairInspection.java` | inspection surface |
| `jpdfium/src/main/java/stirling/software/jpdfium/doc/RepairReport.java` | report surface |
| `jpdfium/src/main/java/stirling/software/jpdfium/doc/RepairResult.java` | result model |
| `jpdfium/src/test/java/stirling/software/jpdfium/doc/**` | 14 preservation / corruption / damage-category / policy / revision / fault-injection suites |

Shared scaffolding lands here because the repair path is its first consumer:
`native/bridge/include/jpdfium.h`, `native/bridge/src/jpdfium_stub.cpp`,
`jpdfium/src/main/java/module-info.java`,
`jpdfium/src/main/java/stirling/software/jpdfium/util/NativeJsonParser.java`.

## 2. Font intelligence + safe mapping repair — `7e27eecb`

`feat(fonts): font intelligence and safe ToUnicode mapping repair`

| Path | Role |
|------|------|
| `native/bridge/src/jpdfium_advanced.cpp` | native evidence / plan / fix pipeline |
| `jpdfium/src/main/java/stirling/software/jpdfium/panama/FontLib.java` | Panama binding |
| `jpdfium/src/main/java/stirling/software/jpdfium/panama/JpdfiumH.java` | native handles |
| `jpdfium/src/main/java/stirling/software/jpdfium/fonts/FontNormalizer.java` | NFKC tolerance surface |
| `jpdfium/src/main/java/stirling/software/jpdfium/fonts/intel/**` | intel model (FontIntelligence, FontEvidence, FontCatalog, FontRepairPlan, diagnostics) |
| `jpdfium/src/test/java/stirling/software/jpdfium/fonts/**` | fonts + fonts/intel suites |

This deliverable carries the **safe mapping repair** fix with the operation it
protects: a base-14 symbolic font with no `/Encoding` and no `/FontDescriptor`
is **declined** (`SYMBOLIC_FONT_UNSUPPORTED`) instead of synthesising a 1-byte
ASCII identity `/ToUnicode`. Mandatory regression: `ZapfDingbats` `0x4B` must
map to `U+272B`, not `U+004B`
(`FontSymbolicEncodingTest`).

## 3. Qualification tooling + corpus — `dbfa3701`

`test(qualification): differential qualification harness and font corpus`

| Path | Role |
|------|------|
| `qualification/qualify.py` | differential driver (`save` vs. mutating ops) |
| `qualification/build-manifest.sh`, `qualification/manifests/inputs.jsonl` | frozen 391-input manifest |
| `qualification/mutate-tounicode.js` | controlled `/ToUnicode` mutation |
| `qualification/reports/RECEIPT.md` | compact qualification receipt |
| `qualification/reports/FINDINGS-full-sweep.md` | findings, with pre-/post-fix status |
| `jpdfium/src/test/java/stirling/software/jpdfium/qualification/**` | isolated per-op worker + fixtures |
| `jpdfium/src/main/java/stirling/software/jpdfium/host/**` | host CLI |
| `jpdfium/src/test/resources/font-corpus/**` | pinned font fixtures |
| `jpdfium/build.gradle.kts`, `docs/font-intelligence-corpus.md` | build + corpus docs |
| `corpus/`, `PdfCorpus.java`, `host/HostFontOpsTest.java` | corpus plumbing |

Generated run output is **not** in the tree: it is emitted under
`jpdfium/build/qualification/<run-id>/` (and legacy `qualification/outputs/**`
is ignored). See `qualification/README.md` and the receipt for the tracked vs.
generated layout.

## Where the safety fixes live

Safety fixes travel with the operation they protect:

- structural-repair preservation guards → deliverable 1;
- symbolic-font refusal + ZapfDingbats mapping regression → deliverable 2;
- differential attribution gates (only `R \ C` is `repair_induced`) →
  deliverable 3.

## Verification

Intermediate commits are review groupings ordered by dependency; the **final
tree** is what is compiled and verified:

```
./gradlew :jpdfium:test --tests 'stirling.software.jpdfium.fonts.intel.*'   # 95 tests, 0 failures
python3 qualification/qualify.py --group all --limit 12                    # bounded qualification, PASS
```

The final tree on `feat/font-intelligence-clean` is byte-identical to the
verified `feat/font-intelligence` head (`git diff` is empty).
