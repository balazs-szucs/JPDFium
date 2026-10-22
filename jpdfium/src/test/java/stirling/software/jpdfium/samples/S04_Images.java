package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageConverter;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.ExtractedImage;
import stirling.software.jpdfium.doc.PdfImageExtractor;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.ImageToPdfOptions;
import stirling.software.jpdfium.model.PdfToImageOptions;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Converts pages to images, extracts embedded images, and builds a PDF from images. */
public final class S04_Images {

    private static final int DPI = 96;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        Path out = SampleBase.out("images");
        List<Path> outputs = new ArrayList<>();

        for (Path input : SampleBase.inputs(args, "pdfs/general/minimal.pdf")) {
            SampleBase.header("S04_Images", input);
            Path dir = Files.createDirectories(out.resolve(SampleBase.stem(input)));

            try (PdfDocument doc = PdfDocument.open(input)) {
                PdfToImageOptions pageImages = PdfToImageOptions.builder()
                        .format(ImageFormat.PNG)
                        .dpi(DPI)
                        .pages(Set.of(0))
                        .build();
                List<Path> converted = PdfImageConverter.pdfToImages(doc, pageImages, dir);
                outputs.addAll(converted);
                System.out.printf("  page 0 -> %d PNG file(s)%n", converted.size());

                for (int i = 0; i < doc.pageCount(); i++) {
                    try (PdfPage page = doc.page(i)) {
                        List<ExtractedImage> images =
                                PdfImageExtractor.extract(doc.rawHandle(), page.rawHandle(), i);
                        for (ExtractedImage image : images) {
                            Path file = dir.resolve("%s-p%d-img%d%s".formatted(
                                    SampleBase.stem(input), i, image.index(), image.suggestedExtension()));
                            image.save(file);
                            outputs.add(file);
                            System.out.printf("  extracted %dx%d image -> %s%n",
                                    image.width(), image.height(), file.getFileName());
                        }
                    }
                }
            }
        }

        ImageToPdfOptions pdfOptions = ImageToPdfOptions.builder()
                .a4()
                .margin(36)
                .build();
        try (PdfDocument pdf = PdfImageConverter.imagesToPdfFromImages(List.of(sampleImage()), pdfOptions)) {
            Path file = out.resolve("image-to-pdf.pdf");
            pdf.save(file);
            outputs.add(file);
        }

        SampleBase.done("S04_Images", outputs);
    }

    private static BufferedImage sampleImage() {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.DARK_GRAY);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
            graphics.drawString("JPDFium", 300, 320);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
