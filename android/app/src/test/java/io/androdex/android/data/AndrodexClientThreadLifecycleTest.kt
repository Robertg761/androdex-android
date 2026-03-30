package io.androdex.android.data

import io.androdex.android.model.ClientUpdate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndrodexClientThreadLifecycleTest {
    @Test
    fun threadStartedNotification_doesNotPromoteThreadCreationIntoTurnStart() {
        val update = threadLifecycleUpdateForNotification(
            method = "thread/started",
            params = JSONObject()
                .put("threadId", "thread-1")
                .put("turnId", "turn-1"),
        )

        assertNull(update)
    }

    @Test
    fun threadStatusChangedNotification_mapsStatusUpdate() {
        val update = threadLifecycleUpdateForNotification(
            method = "thread/status/changed",
            params = JSONObject()
                .put("threadId", "thread-1")
                .put("status", "running"),
        )

        assertEquals(
            ClientUpdate.ThreadStatusChanged(
                threadId = "thread-1",
                status = "running",
            ),
            update,
        )
    }
}
