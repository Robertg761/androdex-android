package io.androdex.android.notifications

import java.util.concurrent.atomic.AtomicBoolean

internal object AndrodexAppProcessState {
    private val isForegroundAtomic = AtomicBoolean(false)

    var isForeground: Boolean
        get() = isForegroundAtomic.get()
        set(value) {
            isForegroundAtomic.set(value)
        }
}
