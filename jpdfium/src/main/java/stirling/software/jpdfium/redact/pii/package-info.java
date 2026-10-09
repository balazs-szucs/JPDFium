/**
 * PII detection engines - PCRE2 JIT patterns, glyph-level precision, XMP metadata redaction, and entity recognition.
 *
 * <p>Used internally by {@link PdfRedactor} when PII-related options are enabled in {@link RedactOptions}: {@link PiiCategory}, {@link PatternEngine}, {@link GlyphRedactor}, {@link XmpRedactor}, and {@link EntityRedactor}.
 */
package stirling.software.jpdfium.redact.pii;

import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
