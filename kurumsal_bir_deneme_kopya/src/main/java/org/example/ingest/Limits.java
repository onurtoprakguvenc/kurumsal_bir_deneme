package org.example.ingest;

/**
 * Hard resource limits for structured-format ingestion. Every limit is enforced while streaming, so memory use
 * is bounded by these values, never by the size of the input file.
 *
 * @param maxUncompressedBytes   total decompressed bytes an OOXML package may declare and inflate
 * @param maxPartBytes           decompressed bytes of any single package part (e.g. one worksheet XML)
 * @param maxZipEntries          entries in an OOXML package
 * @param maxInflateRatio        largest accepted uncompressed/compressed ratio for parts above 1 MiB
 * @param maxXmlDepth            element nesting depth in any XML part
 * @param maxCellChars           characters kept per cell (longer values are clipped with "…")
 * @param maxColumns             columns rendered per table block (further columns are counted, not rendered)
 * @param maxRowsPerTable        rows ingested per sheet / CSV file
 * @param maxCells               non-empty cells ingested per document
 * @param maxSharedStringsBytes  decompressed size of {@code xl/sharedStrings.xml}; its strings are the only
 *                               workbook-wide data held in memory (in one compact character buffer)
 * @param maxSlides              slides ingested per presentation
 * @param maxSlideChars          rendered characters kept per slide (notes included)
 * @param tableBlockChars        target rendered size of one Markdown table block (keep below the chunk size so
 *                               every chunk starts with a column-coordinate header)
 * @param maxHeapUsageRatio      fraction of {@code Runtime.maxMemory()} above which ingestion refuses to start and
 *                               a running extraction stops gracefully (0.85 = 85 % of the heap in use)
 */
public record Limits(long maxUncompressedBytes,
                     long maxPartBytes,
                     int maxZipEntries,
                     int maxInflateRatio,
                     int maxXmlDepth,
                     int maxCellChars,
                     int maxColumns,
                     long maxRowsPerTable,
                     long maxCells,
                     long maxSharedStringsBytes,
                     int maxSlides,
                     int maxSlideChars,
                     int tableBlockChars,
                     double maxHeapUsageRatio) {

    public static final Limits DEFAULTS = new Limits(
            1L << 30,
            512L << 20,
            10_000,
            200,
            64,
            2_000,
            64,
            1_000_000,
            5_000_000,
            128L << 20,
            5_000,
            100_000,
            900,
            0.85);

    public Limits {
        if (maxUncompressedBytes <= 0 || maxPartBytes <= 0 || maxZipEntries <= 0 || maxInflateRatio <= 0
                || maxXmlDepth < 8 || maxCellChars < 16 || maxColumns < 1 || maxRowsPerTable < 1 || maxCells < 1
                || maxSharedStringsBytes <= 0 || maxSlides < 1 || maxSlideChars < 1_000
                || tableBlockChars < 200 || maxHeapUsageRatio <= 0.1 || maxHeapUsageRatio > 0.99) {
            throw new IllegalArgumentException("Invalid ingestion limits: " + this);
        }
    }

    public Limits withTableBlockChars(int chars) {
        return new Limits(maxUncompressedBytes, maxPartBytes, maxZipEntries, maxInflateRatio, maxXmlDepth,
                maxCellChars, maxColumns, maxRowsPerTable, maxCells, maxSharedStringsBytes, maxSlides, maxSlideChars,
                chars, maxHeapUsageRatio);
    }
}
