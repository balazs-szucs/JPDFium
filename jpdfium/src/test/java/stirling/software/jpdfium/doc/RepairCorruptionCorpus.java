package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Paired PDF-corruption corpus with declared outcomes.
 *
 * <p>Every fixture is either a controlled mutation of one known-valid base PDF,
 * a compound of two mutations, or a deliberate binary false positive. Each
 * carries the outcome the repair pipeline is expected to report, so the corpus
 * is a contract: a change in repair behaviour fails the test instead of quietly
 * changing a number.
 *
 * <p>Distinct from {@link RepairCorpus}, which only generates damaged bytes for
 * a "does not crash" sweep. This corpus declares what the correct answer is.
 */
public final class RepairCorruptionCorpus {

    /** The outcome class a fixture is expected to fall into. */
    public enum Outcome {
        /** Recoverable with no meaningful loss. */
        REPAIRABLE,
        /** Recoverable but with possible content loss. */
        PARTIAL,
        /** Repair is refused and the original is handed back untouched. */
        ROLLBACK,
        /** The construct is outside the supported repair set. */
        UNSUPPORTED,
        /** The file is encrypted; repair needs a password first. */
        REQUIRES_PASSWORD,
        /** No strategy can produce usable output. */
        UNRECOVERABLE,
        /** The input exceeds a resource bound and is refused rather than attempted. */
        RESOURCE_LIMIT
    }

    /**
     * One corpus entry: the damaged bytes plus the outcome they must exhibit.
     *
     * @param id stable identifier used in test names and failure messages
     * @param outcome declared outcome class
     * @param expectedPages pages the repaired output must have, or 0 when the
     *     fixture must not produce output
     * @param note why this outcome is expected
     * @param bytes the damaged PDF (or deliberate non-PDF) bytes
     */
    public record Fixture(
            String id, Outcome outcome, int expectedPages, String note, byte[] bytes) {}

    private RepairCorruptionCorpus() {}

