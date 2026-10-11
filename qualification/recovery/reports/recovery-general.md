# Paired-mutation recovery lane

Mutation: delete /ToUnicode from every Type=Font dictionary

Oracle: poppler pdftotext of the pristine input's own declared /ToUnicode

Tolerance: NFKC compatibility normalisation (ligature/presentation forms), recorded per page

| count | value |
|---|---|
| `files_discovered` | 50 |
| `not_applicable` | 36 |
| `cases_run` | 13 |
| `refused` | 0 |
| `mutation_failed` | 1 |
| `recovery_targets` | 12 |
| `recovered` | 0 |
| `recovered_nfkc` | 0 |
| `improved` | 1 |
| `unrecovered` | 11 |
| `intact` | 30 |
| `intact_nfkc` | 12 |
| `nfkc_only_pages` | 12 |
| `changed_correct_page` | 0 |
| `worsened` | 0 |
| `unresolved_oracle_disagreements` | 0 |
| `recovery_containment_violations` | 0 |
| `recovery_structural_regressions` | 0 |

## Disagreement fixtures (pages where even NFKC differs from the oracle)

None.

## NFKC-only pages (raw text differs, compatibility-normalised text equal)

- `mozilla_tracemonkey.pdf` (general): pages [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14]

## Recovery targets not recovered (residual char diff to the oracle)

`mutated`/`repaired` are the char distance from the pristine oracle's extraction;
`fixed` is the font resources the native planner actually rewrote.

| input | fixed | page | outcome | char diff mutated -> repaired |
|---|---|---|---|---|
| `irs_f1040a.pdf` | 4 | 1 | unrecovered | 7 -> 7 |
| `irs_f1040a.pdf` | 4 | 2 | unrecovered | 17 -> 17 |
| `irs_f1040ez.pdf` | 7 | 1 | unrecovered | 15 -> 15 |
| `irs_f1040ez.pdf` | 7 | 2 | unrecovered | 2 -> 2 |
| `irs_fw4.pdf` | 10 | 2 | unrecovered | 1 -> 1 |
| `mozilla_tracemonkey.pdf` | 15 | 6 | unrecovered | 286 -> 308 |
| `mozilla_tracemonkey.pdf` | 15 | 7 | unrecovered | 202 -> 226 |
| `pdfjs_ArabicCIDTrueType.pdf` | 4 | 1 | unrecovered | 80 -> 80 |
| `pdfjs_PDFJS-9279-reduced.pdf` | 0 | 1 | unrecovered | 4 -> 4 |
| `pdfjs_annotation-highlight-without-appearance.pdf` | 0 | 1 | unrecovered | 64 -> 64 |
| `w3c_dummy.pdf` | 0 | 1 | unrecovered | 14 -> 14 |

## Mutation could not be produced (harness limitation, not a repair result)

- `pdfjs_PDFBOX-4352-0.pdf` (general): mutation_failed rning: cannot load object (6 0 R) into cache | syntax error: syntax error in object (6 0 R) | warning: cannot load object (6 0 R) into cache | syntax error: syntax error in object (6 0 R) | warning: d
