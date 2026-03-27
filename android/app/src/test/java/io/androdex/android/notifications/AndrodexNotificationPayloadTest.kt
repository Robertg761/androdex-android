package io.androdex.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndrodexNotificationPayloadTest {
    @Test
    fun decodeNotificationOpenPayload_readsRunCompletionExtras() {
        val payload = decodeNotificationOpenPayload(
            mapOf(
                AndrodexNotificationKeys.source to RUN_COMPLETION_NOTIFICATION_SOURCE,
                AndrodexNotificationKeys.threadId to "thread-1",
                AndrodexNotificationKeys.turnId to "turn-1",
            )
        )

        assertEquals("thread-1", payload?.threadId)
        assertEquals("turn-1", payload?.turnId)
    }

    @Test
    fun decodeNotificationOpenPayload_ignoresUnknownSources() {
        val payload = decodeNotificationOpenPayload(
            mapOf(
                AndrodexNotificationKeys.source to "something-else",
                AndrodexNotificationKeys.threadId to "thread-1",
            )
        )

        assertNull(payload)
    }

    @Test
    fun decodeNotificationOpenPayload_requiresThreadId() {
        val payload = decodeNotificationOpenPayload(
            mapOf(
                AndrodexNotificationKeys.source to RUN_COMPLETION_NOTIFICATION_SOURCE,
                AndrodexNotificationKeys.threadId to "   ",
            )
        )

        assertNull(payload)
    }
}
