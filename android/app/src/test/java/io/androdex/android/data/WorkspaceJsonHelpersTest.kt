package io.androdex.android.data

import io.androdex.android.model.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceJsonHelpersTest {
    @Test
    fun threadSummary_projectName_handlesWindowsPaths() {
        val thread = ThreadSummary(
            id = "thread-1",
            title = "Conversation",
            preview = null,
            cwd = "C:\\Projects\\AppA",
            createdAtEpochMs = null,
            updatedAtEpochMs = null,
        )

        assertEquals("AppA", thread.projectName)
    }
}
