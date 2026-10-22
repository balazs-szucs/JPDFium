package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.FillResult;
import stirling.software.jpdfium.doc.FormField;
import stirling.software.jpdfium.doc.PdfFormFiller;
import stirling.software.jpdfium.doc.PdfFormReader;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads AcroForm fields and fills them from a value map. */
public final class S06_Forms {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("forms");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/all_form_fields.pdf")) {
            SampleBase.header("S06_Forms", input);

            try (PdfDocument doc = PdfDocument.open(input)) {
                List<FormField> fields = readFields(doc);
                System.out.printf("  %d field(s)%n", fields.size());
                for (FormField field : fields) {
                    System.out.printf("    [%s] %s = \"%s\"%n",
                            field.type(), field.name(), field.value());
                }

                Map<String, String> values = valuesFor(fields);
                if (!values.isEmpty()) {
                    FillResult result = PdfFormFiller.fill(doc).fromMap(values).apply();
                    System.out.printf("  %s%n", result.summary());
                }

                Path output = out.resolve(input.getFileName());
                doc.save(output);
                outputs.add(output);
            }
        }

        SampleBase.done("S06_Forms", outputs);
    }

    private static List<FormField> readFields(PdfDocument doc) {
        List<MemorySegment> rawPages = new ArrayList<>();
        List<PdfPage> openPages = new ArrayList<>();
        try {
            for (int i = 0; i < doc.pageCount(); i++) {
                PdfPage page = doc.page(i);
                openPages.add(page);
                rawPages.add(page.rawHandle());
            }
            return PdfFormReader.readAll(doc.rawHandle(), rawPages);
        } finally {
            openPages.forEach(PdfPage::close);
        }
    }

    private static Map<String, String> valuesFor(List<FormField> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        for (FormField field : fields) {
            if (field.readOnly()) {
                continue;
            }
            switch (field.type()) {
                case TEXT -> values.put(field.name(), "JPDFium sample");
                case CHECKBOX -> values.put(field.name(), "Yes");
                case RADIO -> {
                    if (!field.exportValue().isEmpty()) {
                        values.put(field.name(), field.exportValue());
                    }
                }
                case COMBOBOX, LISTBOX -> {
                    if (!field.options().isEmpty()) {
                        values.put(field.name(), field.options().getLast());
                    }
                }
                default -> { }
            }
        }
        return values;
    }
}
