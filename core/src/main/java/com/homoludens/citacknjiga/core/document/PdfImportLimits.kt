package com.homoludens.citacknjiga.core.document

public data class PdfImportLimits(
    public val maxSourceBytes: Long = 512L * 1_048_576L,
    public val maxPages: Int = 10_000,
    public val maxSelectedPages: Int = 200,
    public val maxPageTextBytes: Long = 1L * 1_048_576L,
    public val maxRangeTextBytes: Long = 32L * 1_048_576L,
    public val maxProcessingNanos: Long = 120L * 1_000_000_000L,
) {
    init {
        require(maxSourceBytes > 0 && maxPages > 0 && maxSelectedPages > 0)
        require(maxPageTextBytes > 0 && maxRangeTextBytes > 0 && maxProcessingNanos > 0)
    }

    public companion object {
        public val Production: PdfImportLimits = PdfImportLimits()
    }
}

public fun PdfImportLimits.acceptsSourceBytes(value: Long): Boolean = value <= maxSourceBytes

public fun PdfImportLimits.acceptsPageCount(value: Int): Boolean = value <= maxPages

public fun PdfImportLimits.acceptsSelectedPages(value: Int): Boolean = value <= maxSelectedPages

public fun PdfImportLimits.acceptsPageTextBytes(value: Long): Boolean = value <= maxPageTextBytes

public fun PdfImportLimits.acceptsRangeTextBytes(value: Long): Boolean = value <= maxRangeTextBytes
