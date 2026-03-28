package io.androdex.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRequestCompatibilityTest {
    @Test
    fun shouldRetryTurnWithoutCollaborationMode_requiresExplicitCompatibilityError() {
        assertFalse(
            shouldRetryTurnWithoutCollaborationMode("Temporary collaborationMode lookup timeout")
        )
        assertTrue(
            shouldRetryTurnWithoutCollaborationMode("Unknown field collaborationMode")
        )
    }
}
