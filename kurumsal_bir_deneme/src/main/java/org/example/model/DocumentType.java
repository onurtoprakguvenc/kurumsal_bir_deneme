package org.example.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Supported source formats. Detection prefers magic bytes over file extensions.
 */
public enum DocumentType {

    PDF("pdf"),
    DOCX("docx"),
    XLSX("xlsx"),
    PPTX("pptx"),
    PPT("ppt"),
    /** Word 97-2003 binary document. */
    DOC("doc"),
    /** Excel 97-2003 binary workbook (BIFF8). */
    XLS("xls"),
    CSV("csv"),
    TXT("txt");

    private final String extension;

    DocumentType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static Optional<DocumentType> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "pdf" -> Optional.of(PDF);
            case "docx" -> Optional.of(DOCX);
            case "xlsx", "xlsm" -> Optional.of(XLSX);
            case "pptx", "pptm" -> Optional.of(PPTX);
            case "ppt", "pps" -> Optional.of(PPT);
            case "doc", "dot" -> Optional.of(DOC);
            case "xls", "xlt" -> Optional.of(XLS);
            case "csv", "tsv" -> Optional.of(CSV);
            case "txt", "text", "md", "markdown", "log" -> Optional.of(TXT);
            default -> Optional.empty();
        };
    }
}
