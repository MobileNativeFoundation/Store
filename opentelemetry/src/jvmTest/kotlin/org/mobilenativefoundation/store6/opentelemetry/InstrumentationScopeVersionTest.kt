package org.mobilenativefoundation.store6.opentelemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InstrumentationScopeVersionTest {
    @Test
    fun scopeVersionConstantMatchesTheModuleVersion() {
        // Forwarded by the module build file from the root VERSION_NAME property. RELEASING.md:
        // module gradle.properties files must not reintroduce VERSION_NAME, so the root
        // property is the only source; a missing property fails the test rather than silently
        // passing.
        val versionName = System.getProperty("store6.opentelemetry.versionName")
        assertNotNull(versionName, "store6.opentelemetry.versionName system property is not set")
        assertEquals(versionName, INSTRUMENTATION_SCOPE_VERSION)
    }
}
