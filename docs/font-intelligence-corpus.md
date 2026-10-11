# Font-intelligence corpus

This directory holds the **specification and machinery** for the font-intelligence
test suite — not a folder of thousands of PDFs. The goal is documented coverage of
every supported mechanism, interaction, and failure contract, so that a failure tells
you *which* contract broke.

## What is covered today

The initial slice is deliberately small and complete. It exercises the full vertical
path end to end:

```
authored text
  -> controlled PDF code mapping
  -> a broken semantic mapping (/ToUnicode removed)
  -> evidence-based Unicode resolution
  -> capability report (truthful)
  -> repair applied to the source state
  -> reopen and independent verification
```

Files:

- `jpdfium/src/test/resources/font-corpus/manifest.json` — the versioned, flat-JSON
  case list (parsed by `util.NativeJsonParser`, which cannot read nested objects or
  commas inside string values).
- `jpdfium/src/test/java/stirling/software/jpdfium/fonts/intel/FontCorpusTest.java` —
  the manifest-driven runner.
- `jpdfium/src/test/java/.../FontIntelligenceVerticalSliceTest.java` — the single
  hand-written slice (full font, full embed, then strip `/ToUnicode`).

## Manifest schema

Each case is a flat object. `expect_capability` is a `|`-separated set of allowed
capabilities; an expected unsupported/ambiguous result is a **pass**.

| field | meaning |
| --- | --- |
| `id` | stable case id |
| `schema_version` | manifest schema version |
| `origin` | `controlled` or `real` |
| `license` | asset license / redistribution note |
| `kind` | fixture builder: `full_cid`, `broken_cid`, `subset_no_cmap`, `resource` |
| `source` | classpath resource for `kind=resource` |
| `expect_capability` | allowed capabilities (pipe-separated) |
| `expect_repair` | `true` \| `false` \| `any` |
| `expect_extract` | substring that must survive repair (may be empty) |
| `notes` | rationale, including why an unsupported result is expected |

## Oracles

PDFBox is a **test-scope** dependency used only to author controlled fixtures and to
independently verify the result. It never produces the expected mapping, so a bug in
the production resolver cannot hide behind a shared assumption. The asserted
capabilities come from JPDFium's own evidence; the asserted Unicode comes from PDFBox
re-extracting the saved document.

## Deliberate behaviour the corpus pins

- **Evidence must survive.** A subset font whose Unicode cmap was stripped has no
  recoverable mapping; the correct answer is `REPLACEMENT_REQUIRED`, never a guess.
  (`pdfjs_IdentityToUnicodeMap_charCodeOf.pdf`'s introspection and the
  `controlled-subset-no-cmap` case pin this.)
- **Capability must match repair.** An unsupported subtype (`/Type3`) reports
  `UNSUPPORTED_OPERATION` and is never repaired. (Regression pinned by `real-type3`.)
- **Repair is applied to a prepared source state** and re-inspected afterwards.

## Full font suite

| class | scope |
| --- | --- |
| `FontIntelligenceTest` | pure Java: types, parsing, capability derivation, catalog identity |
| `FontIntelligenceNativeTest` | native: evidence, recovery/repair consistency across real fixtures, shaping/subsetting |
| `FontIntelligenceVerticalSliceTest` | one authored clean→broken→repaired document |
| `FontCorpusTest` | manifest-driven contracts (this file) |
| `host/HostFontOpsTest` | host protocol `fonts.*` ops over the wire |

## Growing the corpus

Treat this as layers A–H over time: controlled font fixtures, controlled PDF fixtures,
producer-realistic output, historical regressions, observational production samples,
bounded adversarial/fuzz cases, performance/retention, and a held-out partition. Every
asset must record origin, revision/URL, SHA-256, license, and attribution; a failed
acquisition must fail loudly, never silently skip. The most valuable corpus is not the
largest one — it is the one that catches an incorrect repair, a dropped glyph, or a
silent placement change, and names the contract that failed.
