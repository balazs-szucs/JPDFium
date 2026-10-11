package stirling.software.jpdfium.fonts.intel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

/**
 * Shared plumbing for the font-intelligence tests that build a small PDF, install
 * or strip a declared {@code /ToUnicode}, and read it back.
 *
 * <p>Only the boilerplate is shared. Fixture <em>content</em> (which pages, which
 * references, which CMap) and every assertion stay in the test that needs them, so
 * a failure still names the specific detector that failed. This is deliberately a
 * handful of explicit helpers, not a fixture framework.
 */
final class FontTestFixtures {

    static final String HEADER =
            "/CIDInit /ProcSet findresource begin\n"
            + "12 dict begin\n"
            + "begincmap\n"
            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
            + "/CMapName /test-UCS def\n"
            + "/CMapType 2 def\n"
            + "1 begincodespacerange\n"
            + "<00> <FF>\n"
            + "endcodespacerange\n";

    static final String FOOTER =
            "endcmap\n"
            + "CMapName currentdict /CMap defineresource pop\n"
            + "end\n"
            + "end\n";

    /** Declares only the code for 'A'; the code for 'B' (0x42) is missing. */
    static final String PARTIAL = "1 beginbfchar\n<41> <0041>\nendbfchar\n";

    private FontTestFixtures() {}

    /** Load the pinned font as a WinAnsi TrueType font on {@code doc}. */
    static PDTrueTypeFont loadFont(PDDocument doc, Path font) throws Exception {
        try (InputStream in = Files.newInputStream(font)) {
            return PDTrueTypeFont.load(doc, in, WinAnsiEncoding.INSTANCE);
        }
    }

    /** Serialize {@code doc} to bytes. */
    static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /** Replace the page's content stream with {@code content}. */
    static void setContent(PDDocument doc, PDPage page, String content) throws Exception {
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createOutputStream()) {
            os.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        page.getCOSObject().setItem(COSName.CONTENTS, stream);
    }

    /** A Form XObject with the given content stream and a 300x300 /BBox. */
    static COSStream formStream(PDDocument doc, byte[] content) throws Exception {
        COSStream form = doc.getDocument().createCOSStream();
        form.setItem(COSName.TYPE, COSName.getPDFName("XObject"));
        form.setItem(COSName.SUBTYPE, COSName.getPDFName("Form"));
        form.setItem(COSName.BBOX, new PDRectangle(300, 300).getCOSArray());
        try (OutputStream os = form.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(content);
        }
        return form;
    }

    /** An empty /Resources dictionary (a form that inherits the caller's font). */
    static COSDictionary emptyResources() {
        return new COSDictionary();
    }

    /** Install {@code cmap} (wrapped in the shared CMap header/footer) on one font. */
    static void installToUnicode(PDDocument doc, COSDictionary fontDict, String cmap)
            throws Exception {
        fontDict.removeItem(COSName.TO_UNICODE);
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createOutputStream()) {
            os.write((HEADER + cmap + FOOTER).getBytes(StandardCharsets.ISO_8859_1));
        }
        fontDict.setItem(COSName.TO_UNICODE, stream);
    }

    /** Install {@code cmap} on every font dictionary of every page. */
    static void installToUnicodeOnAllFonts(PDDocument doc, String cmap) throws Exception {
        for (PDPage page : doc.getPages()) {
            PDResources res = page.getResources();
            if (res == null) {
                continue;
            }
            COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
            if (fonts == null) {
                continue;
            }
            for (COSName key : new ArrayList<>(fonts.keySet())) {
                COSBase fd = fonts.getDictionaryObject(key);
                if (fd instanceof COSDictionary dict) {
                    dict.removeItem(COSName.TO_UNICODE);
                    COSStream stream = doc.getDocument().createCOSStream();
                    try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                        os.write((HEADER + cmap + FOOTER).getBytes(StandardCharsets.ISO_8859_1));
                    }
                    dict.setItem(COSName.TO_UNICODE, stream);
                }
            }
        }
    }

    /** The first font's declared {@code /ToUnicode} stream text on a page, or null. */
    static String toUnicodeText(byte[] pdf, int pageIndex) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageIndex);
            PDResources res = page.getResources();
            if (res == null) {
                return null;
            }
            COSDictionary fonts = res.getCOSObject().getCOSDictionary(COSName.FONT);
            if (fonts == null) {
                return null;
            }
            for (COSName key : new ArrayList<>(fonts.keySet())) {
                COSBase fd = fonts.getDictionaryObject(key);
                if (fd instanceof COSDictionary dict
                        && dict.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream) {
                    try (InputStream in = stream.createInputStream()) {
                        return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new AssertionError("could not read the declared /ToUnicode map", e);
        }
    }

    /** Decoded bytes of the named page's form XObject content stream. */
    static byte[] formContent(byte[] pdf, int pageIndex, String formName) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            COSDictionary xobjects = doc.getPage(pageIndex).getResources().getCOSObject()
                    .getCOSDictionary(COSName.XOBJECT);
            COSBase form = xobjects.getDictionaryObject(COSName.getPDFName(formName));
            if (form instanceof COSStream stream) {
                try (InputStream in = stream.createInputStream()) {
                    return in.readAllBytes();
                }
            }
            return new byte[0];
        } catch (Exception e) {
            throw new AssertionError("could not read the form XObject content", e);
        }
    }
}
