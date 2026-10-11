# Paired-mutation recovery lane

Mutation: delete /ToUnicode from every Type=Font dictionary

Oracle: poppler pdftotext of the pristine input's own declared /ToUnicode

Tolerance: NFKC compatibility normalisation (ligature/presentation forms), recorded per page

| count | value |
|---|---|
| `files_discovered` | 300 |
| `not_applicable` | 205 |
| `cases_run` | 95 |
| `refused` | 0 |
| `mutation_failed` | 0 |
| `recovery_targets` | 357 |
| `recovered` | 0 |
| `recovered_nfkc` | 0 |
| `improved` | 0 |
| `unrecovered` | 357 |
| `intact` | 12 |
| `intact_nfkc` | 0 |
| `nfkc_only_pages` | 0 |
| `changed_correct_page` | 0 |
| `worsened` | 0 |
| `unresolved_oracle_disagreements` | 0 |
| `recovery_containment_violations` | 0 |
| `recovery_structural_regressions` | 0 |

## Disagreement fixtures (pages where even NFKC differs from the oracle)

None.

## NFKC-only pages (raw text differs, compatibility-normalised text equal)

None.

## Recovery targets not recovered (residual char diff to the oracle)

`mutated`/`repaired` are the char distance from the pristine oracle's extraction;
`fixed` is the font resources the native planner actually rewrote.

