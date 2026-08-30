package com.homoludens.citacknjiga

import com.homoludens.citacknjiga.core.diagnostics.DiagnosticSink
import com.homoludens.citacknjiga.core.diagnostics.LocalDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

public class AppFoundationTest {
    @Test
    public fun startRouteIsStable() {
        assertEquals("start", AppRoute.Start.path)
        assertEquals("diagnostics", AppRoute.Diagnostics.path)
    }

    @Test
    public fun containerAcceptsTestReplacements() {
        val diagnostics = LocalDiagnostics(DiagnosticSink { })
        val variant = AppVariant(AppDistribution.FDROID, verboseDiagnostics = false)
        val container = AppContainer(diagnostics = diagnostics, variant = variant)

        assertSame(diagnostics, container.diagnostics)
        assertEquals(AppDistribution.FDROID, container.variant.distribution)
    }
}
