package stirling.software.jpdfium.qualification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import stirling.software.jpdfium.corpus.PathologicalPdfFactory;

/**
 * Writes the procedural adversarial (pathological) specimens to a directory so
 * the qualification driver can treat them as frozen inputs. This makes no claim
 * about any repair: it only materialises the pinned, generated fixtures. The
 * {@code expectedOpenable} flag is the fixture author's expectation and is kept
 * separate from any repair expectation.
 */
public final class QualificationFixtures {

    private QualificationFixtures() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: QualificationFixtures <outDir>");
            System.exit(2);
        }
        Path out = Paths.get(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(out);

        Map<String, PathologicalPdfFactory.Specimen> specimens =
                PathologicalPdfFactory.generateAll();

        StringBuilder manifest = new StringBuilder();
        manifest.append("id\tcategory\texpectedOpenable\tpath\n");
        for (Map.Entry<String, PathologicalPdfFactory.Specimen> entry : specimens.entrySet()) {
            PathologicalPdfFactory.Specimen s = entry.getValue();
            String id = "pathological-" + s.name();
            Path p = out.resolve(id + ".pdf");
            Files.write(p, s.bytes());
            manifest.append(id).append('\t')
                    .append(s.category()).append('\t')
                    .append(s.expectedOpenable()).append('\t')
                    .append(p).append('\n');
        }
        Files.writeString(out.resolve("pathological-manifest.tsv"), manifest.toString());
        System.out.println("wrote " + specimens.size() + " specimens to " + out);
    }
}
