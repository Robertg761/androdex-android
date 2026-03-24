package io.androdex.android.data

import android.content.Context
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ModelOption
import io.androdex.android.model.ThreadSummary
import kotlinx.coroutines.flow.SharedFlow

class AndrodexRepository(context: Context) {
    private val persistence = AndrodexPersistence(context.applicationContext)
    private val client = AndrodexClient(persistence)

    val updates: SharedFlow<ClientUpdate> = client.updates

    fun hasSavedPairing(): Boolean = client.hasSavedPairing()

    fun currentFingerprint(): String? = client.currentFingerprint()

    suspend fun connectWithPairingPayload(rawPayload: String) {
        client.connectWithPairingPayload(rawPayload)
    }

    suspend fun reconnectSaved() {
        client.reconnectSaved()
    }

    suspend fun disconnect(clearSavedPairing: Boolean = false) {
        client.disconnect(clearSavedPairing)
    }

    suspend fun refreshThreads(): List<ThreadSummary> = client.listThreads()

    suspend fun startThread(): ThreadSummary = client.startThread()

    suspend fun loadThread(threadId: String): Pair<ThreadSummary?, List<ConversationMessage>> {
        return client.loadThread(threadId)
    }

    suspend fun startTurn(threadId: String, userInput: String) {
        client.startTurn(threadId, userInput)
    }

    suspend fun loadRuntimeConfig() {
        client.loadRuntimeConfig()
    }

    suspend fun setSelectedModelId(modelId: String?) {
        client.setSelectedModelId(modelId)
    }

    suspend fun setSelectedReasoningEffort(effort: String?) {
        client.setSelectedReasoningEffort(effort)
    }

    suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        client.respondToApproval(request, accept)
    }
}
