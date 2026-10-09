/**
 * Unified PDF redaction module - auto-redacts words, patterns, PII, entities, and regions from PDFs using the Object Fission algorithm.
 *
 * <p>Provides word/regex redaction, PCRE2 JIT PII patterns, FlashText NER, glyph-level redaction (HarfBuzz + ICU), font normalization, XMP//Info metadata redaction, and convert-to-image for maximum security.
 *
 * <pre>{@code
 * RedactOptions opts = RedactOptions.builder()
 *     .addWord("Confidential")
 *     .enableAllPiiPatterns()
 *     .normalizeFonts(true)
 *     .redactMetadata(true)
 *     .boxColor(0xFF000000)
 *     .build();
 *
 * RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
 * result.document().save(Path.of("redacted.pdf"));
 * result.document().close();
 * }</pre>
 *
 * @see stirling.software.jpdfium.redact.PdfRedactor
 * @see stirling.software.jpdfium.redact.RedactOptions
 * @see stirling.software.jpdfium.redact.RedactionSession
 */
package stirling.software.jpdfium.redact;
