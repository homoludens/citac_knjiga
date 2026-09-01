package com.homoludens.citacknjiga.pdfqualification

import java.nio.charset.StandardCharsets

internal object PdfFixtureFactory {
    private val names = listOf(
        "latin-unicode", "cyrillic-unicode", "soft-wrapping", "one-column",
        "separated-columns", "overlapping-columns", "empty-page", "image-only-page",
        "repeated-decoration", "protected", "malformed-truncated", "unsupported-encoding",
        "external-reference",
    )

    fun names(): List<String> = names

    fun bytes(name: String, pageCount: Int = 1): ByteArray = when (name) {
        "latin-unicode" -> unicodePdf(listOf("Читанка: č ć š ž đ, Unicode € — α"))
        "cyrillic-unicode" -> unicodePdf(listOf("Српски текст: Читанка, ћирилица, Ђ"))
        "soft-wrapping" -> asciiPdf(listOf("Prvi red", "Drugi red"), pageCount = pageCount)
        "one-column" -> asciiPdf(listOf("Jedna kolona", "Drugi pasus"))
        "separated-columns" -> asciiPdf(listOf("LEVA KOLONA@72,700", "DESNA KOLONA@360,700"))
        "overlapping-columns" -> asciiPdf(listOf("PREKLAPANJE@72,700", "NEPOUZDANO@100,705"))
        "empty-page" -> asciiPdf(emptyList())
        "image-only-page" -> imageOnlyPdf()
        "repeated-decoration" -> asciiPdf(listOf("Naslov", "Naslov", "Tekst"))
        "protected" -> protectedPdfMarker()
        "malformed-truncated" -> "%PDF-1.4\n1 0 obj<< /Type /Catalog >>endobj\n".toByteArray()
        "unsupported-encoding" -> asciiPdf(emptyList(), pageObject = "3 0 obj<< /Type /NotAPage /Parent 2 0 R >>endobj\n")
        "external-reference" -> externalReferencePdf()
        else -> error("unknown fixture: $name")
    }

    private fun asciiPdf(lines: List<String>, pageCount: Int = 1, pageObject: String? = null): ByteArray {
        require(pageCount >= 1)
        val content = lines.joinToString(" ") { line ->
            val parts = line.split('@', limit = 2)
            val position = parts.getOrNull(1)?.split(',')
            val move = if (position?.size == 2) "${position[0]} ${position[1]} Td " else "72 720 Td "
            "BT /F1 12 Tf $move (${escape(parts[0])}) Tj ET"
        }
        if (pageObject != null) {
            return pdf(
                listOf(
                    "1 0 obj<< /Type /Catalog /Pages 2 0 R>>endobj\n",
                    "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1>>endobj\n",
                    pageObject,
                    "4 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica>>endobj\n",
                    streamObject(5, content),
                ),
            )
        }
        val firstPage = 3
        val font = firstPage + pageCount
        val firstContent = font + 1
        val pages = (0 until pageCount).map { index ->
            val page = firstPage + index
            val contentObject = firstContent + index
            "$page 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 $font 0 R >> >> /Contents $contentObject 0 R>>endobj\n"
        }
        return pdf(
            listOf(
                "1 0 obj<< /Type /Catalog /Pages 2 0 R>>endobj\n",
                "2 0 obj<< /Type /Pages /Kids [${pages.indices.joinToString(" ") { "${firstPage + it} 0 R" }}] /Count $pageCount>>endobj\n",
            ) + pages + listOf(
                "$font 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica>>endobj\n",
            ) + (0 until pageCount).map { streamObject(firstContent + it, content) },
        )
    }

