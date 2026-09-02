package com.homoludens.citacknjiga.document.pdf

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

public class PdfQualificationGateAndroidTest {
    public companion object {
        @JvmStatic
        @BeforeClass
        public fun initializePdfBox() {
            PdfBoxResourceLoaderInitializer.initialize(ApplicationProvider.getApplicationContext())
        }
    }

    @Test
    public fun selectedQualificationEnablesProductionPdf() {
        assertTrue(PdfFeatureAvailability.QUALIFIED)
    }
}
