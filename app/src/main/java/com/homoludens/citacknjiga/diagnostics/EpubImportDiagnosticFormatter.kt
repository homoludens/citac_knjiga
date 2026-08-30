package com.homoludens.citacknjiga.diagnostics

import com.homoludens.citacknjiga.document.epub.EpubSecurityDiagnostic

public enum class EpubDiagnosticLanguage { SERBIAN, ENGLISH }

/** Maps structured EPUB diagnostics once, without exposing source text or complete URIs. */
public object EpubImportDiagnosticFormatter {
    public fun format(
        diagnostic: EpubSecurityDiagnostic,
        language: EpubDiagnosticLanguage = EpubDiagnosticLanguage.SERBIAN,
    ): String {
        val scope = diagnostic.scope
        val observed = diagnostic.observed?.let { "$it ${diagnostic.observedUnit ?: ""}".trim() }
            ?: diagnostic.observedCategory.orEmpty()
        val allowed = diagnostic.limit?.let { "$it ${diagnostic.observedUnit ?: ""}".trim() }
            ?: diagnostic.allowedCondition.orEmpty()
        val english = when (diagnostic.rule) {
            "resource.xml-text-bytes" -> "XML/text resource $scope is $observed; the limit is $allowed."
            "archive.source-bytes" -> "The EPUB source is $observed; the limit is $allowed."
            "compat.external-hyperlink" -> "External ${diagnostic.scheme ?: ""} hyperlink in $scope was retained as a non-fetching reference."
            "compat.doctype" -> "The allowlisted doctype in $scope was recovered without loading an external DTD."
            "compat.font-obfuscation" -> "Supported font obfuscation in $scope was recovered without decoding the font."
            else -> "EPUB security rule ${diagnostic.rule} failed for $scope. Observed: $observed. Allowed: $allowed."
        }
        if (language == EpubDiagnosticLanguage.ENGLISH) return english
        return when (diagnostic.rule) {
            "resource.xml-text-bytes" -> "XML/tekst resurs $scope ima $observed; dozvoljeno je $allowed."
            "archive.source-bytes" -> "Izvorni EPUB ima $observed; dozvoljeno je $allowed."
            "compat.external-hyperlink" -> "Spoljna ${diagnostic.scheme ?: ""} veza u resursu $scope je sačuvana bez pristupa mreži."
            "compat.doctype" -> "Dozvoljeni doctype u resursu $scope je oporavljen bez učitavanja spoljnog DTD-a."
            "compat.font-obfuscation" -> "Podržano prikrivanje fonta u resursu $scope je oporavljeno bez dekodiranja fonta."
            else -> "EPUB bezbednosno pravilo ${diagnostic.rule} nije zadovoljeno za $scope. Zapaženo: $observed. Dozvoljeno: $allowed."
        }
    }

    public fun formatEnglish(diagnostic: EpubSecurityDiagnostic): String = format(diagnostic, EpubDiagnosticLanguage.ENGLISH)

    public fun formatSerbian(diagnostic: EpubSecurityDiagnostic): String = format(diagnostic, EpubDiagnosticLanguage.SERBIAN)
}