    private fun unicodePdf(lines: List<String>): ByteArray {
        val values = lines.joinToString("\n").codePoints().toArray().distinct()
        val cids = values.withIndex().associate { (index, value) -> value to index + 1 }
        val content = lines.joinToString(" ") { line ->
            val encoded = line.codePoints().map { cids.getValue(it) }.toArray()
                .joinToString("") { "%04x".format(it) }
            "BT /F1 12 Tf 72 720 Td <$encoded> Tj ET"
        }
        val cmap = buildString {
            append("/CIDInit /ProcSet findresource begin\n12 dict begin begincmap\n")
            append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
            append("/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n")
            append("1 begincodespacerange <0000> <ffff> endcodespacerange\n")
            append(values.size).append(" beginbfchar\n")
            values.forEachIndexed { index, value -> append("<%04x> <%04x>\n".format(index + 1, value)) }
            append("endbfchar endcmap CMapName currentdict /CMap defineresource pop end end\n")
        }
        return pdf(
            listOf(
                "1 0 obj<< /Type /Catalog /Pages 2 0 R>>endobj\n",
                "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1>>endobj\n",
                "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 8 0 R>>endobj\n",
                "4 0 obj<< /Type /Font /Subtype /Type0 /BaseFont /QualificationUnicode /Encoding /Identity-H /DescendantFonts [5 0 R] /ToUnicode 7 0 R>>endobj\n",
                "5 0 obj<< /Type /Font /Subtype /CIDFontType0 /BaseFont /QualificationUnicode /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 600>>endobj\n",
                "6 0 obj<< /Length 0>>stream\nendstream endobj\n",
                streamObject(7, cmap),
                streamObject(8, content),
            ),
        )
    }

    private fun imageOnlyPdf(): ByteArray = pdf(
        listOf(
            "1 0 obj<< /Type /Catalog /Pages 2 0 R>>endobj\n",
            "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1>>endobj\n",
            "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] /Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R>>endobj\n",
            streamObject(4, "q /Im1 Do Q"),
            "5 0 obj<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length 1>>stream\n0\nendstream endobj\n",
        ),
    )

    private fun externalReferencePdf(): ByteArray = pdf(
        listOf(
            "1 0 obj<< /Type /Catalog /Pages 2 0 R>>endobj\n",
            "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1>>endobj\n",
            "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R /Annots [6 0 R]>>endobj\n",
            "4 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica>>endobj\n",
            streamObject(5, "BT /F1 12 Tf 72 720 Td (Only local text) Tj ET"),
            "6 0 obj<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /Type /Action /S /URI /URI (https://qualification.invalid/sentinel) >> >>endobj\n",
        ),
    )

    private fun protectedPdfMarker(): ByteArray = pdf(
        listOf(
            "1 0 obj<< /Type /Catalog /Pages 2 0 R>>endobj\n",
            "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1>>endobj\n",
            "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]>>endobj\n",
            "4 0 obj<< /Filter /Standard /V 1 /R 2 /O <0000000000000000000000000000000000000000000000000000000000000000> /U <0000000000000000000000000000000000000000000000000000000000000000> /P -4>>endobj\n",
        ),
        encryptObject = 4,
    )

    private fun streamObject(number: Int, value: String): String {
        val bytes = value.toByteArray(StandardCharsets.ISO_8859_1)
        return "$number 0 obj<< /Length ${bytes.size}>>stream\n$value\nendstream endobj\n"
    }

    private fun pdf(objects: List<String>, encryptObject: Int? = null): ByteArray {
        val output = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objects.forEach { value -> offsets += output.length; output.append(value) }
        val xref = output.length
        output.append("xref\n0 ").append(objects.size + 1).append("\n0000000000 65535 f \n")
        offsets.drop(1).forEach { output.append("%010d 00000 n \n".format(it)) }
        output.append("trailer<< /Size ").append(objects.size + 1).append(" /Root 1 0 R")
        if (encryptObject != null) output.append(" /Encrypt ").append(encryptObject).append(" 0 R")
        output.append(">>\nstartxref\n").append(xref).append("\n%%EOF\n")
        return output.toString().toByteArray(StandardCharsets.ISO_8859_1)
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}
