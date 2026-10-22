package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfEncryption;
import stirling.software.jpdfium.doc.PdfSecurity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Sanitizes documents and applies AES-256 password protection. */
public final class S09_Security {

    private static final String USER_PASSWORD = "user";
    private static final String OWNER_PASSWORD = "owner";

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("security");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/minimal.pdf")) {
            SampleBase.header("S09_Security", input);
            String stem = SampleBase.stem(input);

            Path sanitized = out.resolve(stem + "-sanitized.pdf");
            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfSecurity.Result result = PdfSecurity.builder().all().build().execute(doc);
                doc.save(sanitized);
                System.out.printf("  %s%n", result.summary().replace("\n", "\n  "));
            }
            outputs.add(sanitized);

            if (!PdfSecurity.isSupported()) {
                System.out.println("  encryption backend unavailable - skipping");
                continue;
            }

            Path encrypted = out.resolve(stem + "-encrypted.pdf");
            PdfEncryption.encrypt(input, encrypted, USER_PASSWORD, OWNER_PASSWORD);
            outputs.add(encrypted);

            try (PdfDocument doc = PdfDocument.open(encrypted, USER_PASSWORD)) {
                System.out.printf("  encrypted: %d pages, %d bytes, encrypted=%b%n",
                        doc.pageCount(), Files.size(encrypted),
                        PdfEncryption.isEncrypted(doc.rawHandle()));
            }

            Path decrypted = out.resolve(stem + "-decrypted.pdf");
            PdfEncryption.decrypt(encrypted, decrypted, USER_PASSWORD);
            outputs.add(decrypted);
            System.out.printf("  decrypted: %d bytes%n", Files.size(decrypted));
        }

        SampleBase.done("S09_Security", outputs);
    }
}
