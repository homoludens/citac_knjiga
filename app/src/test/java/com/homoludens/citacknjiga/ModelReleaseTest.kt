package com.homoludens.citacknjiga

import com.homoludens.citacknjiga.diagnostics.ModelReleaseAction
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

public class ModelReleaseTest {
    @Test
    public fun acceptsOnlyAbsoluteHttpUrlsWithHosts() {
        assertNotNull(ModelReleaseAction.validatedUrl("https://example.com/releases/model"))
        assertNotNull(ModelReleaseAction.validatedUrl("http://example.com/model"))
    }

    @Test
    public fun rejectsEmptyAlternateMalformedAndCredentialBearingUrls() {
        listOf(
            "",
            "file:///model.zip",
            "content://model",
            "intent://model",
            "javascript:alert(1)",
            "https://user:password@example.com/model",
            "https:///model",
            "not a url",
        ).forEach { assertNull(it, ModelReleaseAction.validatedUrl(it)) }
    }
}
