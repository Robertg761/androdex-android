package io.androdex.android

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

internal object ThreadOpenPerfLogger {
    private const val logTag = "ThreadOpenPerf"
    private const val sessionTtlMs = 30_000L
    private val nextAttemptId = AtomicLong(1L)
    private val sessions = linkedMapOf<String, Session>()
    @Volatile
    private var logObserver: ((Entry) -> Unit)? = null

    private data class Session(
        val attemptId: Long,
        val startedAtMs: Long,
    )

    internal data class Entry(
        val attemptId: Long,
        val threadId: String,
        val stage: String,
        val sinceStartMs: Long,
        val durationMs: Long?,
        val extra: String?,
    )

    fun startAttempt(
        threadId: String,
        stage: String,
        extra: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = nowMs()
        val session = synchronized(this) {
            pruneExpiredSessionsLocked(now)
            Session(
                attemptId = nextAttemptId.getAndIncrement(),
                startedAtMs = now,
            ).also { sessions[threadId] = it }
        }
        log(session = session, threadId = threadId, stage = stage, durationMs = null, extra = extra)
    }

    fun ensureAttempt(
        threadId: String,
        stage: String,
        extra: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = nowMs()
        val session = synchronized(this) {
            pruneExpiredSessionsLocked(now)
            sessions[threadId] ?: Session(
                attemptId = nextAttemptId.getAndIncrement(),
                startedAtMs = now,
            ).also { sessions[threadId] = it }
        }
        log(session = session, threadId = threadId, stage = stage, durationMs = null, extra = extra)
    }

    fun logStage(
        threadId: String?,
        stage: String,
        durationMs: Long? = null,
        extra: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val now = nowMs()
        val session = synchronized(this) {
            pruneExpiredSessionsLocked(now)
            sessions[normalizedThreadId]
        } ?: return
        log(
            session = session,
            threadId = normalizedThreadId,
            stage = stage,
            durationMs = durationMs,
            extra = extra,
        )
    }

    inline fun <T> measure(
        threadId: String?,
        stage: String,
        noinline extra: (() -> String?)? = null,
        block: () -> T,
    ): T {
        if (!BuildConfig.DEBUG) return block()
        val normalizedThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() } ?: return block()
        val startedAt = nowMs()
        return try {
            block()
        } finally {
            logStage(
                threadId = normalizedThreadId,
                stage = stage,
                durationMs = nowMs() - startedAt,
                extra = extra?.invoke(),
            )
        }
    }

    internal fun setLogObserverForTests(observer: ((Entry) -> Unit)?) {
        logObserver = observer
    }

    private fun log(
        session: Session,
        threadId: String,
        stage: String,
        durationMs: Long?,
        extra: String?,
    ) {
        val sinceStartMs = nowMs() - session.startedAtMs
        val durationPart = durationMs?.let { " durationMs=$it" }.orEmpty()
        val extraPart = extra?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        val entry = Entry(
            attemptId = session.attemptId,
            threadId = threadId,
            stage = stage,
            sinceStartMs = sinceStartMs,
            durationMs = durationMs,
            extra = extra,
        )
        logObserver?.invoke(entry)
        runCatching {
            Log.d(
                logTag,
                "open[${session.attemptId}] thread=$threadId stage=$stage sinceStartMs=$sinceStartMs$durationPart$extraPart",
            )
        }
    }

    private fun pruneExpiredSessionsLocked(now: Long) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (_, session) = iterator.next()
            if (now - session.startedAtMs > sessionTtlMs) {
                iterator.remove()
            }
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