| input | fixed | page | outcome | char diff mutated -> repaired |
|---|---|---|---|---|
| `gen-0000.pdf` | 42 | 1 | unrecovered | 116 -> 116 |
| `gen-0000.pdf` | 42 | 2 | unrecovered | 34 -> 34 |
| `gen-0000.pdf` | 42 | 3 | unrecovered | 140 -> 140 |
| `gen-0000.pdf` | 42 | 4 | unrecovered | 66 -> 66 |
| `gen-0000.pdf` | 42 | 5 | unrecovered | 23 -> 23 |
| `gen-0003.pdf` | 16 | 1 | unrecovered | 162 -> 162 |
| `gen-0003.pdf` | 16 | 2 | unrecovered | 95 -> 95 |
| `gen-0009.pdf` | 23 | 1 | unrecovered | 65 -> 65 |
| `gen-0009.pdf` | 23 | 2 | unrecovered | 79 -> 79 |
| `gen-0009.pdf` | 23 | 3 | unrecovered | 63 -> 63 |
| `gen-0012.pdf` | 20 | 1 | unrecovered | 72 -> 72 |
| `gen-0012.pdf` | 20 | 2 | unrecovered | 44 -> 44 |
| `gen-0015.pdf` | 15 | 1 | unrecovered | 212 -> 212 |
| `gen-0015.pdf` | 15 | 2 | unrecovered | 29 -> 29 |
| `gen-0015.pdf` | 15 | 3 | unrecovered | 164 -> 164 |
| `gen-0018.pdf` | 19 | 1 | unrecovered | 97 -> 97 |
| `gen-0018.pdf` | 19 | 2 | unrecovered | 115 -> 115 |
| `gen-0021.pdf` | 52 | 1 | unrecovered | 20 -> 20 |
| `gen-0021.pdf` | 52 | 2 | unrecovered | 152 -> 152 |
| `gen-0021.pdf` | 52 | 3 | unrecovered | 48 -> 48 |
| `gen-0021.pdf` | 52 | 4 | unrecovered | 117 -> 117 |
| `gen-0021.pdf` | 52 | 5 | unrecovered | 22 -> 22 |
| `gen-0021.pdf` | 52 | 7 | unrecovered | 39 -> 39 |
| `gen-0021.pdf` | 52 | 8 | unrecovered | 185 -> 185 |
| `gen-0021.pdf` | 52 | 9 | unrecovered | 175 -> 175 |
| `gen-0021.pdf` | 52 | 10 | unrecovered | 134 -> 134 |
| `gen-0024.pdf` | 29 | 1 | unrecovered | 74 -> 74 |
| `gen-0024.pdf` | 29 | 2 | unrecovered | 76 -> 76 |
| `gen-0024.pdf` | 29 | 3 | unrecovered | 97 -> 97 |
| `gen-0027.pdf` | 38 | 1 | unrecovered | 21 -> 21 |
| `gen-0027.pdf` | 38 | 2 | unrecovered | 22 -> 22 |
| `gen-0027.pdf` | 38 | 3 | unrecovered | 73 -> 73 |
| `gen-0027.pdf` | 38 | 4 | unrecovered | 138 -> 138 |
| `gen-0030.pdf` | 15 | 1 | unrecovered | 147 -> 147 |
| `gen-0030.pdf` | 15 | 2 | unrecovered | 105 -> 105 |
| `gen-0033.pdf` | 37 | 1 | unrecovered | 239 -> 239 |
| `gen-0033.pdf` | 37 | 2 | unrecovered | 107 -> 107 |
| `gen-0033.pdf` | 37 | 3 | unrecovered | 211 -> 211 |
| `gen-0033.pdf` | 37 | 4 | unrecovered | 141 -> 141 |
| `gen-0033.pdf` | 37 | 5 | unrecovered | 72 -> 72 |
| `gen-0039.pdf` | 23 | 1 | unrecovered | 42 -> 42 |
| `gen-0039.pdf` | 23 | 2 | unrecovered | 93 -> 93 |
| `gen-0039.pdf` | 23 | 3 | unrecovered | 7 -> 7 |
| `gen-0039.pdf` | 23 | 4 | unrecovered | 22 -> 22 |
| `gen-0042.pdf` | 18 | 1 | unrecovered | 179 -> 179 |
| `gen-0042.pdf` | 18 | 2 | unrecovered | 252 -> 252 |
| `gen-0042.pdf` | 18 | 3 | unrecovered | 54 -> 54 |
| `gen-0045.pdf` | 47 | 1 | unrecovered | 117 -> 117 |
| `gen-0045.pdf` | 47 | 2 | unrecovered | 347 -> 347 |
| `gen-0045.pdf` | 47 | 3 | unrecovered | 161 -> 161 |
| `gen-0045.pdf` | 47 | 4 | unrecovered | 44 -> 44 |
| `gen-0048.pdf` | 23 | 1 | unrecovered | 118 -> 118 |
| `gen-0048.pdf` | 23 | 2 | unrecovered | 31 -> 31 |
| `gen-0048.pdf` | 23 | 3 | unrecovered | 280 -> 280 |
| `gen-0048.pdf` | 23 | 4 | unrecovered | 84 -> 84 |
| `gen-0051.pdf` | 23 | 1 | unrecovered | 44 -> 44 |
| `gen-0051.pdf` | 23 | 2 | unrecovered | 120 -> 120 |
| `gen-0051.pdf` | 23 | 3 | unrecovered | 236 -> 236 |
| `gen-0051.pdf` | 23 | 4 | unrecovered | 69 -> 69 |
| `gen-0051.pdf` | 23 | 5 | unrecovered | 132 -> 132 |
| `gen-0054.pdf` | 26 | 1 | unrecovered | 76 -> 76 |
| `gen-0054.pdf` | 26 | 2 | unrecovered | 4 -> 4 |
| `gen-0054.pdf` | 26 | 3 | unrecovered | 74 -> 74 |
| `gen-0054.pdf` | 26 | 4 | unrecovered | 40 -> 40 |
| `gen-0057.pdf` | 51 | 2 | unrecovered | 123 -> 123 |
| `gen-0057.pdf` | 51 | 3 | unrecovered | 21 -> 21 |
| `gen-0057.pdf` | 51 | 4 | unrecovered | 412 -> 412 |
| `gen-0057.pdf` | 51 | 5 | unrecovered | 136 -> 136 |
| `gen-0057.pdf` | 51 | 6 | unrecovered | 104 -> 104 |
| `gen-0060.pdf` | 25 | 1 | unrecovered | 242 -> 242 |
| `gen-0060.pdf` | 25 | 2 | unrecovered | 90 -> 90 |
| `gen-0060.pdf` | 25 | 4 | unrecovered | 11 -> 11 |
| `gen-0060.pdf` | 25 | 5 | unrecovered | 193 -> 193 |
| `gen-0063.pdf` | 41 | 1 | unrecovered | 236 -> 236 |
| `gen-0063.pdf` | 41 | 2 | unrecovered | 37 -> 37 |
| `gen-0063.pdf` | 41 | 3 | unrecovered | 104 -> 104 |
| `gen-0063.pdf` | 41 | 4 | unrecovered | 41 -> 41 |
| `gen-0063.pdf` | 41 | 5 | unrecovered | 71 -> 71 |
| `gen-0063.pdf` | 41 | 6 | unrecovered | 38 -> 38 |
| `gen-0066.pdf` | 28 | 1 | unrecovered | 31 -> 31 |
| `gen-0066.pdf` | 28 | 2 | unrecovered | 52 -> 52 |
| `gen-0066.pdf` | 28 | 3 | unrecovered | 492 -> 492 |
| `gen-0066.pdf` | 28 | 4 | unrecovered | 215 -> 215 |
| `gen-0069.pdf` | 42 | 1 | unrecovered | 75 -> 75 |
| `gen-0069.pdf` | 42 | 2 | unrecovered | 22 -> 22 |
| `gen-0069.pdf` | 42 | 4 | unrecovered | 58 -> 58 |
| `gen-0069.pdf` | 42 | 5 | unrecovered | 94 -> 94 |
| `gen-0069.pdf` | 42 | 6 | unrecovered | 236 -> 236 |
| `gen-0072.pdf` | 29 | 1 | unrecovered | 162 -> 162 |
| `gen-0072.pdf` | 29 | 2 | unrecovered | 15 -> 15 |
| `gen-0072.pdf` | 29 | 3 | unrecovered | 114 -> 114 |
| `gen-0075.pdf` | 41 | 1 | unrecovered | 36 -> 36 |
| `gen-0075.pdf` | 41 | 2 | unrecovered | 125 -> 125 |
| `gen-0075.pdf` | 41 | 3 | unrecovered | 273 -> 273 |
| `gen-0075.pdf` | 41 | 4 | unrecovered | 173 -> 173 |
| `gen-0075.pdf` | 41 | 5 | unrecovered | 83 -> 83 |
| `gen-0075.pdf` | 41 | 6 | unrecovered | 73 -> 73 |
| `gen-0078.pdf` | 26 | 1 | unrecovered | 134 -> 134 |
| `gen-0078.pdf` | 26 | 2 | unrecovered | 184 -> 184 |
| `gen-0078.pdf` | 26 | 3 | unrecovered | 56 -> 56 |
| `gen-0078.pdf` | 26 | 4 | unrecovered | 86 -> 86 |
| `gen-0081.pdf` | 10 | 1 | unrecovered | 5 -> 5 |
| `gen-0084.pdf` | 30 | 1 | unrecovered | 168 -> 168 |
| `gen-0084.pdf` | 30 | 2 | unrecovered | 68 -> 68 |
| `gen-0084.pdf` | 30 | 3 | unrecovered | 59 -> 59 |
| `gen-0084.pdf` | 30 | 4 | unrecovered | 41 -> 41 |
| `gen-0084.pdf` | 30 | 5 | unrecovered | 73 -> 73 |
| `gen-0084.pdf` | 30 | 6 | unrecovered | 19 -> 19 |
| `gen-0087.pdf` | 47 | 1 | unrecovered | 54 -> 54 |
| `gen-0087.pdf` | 47 | 2 | unrecovered | 44 -> 44 |
| `gen-0087.pdf` | 47 | 3 | unrecovered | 112 -> 112 |
| `gen-0087.pdf` | 47 | 4 | unrecovered | 42 -> 42 |
| `gen-0087.pdf` | 47 | 5 | unrecovered | 10 -> 10 |
| `gen-0087.pdf` | 47 | 6 | unrecovered | 117 -> 117 |
| `gen-0087.pdf` | 47 | 7 | unrecovered | 32 -> 32 |
| `gen-0090.pdf` | 36 | 1 | unrecovered | 132 -> 132 |
| `gen-0090.pdf` | 36 | 2 | unrecovered | 142 -> 142 |
| `gen-0090.pdf` | 36 | 3 | unrecovered | 69 -> 69 |
| `gen-0090.pdf` | 36 | 4 | unrecovered | 52 -> 52 |
| `gen-0090.pdf` | 36 | 5 | unrecovered | 73 -> 73 |
| `gen-0093.pdf` | 15 | 1 | unrecovered | 176 -> 176 |
| `gen-0093.pdf` | 15 | 2 | unrecovered | 218 -> 218 |
| `gen-0096.pdf` | 7 | 1 | unrecovered | 254 -> 254 |
| `gen-0099.pdf` | 3 | 1 | unrecovered | 4 -> 4 |
| `gen-0102.pdf` | 46 | 1 | unrecovered | 54 -> 54 |
| `gen-0102.pdf` | 46 | 2 | unrecovered | 88 -> 88 |
| `gen-0102.pdf` | 46 | 3 | unrecovered | 91 -> 91 |
| `gen-0102.pdf` | 46 | 4 | unrecovered | 41 -> 41 |
| `gen-0102.pdf` | 46 | 5 | unrecovered | 58 -> 58 |
| `gen-0102.pdf` | 46 | 6 | unrecovered | 65 -> 65 |
| `gen-0105.pdf` | 18 | 1 | unrecovered | 358 -> 358 |
| `gen-0105.pdf` | 18 | 2 | unrecovered | 311 -> 311 |
| `gen-0108.pdf` | 12 | 1 | unrecovered | 113 -> 113 |
| `gen-0111.pdf` | 22 | 1 | unrecovered | 57 -> 57 |
| `gen-0111.pdf` | 22 | 2 | unrecovered | 181 -> 181 |
| `gen-0114.pdf` | 4 | 1 | unrecovered | 28 -> 28 |
| `gen-0117.pdf` | 20 | 1 | unrecovered | 34 -> 34 |
| `gen-0117.pdf` | 20 | 2 | unrecovered | 94 -> 94 |
| `gen-0117.pdf` | 20 | 3 | unrecovered | 22 -> 22 |
| `gen-0120.pdf` | 61 | 1 | unrecovered | 196 -> 196 |
| `gen-0120.pdf` | 61 | 2 | unrecovered | 245 -> 245 |
| `gen-0120.pdf` | 61 | 3 | unrecovered | 122 -> 122 |
| `gen-0120.pdf` | 61 | 4 | unrecovered | 56 -> 56 |
| `gen-0120.pdf` | 61 | 5 | unrecovered | 214 -> 214 |
| `gen-0120.pdf` | 61 | 6 | unrecovered | 99 -> 99 |
| `gen-0120.pdf` | 61 | 7 | unrecovered | 108 -> 108 |
| `gen-0120.pdf` | 61 | 8 | unrecovered | 111 -> 111 |
| `gen-0123.pdf` | 17 | 1 | unrecovered | 323 -> 323 |
| `gen-0123.pdf` | 17 | 2 | unrecovered | 168 -> 168 |
| `gen-0123.pdf` | 17 | 3 | unrecovered | 133 -> 133 |
| `gen-0126.pdf` | 1 | 1 | unrecovered | 201 -> 201 |
| `gen-0129.pdf` | 34 | 1 | unrecovered | 22 -> 22 |
| `gen-0129.pdf` | 34 | 2 | unrecovered | 336 -> 336 |
| `gen-0129.pdf` | 34 | 3 | unrecovered | 215 -> 215 |
| `gen-0129.pdf` | 34 | 4 | unrecovered | 57 -> 57 |
| `gen-0132.pdf` | 40 | 1 | unrecovered | 11 -> 11 |
| `gen-0132.pdf` | 40 | 2 | unrecovered | 255 -> 255 |
| `gen-0132.pdf` | 40 | 3 | unrecovered | 178 -> 178 |
| `gen-0132.pdf` | 40 | 4 | unrecovered | 195 -> 195 |
| `gen-0132.pdf` | 40 | 5 | unrecovered | 182 -> 182 |
| `gen-0135.pdf` | 38 | 1 | unrecovered | 152 -> 152 |
| `gen-0135.pdf` | 38 | 2 | unrecovered | 208 -> 208 |
| `gen-0135.pdf` | 38 | 3 | unrecovered | 181 -> 181 |
| `gen-0135.pdf` | 38 | 4 | unrecovered | 89 -> 89 |
| `gen-0135.pdf` | 38 | 5 | unrecovered | 57 -> 57 |
| `gen-0135.pdf` | 38 | 6 | unrecovered | 113 -> 113 |
| `gen-0138.pdf` | 41 | 1 | unrecovered | 278 -> 278 |
| `gen-0138.pdf` | 41 | 2 | unrecovered | 82 -> 82 |
| `gen-0138.pdf` | 41 | 3 | unrecovered | 94 -> 94 |
| `gen-0138.pdf` | 41 | 4 | unrecovered | 189 -> 189 |
| `gen-0138.pdf` | 41 | 5 | unrecovered | 66 -> 66 |
| `gen-0141.pdf` | 4 | 1 | unrecovered | 105 -> 105 |
| `gen-0144.pdf` | 42 | 1 | unrecovered | 415 -> 415 |
| `gen-0144.pdf` | 42 | 2 | unrecovered | 34 -> 34 |
| `gen-0144.pdf` | 42 | 3 | unrecovered | 42 -> 42 |
| `gen-0144.pdf` | 42 | 4 | unrecovered | 171 -> 171 |
| `gen-0144.pdf` | 42 | 5 | unrecovered | 280 -> 280 |
| `gen-0144.pdf` | 42 | 6 | unrecovered | 19 -> 19 |
| `gen-0150.pdf` | 45 | 1 | unrecovered | 34 -> 34 |
| `gen-0150.pdf` | 45 | 2 | unrecovered | 159 -> 159 |
| `gen-0150.pdf` | 45 | 3 | unrecovered | 245 -> 245 |
| `gen-0150.pdf` | 45 | 4 | unrecovered | 53 -> 53 |
| `gen-0150.pdf` | 45 | 5 | unrecovered | 38 -> 38 |
| `gen-0150.pdf` | 45 | 6 | unrecovered | 164 -> 164 |
| `gen-0153.pdf` | 19 | 1 | unrecovered | 404 -> 404 |
| `gen-0156.pdf` | 7 | 1 | unrecovered | 310 -> 310 |
| `gen-0159.pdf` | 12 | 1 | unrecovered | 261 -> 261 |
| `gen-0159.pdf` | 12 | 2 | unrecovered | 260 -> 260 |
| `gen-0162.pdf` | 43 | 1 | unrecovered | 268 -> 268 |
| `gen-0162.pdf` | 43 | 2 | unrecovered | 18 -> 18 |
| `gen-0162.pdf` | 43 | 3 | unrecovered | 169 -> 169 |
| `gen-0162.pdf` | 43 | 4 | unrecovered | 199 -> 199 |
| `gen-0162.pdf` | 43 | 5 | unrecovered | 53 -> 53 |
| `gen-0162.pdf` | 43 | 6 | unrecovered | 24 -> 24 |
| `gen-0165.pdf` | 25 | 1 | unrecovered | 78 -> 78 |
| `gen-0165.pdf` | 25 | 2 | unrecovered | 49 -> 49 |
| `gen-0171.pdf` | 19 | 1 | unrecovered | 224 -> 224 |
| `gen-0171.pdf` | 19 | 2 | unrecovered | 2 -> 2 |
| `gen-0174.pdf` | 51 | 1 | unrecovered | 64 -> 64 |
| `gen-0174.pdf` | 51 | 2 | unrecovered | 21 -> 21 |
| `gen-0174.pdf` | 51 | 3 | unrecovered | 111 -> 111 |
| `gen-0174.pdf` | 51 | 4 | unrecovered | 97 -> 97 |
| `gen-0174.pdf` | 51 | 5 | unrecovered | 166 -> 166 |
| `gen-0174.pdf` | 51 | 6 | unrecovered | 16 -> 16 |
| `gen-0177.pdf` | 15 | 1 | unrecovered | 22 -> 22 |
| `gen-0177.pdf` | 15 | 2 | unrecovered | 90 -> 90 |
| `gen-0177.pdf` | 15 | 3 | unrecovered | 158 -> 158 |
| `gen-0180.pdf` | 14 | 1 | unrecovered | 232 -> 232 |
| `gen-0180.pdf` | 14 | 2 | unrecovered | 76 -> 76 |
| `gen-0183.pdf` | 17 | 1 | unrecovered | 203 -> 203 |
| `gen-0183.pdf` | 17 | 2 | unrecovered | 114 -> 114 |
| `gen-0183.pdf` | 17 | 3 | unrecovered | 68 -> 68 |
| `gen-0186.pdf` | 39 | 1 | unrecovered | 71 -> 71 |
| `gen-0186.pdf` | 39 | 2 | unrecovered | 28 -> 28 |
| `gen-0186.pdf` | 39 | 3 | unrecovered | 318 -> 318 |
| `gen-0186.pdf` | 39 | 4 | unrecovered | 28 -> 28 |
| `gen-0186.pdf` | 39 | 5 | unrecovered | 87 -> 87 |
| `gen-0186.pdf` | 39 | 6 | unrecovered | 114 -> 114 |
| `gen-0186.pdf` | 39 | 7 | unrecovered | 42 -> 42 |
| `gen-0189.pdf` | 21 | 1 | unrecovered | 503 -> 503 |
| `gen-0189.pdf` | 21 | 2 | unrecovered | 22 -> 22 |
| `gen-0189.pdf` | 21 | 3 | unrecovered | 84 -> 84 |
| `gen-0189.pdf` | 21 | 4 | unrecovered | 323 -> 323 |
| `gen-0192.pdf` | 27 | 1 | unrecovered | 166 -> 166 |
| `gen-0192.pdf` | 27 | 2 | unrecovered | 282 -> 282 |
| `gen-0192.pdf` | 27 | 3 | unrecovered | 181 -> 181 |
| `gen-0195.pdf` | 13 | 1 | unrecovered | 253 -> 253 |
| `gen-0195.pdf` | 13 | 2 | unrecovered | 79 -> 79 |
| `gen-0195.pdf` | 13 | 3 | unrecovered | 53 -> 53 |
| `gen-0198.pdf` | 16 | 1 | unrecovered | 32 -> 32 |
| `gen-0198.pdf` | 16 | 2 | unrecovered | 277 -> 277 |
| `gen-0201.pdf` | 25 | 1 | unrecovered | 22 -> 22 |
| `gen-0201.pdf` | 25 | 2 | unrecovered | 71 -> 71 |
| `gen-0201.pdf` | 25 | 3 | unrecovered | 100 -> 100 |
| `gen-0201.pdf` | 25 | 4 | unrecovered | 137 -> 137 |
| `gen-0204.pdf` | 20 | 1 | unrecovered | 161 -> 161 |
| `gen-0204.pdf` | 20 | 2 | unrecovered | 162 -> 162 |
| `gen-0204.pdf` | 20 | 3 | unrecovered | 70 -> 70 |
| `gen-0207.pdf` | 30 | 1 | unrecovered | 186 -> 186 |
| `gen-0207.pdf` | 30 | 2 | unrecovered | 102 -> 102 |
| `gen-0207.pdf` | 30 | 3 | unrecovered | 154 -> 154 |
| `gen-0207.pdf` | 30 | 4 | unrecovered | 3 -> 3 |
| `gen-0210.pdf` | 21 | 1 | unrecovered | 252 -> 252 |
| `gen-0210.pdf` | 21 | 2 | unrecovered | 67 -> 67 |
| `gen-0210.pdf` | 21 | 3 | unrecovered | 22 -> 22 |
| `gen-0213.pdf` | 31 | 1 | unrecovered | 123 -> 123 |
| `gen-0213.pdf` | 31 | 2 | unrecovered | 23 -> 23 |
| `gen-0213.pdf` | 31 | 3 | unrecovered | 186 -> 186 |
| `gen-0213.pdf` | 31 | 5 | unrecovered | 235 -> 235 |
| `gen-0216.pdf` | 25 | 1 | unrecovered | 22 -> 22 |
| `gen-0216.pdf` | 25 | 2 | unrecovered | 94 -> 94 |
| `gen-0216.pdf` | 25 | 3 | unrecovered | 4 -> 4 |
| `gen-0216.pdf` | 25 | 4 | unrecovered | 120 -> 120 |
| `gen-0219.pdf` | 68 | 1 | unrecovered | 3 -> 3 |
| `gen-0219.pdf` | 68 | 2 | unrecovered | 110 -> 110 |
| `gen-0219.pdf` | 68 | 3 | unrecovered | 116 -> 116 |
| `gen-0219.pdf` | 68 | 4 | unrecovered | 184 -> 184 |
| `gen-0219.pdf` | 68 | 5 | unrecovered | 44 -> 44 |
| `gen-0219.pdf` | 68 | 6 | unrecovered | 25 -> 25 |
| `gen-0219.pdf` | 68 | 8 | unrecovered | 413 -> 413 |
| `gen-0219.pdf` | 68 | 9 | unrecovered | 22 -> 22 |
| `gen-0219.pdf` | 68 | 10 | unrecovered | 22 -> 22 |
| `gen-0219.pdf` | 68 | 11 | unrecovered | 41 -> 41 |
| `gen-0222.pdf` | 25 | 1 | unrecovered | 132 -> 132 |
| `gen-0222.pdf` | 25 | 2 | unrecovered | 48 -> 48 |
| `gen-0222.pdf` | 25 | 3 | unrecovered | 243 -> 243 |
| `gen-0225.pdf` | 33 | 1 | unrecovered | 42 -> 42 |
| `gen-0225.pdf` | 33 | 2 | unrecovered | 366 -> 366 |
| `gen-0225.pdf` | 33 | 3 | unrecovered | 121 -> 121 |
| `gen-0225.pdf` | 33 | 5 | unrecovered | 521 -> 521 |
| `gen-0225.pdf` | 33 | 6 | unrecovered | 131 -> 131 |
| `gen-0228.pdf` | 24 | 1 | unrecovered | 41 -> 41 |
| `gen-0228.pdf` | 24 | 2 | unrecovered | 13 -> 13 |
| `gen-0228.pdf` | 24 | 3 | unrecovered | 447 -> 447 |
| `gen-0228.pdf` | 24 | 4 | unrecovered | 109 -> 109 |
| `gen-0231.pdf` | 31 | 1 | unrecovered | 50 -> 50 |
| `gen-0231.pdf` | 31 | 2 | unrecovered | 109 -> 109 |
| `gen-0231.pdf` | 31 | 3 | unrecovered | 9 -> 9 |
| `gen-0231.pdf` | 31 | 4 | unrecovered | 19 -> 19 |
| `gen-0231.pdf` | 31 | 5 | unrecovered | 68 -> 68 |
| `gen-0234.pdf` | 23 | 1 | unrecovered | 26 -> 26 |
| `gen-0234.pdf` | 23 | 2 | unrecovered | 118 -> 118 |
| `gen-0234.pdf` | 23 | 3 | unrecovered | 144 -> 144 |
| `gen-0237.pdf` | 38 | 1 | unrecovered | 106 -> 106 |
| `gen-0237.pdf` | 38 | 2 | unrecovered | 350 -> 350 |
| `gen-0237.pdf` | 38 | 3 | unrecovered | 114 -> 114 |
| `gen-0237.pdf` | 38 | 4 | unrecovered | 98 -> 98 |
| `gen-0237.pdf` | 38 | 5 | unrecovered | 67 -> 67 |
| `gen-0237.pdf` | 38 | 6 | unrecovered | 37 -> 37 |
| `gen-0240.pdf` | 32 | 1 | unrecovered | 184 -> 184 |
| `gen-0240.pdf` | 32 | 2 | unrecovered | 171 -> 171 |
| `gen-0240.pdf` | 32 | 3 | unrecovered | 83 -> 83 |
| `gen-0240.pdf` | 32 | 4 | unrecovered | 96 -> 96 |
| `gen-0240.pdf` | 32 | 5 | unrecovered | 35 -> 35 |
| `gen-0243.pdf` | 36 | 1 | unrecovered | 112 -> 112 |
| `gen-0243.pdf` | 36 | 2 | unrecovered | 154 -> 154 |
| `gen-0243.pdf` | 36 | 3 | unrecovered | 221 -> 221 |
| `gen-0243.pdf` | 36 | 4 | unrecovered | 189 -> 189 |
| `gen-0243.pdf` | 36 | 5 | unrecovered | 35 -> 35 |
| `gen-0243.pdf` | 36 | 6 | unrecovered | 274 -> 274 |
| `gen-0246.pdf` | 31 | 1 | unrecovered | 22 -> 22 |
| `gen-0246.pdf` | 31 | 2 | unrecovered | 124 -> 124 |
| `gen-0246.pdf` | 31 | 3 | unrecovered | 125 -> 125 |
| `gen-0246.pdf` | 31 | 4 | unrecovered | 110 -> 110 |
| `gen-0249.pdf` | 8 | 1 | unrecovered | 77 -> 77 |
| `gen-0252.pdf` | 89 | 1 | unrecovered | 314 -> 314 |
| `gen-0252.pdf` | 89 | 2 | unrecovered | 207 -> 207 |
| `gen-0252.pdf` | 89 | 3 | unrecovered | 299 -> 299 |
| `gen-0252.pdf` | 89 | 4 | unrecovered | 51 -> 51 |
| `gen-0252.pdf` | 89 | 5 | unrecovered | 80 -> 80 |
| `gen-0252.pdf` | 89 | 6 | unrecovered | 378 -> 378 |
| `gen-0252.pdf` | 89 | 7 | unrecovered | 136 -> 136 |
| `gen-0252.pdf` | 89 | 8 | unrecovered | 151 -> 151 |
| `gen-0252.pdf` | 89 | 9 | unrecovered | 22 -> 22 |
| `gen-0252.pdf` | 89 | 10 | unrecovered | 117 -> 117 |
| `gen-0252.pdf` | 89 | 11 | unrecovered | 362 -> 362 |
| `gen-0252.pdf` | 89 | 12 | unrecovered | 178 -> 178 |
| `gen-0255.pdf` | 53 | 1 | unrecovered | 51 -> 51 |
| `gen-0255.pdf` | 53 | 2 | unrecovered | 34 -> 34 |
| `gen-0255.pdf` | 53 | 3 | unrecovered | 136 -> 136 |
| `gen-0255.pdf` | 53 | 5 | unrecovered | 147 -> 147 |
| `gen-0255.pdf` | 53 | 6 | unrecovered | 39 -> 39 |
| `gen-0261.pdf` | 4 | 1 | unrecovered | 60 -> 60 |
| `gen-0264.pdf` | 49 | 1 | unrecovered | 199 -> 199 |
| `gen-0264.pdf` | 49 | 2 | unrecovered | 56 -> 56 |
| `gen-0264.pdf` | 49 | 3 | unrecovered | 114 -> 114 |
| `gen-0264.pdf` | 49 | 4 | unrecovered | 26 -> 26 |
| `gen-0264.pdf` | 49 | 5 | unrecovered | 43 -> 43 |
| `gen-0264.pdf` | 49 | 6 | unrecovered | 131 -> 131 |
| `gen-0267.pdf` | 6 | 1 | unrecovered | 283 -> 283 |
| `gen-0270.pdf` | 53 | 1 | unrecovered | 211 -> 211 |
| `gen-0270.pdf` | 53 | 2 | unrecovered | 81 -> 81 |
| `gen-0270.pdf` | 53 | 3 | unrecovered | 151 -> 151 |
| `gen-0270.pdf` | 53 | 4 | unrecovered | 180 -> 180 |
| `gen-0270.pdf` | 53 | 5 | unrecovered | 189 -> 189 |
| `gen-0273.pdf` | 9 | 1 | unrecovered | 153 -> 153 |
| `gen-0273.pdf` | 9 | 2 | unrecovered | 166 -> 166 |
| `gen-0276.pdf` | 6 | 1 | unrecovered | 139 -> 139 |
| `gen-0279.pdf` | 7 | 1 | unrecovered | 104 -> 104 |
| `gen-0279.pdf` | 7 | 2 | unrecovered | 134 -> 134 |
| `gen-0282.pdf` | 51 | 1 | unrecovered | 60 -> 60 |
| `gen-0282.pdf` | 51 | 2 | unrecovered | 217 -> 217 |
| `gen-0282.pdf` | 51 | 3 | unrecovered | 70 -> 70 |
| `gen-0282.pdf` | 51 | 4 | unrecovered | 163 -> 163 |
| `gen-0282.pdf` | 51 | 5 | unrecovered | 227 -> 227 |
| `gen-0282.pdf` | 51 | 6 | unrecovered | 81 -> 81 |
| `gen-0285.pdf` | 26 | 1 | unrecovered | 54 -> 54 |
| `gen-0285.pdf` | 26 | 2 | unrecovered | 151 -> 151 |
| `gen-0285.pdf` | 26 | 3 | unrecovered | 126 -> 126 |
| `gen-0288.pdf` | 18 | 1 | unrecovered | 37 -> 37 |
| `gen-0288.pdf` | 18 | 2 | unrecovered | 38 -> 38 |
| `gen-0291.pdf` | 14 | 1 | unrecovered | 138 -> 138 |
| `gen-0291.pdf` | 14 | 2 | unrecovered | 81 -> 81 |
| `gen-0294.pdf` | 1 | 1 | unrecovered | 161 -> 161 |
| `gen-0297.pdf` | 13 | 1 | unrecovered | 124 -> 124 |
| `gen-0297.pdf` | 13 | 2 | unrecovered | 200 -> 200 |
| `gen-0297.pdf` | 13 | 3 | unrecovered | 153 -> 153 |

## Mutation could not be produced (harness limitation, not a repair result)

None.
