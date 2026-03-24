package io.androdex.android.data

import io.androdex.android.model.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.json.JSONArray
import org.json.JSONObject
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
    fun decodeWorkspaceBrowseResult_keepsJsonNullPathAsNull() {
        val result = decodeWorkspaceBrowseResult(
            JSONObject()
                .put("requestedPath", JSONObject.NULL)
                .put("parentPath", JSONObject.NULL)
                .put(
                    "rootEntries",
                    JSONArray().put(
                        JSONObject()
                            .put("path", "C:\\")
                            .put("name", "C:")
                            .put("isDirectory", true)
                            .put("isActive", false)
                            .put("source", "root")
                    )
                )
        )

        assertNull(result.requestedPath)
        assertNull(result.parentPath)
        assertEquals("C:\\", result.rootEntries.single().path)
    }
}
