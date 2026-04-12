package io.androdex.android.transport.macnative

import io.androdex.android.data.AndrodexPersistence

internal class PersistenceMacNativeSessionStore(
    private val persistence: AndrodexPersistence,
) : MacNativeSessionStore {
    override fun loadBearerSession(): MacNativePersistedSession? = persistence.loadMacNativePersistedSession()

    override fun saveBearerSession(session: MacNativePersistedSession) {
        persistence.saveMacNativePersistedSession(session)
    }

    override fun clearBearerSession() {
        persistence.clearMacNativePersistedSession()
    }

    override fun loadSnapshotSequence(): Long? = persistence.loadMacNativeSnapshotSequence()

    override fun saveSnapshotSequence(snapshotSequence: Long) {
        persistence.saveMacNativeSnapshotSequence(snapshotSequence)
    }

    override fun clearSnapshotSequence() {
        persistence.clearMacNativeSnapshotSequence()
    }
}
