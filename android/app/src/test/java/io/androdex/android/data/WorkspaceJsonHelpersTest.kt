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

    @Test
    fun threadSummary_projectName_defaultsWhenWorkspaceMissing() {
        val thread = ThreadSummary(
            id = "thread-2",
            title = "Conversation",
            preview = null,
            cwd = null,
            createdAtEpochMs = null,
            updatedAtEpochMs = null,
        )

        assertEquals("No Project", thread.projectName)
    }
}
