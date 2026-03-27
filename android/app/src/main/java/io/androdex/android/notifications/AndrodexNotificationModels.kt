package io.androdex.android.notifications

internal const val RUN_COMPLETION_NOTIFICATION_SOURCE = "androdex.runCompletion"
internal const val RUN_COMPLETION_NOTIFICATION_CHANNEL_ID = "androdex.run_completion"

internal object AndrodexNotificationKeys {
    const val source = "source"
    const val threadId = "threadId"
    const val turnId = "turnId"
    const val result = "result"
    const val title = "title"
    const val body = "body"
}

internal data class AndrodexNotificationOpenPayload(
    val threadId: String,
    val turnId: String?,
)

internal data class AndrodexRunCompletionNotification(
    val threadId: String,
    val turnId: String?,
    val title: String,
    val body: String,
)

internal fun decodeNotificationOpenPayload(values: Map<String, String?>): AndrodexNotificationOpenPayload? {
    val source = values[AndrodexNotificationKeys.source]?.trim()
    if (source != RUN_COMPLETION_NOTIFICATION_SOURCE) {
        return null
    }

    val threadId = values[AndrodexNotificationKeys.threadId]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val turnId = values[AndrodexNotificationKeys.turnId]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return AndrodexNotificationOpenPayload(
        threadId = threadId,
        turnId = turnId,
    )
}
