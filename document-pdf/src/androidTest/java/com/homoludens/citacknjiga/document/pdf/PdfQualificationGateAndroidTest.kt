package com.homoludens.citacknjiga.document.pdf

import org.junit.Assert.assertFalse
import org.junit.Test

public class PdfQualificationGateAndroidTest {
    @Test
    public fun noPassQualificationKeepsProductionPdfDisabled() {
        assertFalse(PdfFeatureAvailability.QUALIFIED)
    }
}