    /** The paired corpus: a valid control, single mutations, compounds, false positives. */
    public static List<Fixture> all() throws IOException {
        byte[] valid = RepairCorpus.validMultiPage();
        List<Fixture> out = new ArrayList<>();

        out.add(new Fixture("control-valid", Outcome.REPAIRABLE, 3,
                "undamaged control: must stay usable with all pages", valid.clone()));

        // --- single controlled mutations -------------------------------------------------
        out.add(new Fixture("xref-corrupted", Outcome.REPAIRABLE, 3,
                "xref body overwritten; qpdf rebuilds it", RepairCorpus.corruptXref(valid)));
        out.add(new Fixture("xref-zeroed", Outcome.REPAIRABLE, 3,
                "xref entries zeroed; qpdf rebuilds it", RepairCorpus.zeroXref(valid)));
        out.add(new Fixture("startxref-wrong", Outcome.REPAIRABLE, 3,
                "startxref offset out of range; recovered by scan",
                RepairCorpus.wrongStartxref(valid)));
        out.add(new Fixture("stream-length-wrong", Outcome.REPAIRABLE, 3,
                "declared /Length inconsistent; qpdf heals it",
                RepairCorpus.wrongStreamLength(valid)));
        out.add(new Fixture("endobj-missing", Outcome.REPAIRABLE, 3,
                "missing endobj keywords; qpdf tolerates", RepairCorpus.stripEndobj(valid)));
        out.add(new Fixture("missing-eof", Outcome.REPAIRABLE, 3,
                "%%EOF removed; the trailing marker is not required to open",
                RepairCorpus.stripEof(valid)));
        out.add(new Fixture("header-garbage", Outcome.REPAIRABLE, 3,
                "junk before the %PDF header is skipped", RepairCorpus.garbageHeader(valid)));
        out.add(new Fixture("header-padded", Outcome.REPAIRABLE, 3,
                "blank lines before the header push every xref offset; rebuild by scan",
                RepairCorpus.padHeader(valid)));
        out.add(new Fixture("comments-injected", Outcome.REPAIRABLE, 3,
                "comment lines after the header are ignored and offsets rebuilt",
                RepairCorpus.injectComments(valid)));
        out.add(new Fixture("trailing-garbage-after-eof", Outcome.REPAIRABLE, 3,
                "junk after the final %%EOF is ignored", trailingGarbage(valid)));
        out.add(new Fixture("doubled-eof", Outcome.REPAIRABLE, 3,
                "a second %%EOF marker does not confuse the reader", doubledEof(valid)));
        out.add(new Fixture("broken-page-parent", Outcome.REPAIRABLE, 1,
                "page /Parent points at the catalog; traversal still finds the page but "
                        + "the back-pointer must be rewritten",
                brokenPageParent()));

        out.add(new Fixture("header-only", Outcome.UNRECOVERABLE, 0,
                "%PDF header with no body objects or trailer", RepairCorpus.headerOnly()));

        out.add(new Fixture("page-tree-cycle", Outcome.REPAIRABLE, 1,
                "the /Pages node is its own kid; repair drops the cycle, keeping the page",
                pageTreeCycle()));

        out.add(new Fixture("truncated-75pct", Outcome.UNRECOVERABLE, 0,
                "tail (xref/trailer) gone; no page tree survives",
                RepairCorpus.truncate(valid, 0.75)));
        out.add(new Fixture("truncated-50pct", Outcome.UNRECOVERABLE, 0,
                "half the file gone; unrecoverable", RepairCorpus.truncate(valid, 0.50)));
        out.add(new Fixture("truncated-25pct", Outcome.UNRECOVERABLE, 0,
                "only the header and first objects survive; no page tree",
                RepairCorpus.truncate(valid, 0.25)));

        // --- compound mutations ----------------------------------------------------------
        out.add(new Fixture("compound-xref+noeof", Outcome.REPAIRABLE, 3,
                "corrupt xref and missing EOF together",
                RepairCorpus.stripEof(RepairCorpus.corruptXref(valid))));
        out.add(new Fixture("compound-startxref+length", Outcome.REPAIRABLE, 3,
                "wrong startxref and wrong /Length together",
                RepairCorpus.wrongStreamLength(RepairCorpus.wrongStartxref(valid))));
        out.add(new Fixture("compound-garbage+xref", Outcome.REPAIRABLE, 3,
                "leading garbage and corrupt xref",
                RepairCorpus.corruptXref(RepairCorpus.garbageHeader(valid))));

        // --- binary false positives: bytes that look structural but are not PDFs ----------
        out.add(new Fixture("false-random-bytes", Outcome.UNRECOVERABLE, 0,
                "deterministic random bytes: not a PDF", randomBytes(1024)));
        out.add(new Fixture("false-plaintext-keywords", Outcome.UNRECOVERABLE, 0,
                "text mentioning obj/xref/trailer/endstream but with no PDF structure",
                "This file mentions obj xref trailer startxref %%EOF endstream endobj\n"
                        .repeat(8).getBytes(StandardCharsets.ISO_8859_1)));
        out.add(new Fixture("false-png-header", Outcome.UNRECOVERABLE, 0,
                "PNG magic and IHDR then zeros: not a PDF", pngLike()));
        out.add(new Fixture("false-zip", Outcome.UNRECOVERABLE, 0,
                "ZIP local header then zeros: not a PDF", zipLike()));
        out.add(new Fixture("false-json", Outcome.UNRECOVERABLE, 0,
                "JSON document mentioning pages/xref: not a PDF",
                "{\"pages\":3,\"xref\":\"none\",\"trailer\":true}\n"
                        .repeat(8).getBytes(StandardCharsets.ISO_8859_1)));
        out.add(new Fixture("false-xml", Outcome.UNRECOVERABLE, 0,
                "XML document mentioning xref/trailer/endstream: not a PDF",
                ("<?xml version=\"1.0\"?><pdf xref=\"0 0\" trailer=\"true\" "
                        + "endstream=\"false\"/>\n").repeat(8)
                        .getBytes(StandardCharsets.ISO_8859_1)));

        // --- encrypted: repair must not silently pretend to succeed -----------------------
        out.add(new Fixture("encrypted-user-password", Outcome.REQUIRES_PASSWORD, 0,
                "encrypted with a user password; repair cannot proceed without it",
                encrypted(valid)));

        return out;
    }

    /** Appends trailing junk after the final %%EOF marker. */
    static byte[] trailingGarbage(byte[] pdf) {
        byte[] junk = "\nJUNK JUNK JUNK trailing bytes ignored by readers\n"
                .repeat(4).getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = Arrays.copyOf(pdf, pdf.length + junk.length);
        System.arraycopy(junk, 0, out, pdf.length, junk.length);
        return out;
    }

    /** Appends a duplicate %%EOF marker at the end of the file. */
    static byte[] doubledEof(byte[] pdf) {
        byte[] extra = "%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = Arrays.copyOf(pdf, pdf.length + extra.length);
        System.arraycopy(extra, 0, out, pdf.length, extra.length);
        return out;
    }

