package stirling.software.jpdfium.model;
import stirling.software.jpdfium.PdfDocument;

/**
 * Controls what elements are flattened when calling {@link PdfDocument#flatten}.
 *
 * <p>All modes use native PDFium via FFM - no PDFBox involved.
 */
public enum FlattenMode {

    /**
     * Flatten annotations and form fields into static page content.
     *
     * <p>Interactive elements (form values, redaction marks, sticky notes) are baked into the content stream and no longer editable; text remains selectable. Uses native PDFium {@code FPDFPage_Flatten} via {@code jpdfium_page_flatten}.
     */
    ANNOTATIONS,

    /**
     * Convert each page to an image-based page (full rasterization).
     *
     * <p>The entire page - text, vector graphics, annotations, form fields - is rendered at the specified DPI and replaced with a single raster image, so no text can be selected or extracted. Uses native PDFium via {@code jpdfium_page_to_image} and requires a DPI parameter (150 = good quality, 300 = high quality).
     */
    FULL
}
