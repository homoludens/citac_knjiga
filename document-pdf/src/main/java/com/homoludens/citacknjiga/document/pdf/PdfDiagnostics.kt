package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode

/** Stable, redacted UI text. It never interpolates source text, paths, or URIs. */
public object PdfDiagnosticFormatter {
    public fun format(diagnostic: ImportDiagnostic, language: String = "sr"): String {
        if (language != "sr") return english(diagnostic.code)
        return when (diagnostic.code) {
            ImportDiagnosticCode.PAGE_RANGE_INVALID -> "Опсег страница није исправан. Унесите један опсег унутар приказаног броја страница."
            ImportDiagnosticCode.SOURCE_TOO_LARGE,
            ImportDiagnosticCode.PAGE_COUNT_TOO_LARGE,
            ImportDiagnosticCode.SELECTED_PAGES_TOO_MANY,
            ImportDiagnosticCode.PAGE_TEXT_TOO_LARGE,
            ImportDiagnosticCode.RANGE_TEXT_TOO_LARGE -> "PDF премашује ограничење увоза. Изаберите мању датотеку или опсег."
            ImportDiagnosticCode.PROTECTED_PDF -> "Заштићени PDF није подржан. Изаберите незаштићену датотеку."
            ImportDiagnosticCode.MALFORMED_PDF,
            ImportDiagnosticCode.UNSUPPORTED_PDF -> "PDF није могуће безбедно прочитати. Изаберите другу датотеку."
            ImportDiagnosticCode.OCR_UNSUPPORTED -> "Ова страница је слика. OCR није подржан; изаберите PDF са текстом."
            ImportDiagnosticCode.UNRELIABLE_LAYOUT -> "Редослед текста није поуздан. Не прихватајте ову страницу."
            ImportDiagnosticCode.EXTERNAL_RESOURCE_IGNORED -> "Спољни ресурси су игнорисани; коришћени су само локални PDF подаци."
            ImportDiagnosticCode.PDF_FEATURE_UNAVAILABLE -> "Увоз PDF-а тренутно није доступан јер квалификација није завршена."
            else -> "Увоз PDF-а није успео. Покушајте поново са локалном датотеком."
        }
    }

    private fun english(code: ImportDiagnosticCode): String = when (code) {
        ImportDiagnosticCode.PDF_FEATURE_UNAVAILABLE -> "PDF import is unavailable because qualification did not pass."
        ImportDiagnosticCode.OCR_UNSUPPORTED -> "Image-only pages are unsupported because OCR is unavailable."
        ImportDiagnosticCode.UNRELIABLE_LAYOUT -> "The page reading order is not reliable."
        ImportDiagnosticCode.PAGE_RANGE_INVALID -> "Enter one inclusive range within the displayed page count."
        else -> "The PDF import could not be completed safely."
    }
}
