# Samples

The repository contains ten runnable programs that cover the core API and the
features most applications need. They live in
[`jpdfium/src/test/java/stirling/software/jpdfium/samples`](../jpdfium/src/test/java/stirling/software/jpdfium/samples).

Run every sample:

```bash
./gradlew runAllSamples
```

Run one sample by number:

```bash
./gradlew runSample -Psample=05
```

The Gradle tasks start the JVM with `--enable-native-access=ALL-UNNAMED` and
write output to `samples-output/<feature>/` in the repository root.

| No. | Sample | Demonstrates |
|---|---|---|
| 01 | `S01_Document` | Open a document, read metadata and page sizes, save, and reopen from bytes |
| 02 | `S02_Render` | Render pages to PNG at a configurable DPI |
| 03 | `S03_Text` | Extract structured text and search across pages |
| 04 | `S04_Images` | Convert pages to images, extract embedded images, and build a PDF from an image |
| 05 | `S05_Redact` | Redact words, PII patterns, and metadata with `PdfRedactor` |
| 06 | `S06_Forms` | Read AcroForm fields and fill them from a value map |
| 07 | `S07_MergeSplit` | Merge documents, split into single pages, and extract a page range |
| 08 | `S08_Compress` | Compress with the lossless and web presets |
| 09 | `S09_Security` | Sanitize a document and apply AES-256 password protection |
| 10 | `S10_Pipeline` | Search, redact, flatten, render, and save in one pass |

Each sample uses a bundled test fixture by default. When running a sample
directly, pass one or more PDF paths to override the fixture. The suite is also
executed by `SamplesSuiteTest` as part of `:jpdfium:integrationTest`.