    /**
     * A valid one-page PDF whose page {@code /Parent} points at the catalog
     * instead of the {@code /Pages} node. The page tree is still traversable via
     * {@code /Kids}, so the document loads, but the back-pointer is broken until
     * the repair rewrites it.
     */
    public static byte[] brokenPageParent() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            // Deliberately wrong: the page parent should be the /Pages node.
            page.getCOSObject().setItem(COSName.PARENT,
                    doc.getDocumentCatalog().getCOSObject());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /** Deterministic pseudo-random bytes (no dependency on a seeded RNG). */
    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 37 + 11);
        }
        return b;
    }

    /** A PNG magic number and IHDR tag followed by zeros. */
    static byte[] pngLike() {
        byte[] b = new byte[256];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(magic, 0, b, 0, magic.length);
        byte[] ihdr = {'I', 'H', 'D', 'R'};
        System.arraycopy(ihdr, 0, b, 12, 4);
        return b;
    }

    /** A ZIP local-file header followed by zeros. */
    static byte[] zipLike() {
        byte[] b = new byte[256];
        byte[] magic = {'P', 'K', 3, 4};
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }

    /** A PDF encrypted with a user password, so repair cannot proceed without it. */
    static byte[] encrypted(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-secret", "user-secret", ap);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * A valid one-page PDF grown to two pages by an incremental update, so the
     * first revision has one page and the effective revision has two. A
     * revision-unaware repair would see only the superseded page count.
     */
    public static byte[] incrementalAddedPage() throws IOException {
        Path tmp = Files.createTempFile("jpdfium-corpus-incremental-", ".pdf");
        try {
            Files.write(tmp, onePage());
            try (PDDocument doc = Loader.loadPDF(tmp.toFile())) {
                doc.addPage(new PDPage(PDRectangle.LETTER));
                try (OutputStream os = Files.newOutputStream(tmp,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    doc.saveIncremental(os);
                }
            }
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A valid PDF 1.7 document, written so that PDFBox emits object streams:
     * the native inspector must therefore find real {@code /ObjStm} containers.
     */
    public static byte[] objectStreamPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.setVersion(1.7f);
            for (int i = 1; i <= 2; i++) {
                doc.addPage(new PDPage(PDRectangle.LETTER));
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /** A one-page PDF whose page tree contains itself as a kid (a cycle). */
    public static byte[] pageTreeCycle() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            COSDictionary pages = doc.getDocumentCatalog().getPages().getCOSObject();
            COSArray kids = (COSArray) pages.getDictionaryObject(COSName.KIDS);
            kids.add(pages);  // the /Pages node is now its own kid: a cycle
            pages.setInt(COSName.COUNT, 2);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * A page tree nested {@code depth} levels deep (a single page at the bottom),
     * used to exceed the repair pass's tree-depth bound so the analysis is
     * truncated. Every /Parent link and /Count is correct, so the only defect is
     * the nesting depth.
     */
    public static byte[] deepPageTree(int depth) {
        int objectCount = depth + 2; // catalog + depth /Pages nodes + one page
        List<String> bodies = new ArrayList<>();
        bodies.add("<< /Type /Catalog /Pages 2 0 R >>");
        for (int i = 0; i < depth; i++) {
            int self = 2 + i;
            StringBuilder body = new StringBuilder("<< /Type /Pages ");
            if (self > 2) {
                body.append("/Parent ").append(self - 1).append(" 0 R ");
            }
            body.append("/Kids [").append(self + 1).append(" 0 R] /Count 1 >>");
            bodies.add(body.toString());
        }
        bodies.add("<< /Type /Page /Parent " + (objectCount - 1)
                + " 0 R /MediaBox [0 0 612 792] >>");

        StringBuilder out = new StringBuilder("%PDF-1.7\n");
        long[] offsets = new long[objectCount + 1];
        for (int n = 1; n <= objectCount; n++) {
            offsets[n] = out.length();
            out.append(n).append(" 0 obj\n").append(bodies.get(n - 1)).append("\nendobj\n");
        }
        long xrefStart = out.length();
        out.append("xref\n0 ").append(objectCount + 1).append('\n')
                .append("0000000000 65535 f \n");
        for (int n = 1; n <= objectCount; n++) {
            out.append(String.format("%010d 00000 n \n", offsets[n]));
        }
        out.append("trailer\n<< /Size ").append(objectCount + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xrefStart).append("\n%%EOF\n");
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] onePage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
