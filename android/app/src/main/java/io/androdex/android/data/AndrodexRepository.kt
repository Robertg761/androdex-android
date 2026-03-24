package io.androdex.android.data

import android.content.Context
import io.androdex.android.model.ApprovalRequest
import io.androdex.android.model.ClientUpdate
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ModelOption
import io.androdex.android.model.ThreadSummary
import io.androdex.android.model.WorkspaceActivationStatus
import io.androdex.android.model.WorkspaceBrowseResult
import io.androdex.android.model.WorkspaceRecentState
import kotlinx.coroutines.flow.SharedFlow

interface AndrodexRepositoryContract {
    val updates: SharedFlow<ClientUpdate>
    fun hasSavedPairing(): Boolean
    fun currentFingerprint(): String?
    fun startupNotice(): String?
    suspend fun connectWithPairingPayload(rawPayload: String)
    suspend fun reconnectSaved()
    suspend fun disconnect(clearSavedPairing: Boolean = false)
    suspend fun refreshThreads(): List<ThreadSummary>
    suspend fun startThread(preferredProjectPath: String? = null): ThreadSummary
    suspend fun loadThread(threadId: String): Pair<ThreadSummary?, List<ConversationMessage>>
    suspend fun startTurn(threadId: String, userInput: String)
    suspend fun loadRuntimeConfig()
    suspend fun setSelectedModelId(modelId: String?)
    suspend fun setSelectedReasoningEffort(effort: String?)
    suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean)
    suspend fun listRecentWorkspaces(): WorkspaceRecentState
    suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult
    suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus
}

class AndrodexRepository(context: Context) : AndrodexRepositoryContract {
    private val persistence = AndrodexPersistence(context.applicationContext)
    private val client = AndrodexClient(persistence)

    override val updates: SharedFlow<ClientUpdate> = client.updates

    override fun hasSavedPairing(): Boolean = client.hasSavedPairing()

    override fun currentFingerprint(): String? = client.currentFingerprint()

    override fun startupNotice(): String? = persistence.takeStartupNotice()

    override suspend fun connectWithPairingPayload(rawPayload: String) {
        client.connectWithPairingPayload(rawPayload)
    }

    override suspend fun reconnectSaved() {
        client.reconnectSaved()
    }

    override suspend fun disconnect(clearSavedPairing: Boolean) {
        client.disconnect(clearSavedPairing)
    }

    override suspend fun refreshThreads(): List<ThreadSummary> = client.listThreads()

    override suspend fun startThread(preferredProjectPath: String?): ThreadSummary = client.startThread(preferredProjectPath)

    override suspend fun loadThread(threadId: String): Pair<ThreadSummary?, List<ConversationMessage>> {
        return client.loadThread(threadId)
    }

    override suspend fun startTurn(threadId: String, userInput: String) {
        client.startTurn(threadId, userInput)
    }

    override suspend fun loadRuntimeConfig() {
        client.loadRuntimeConfig()
    }

    override suspend fun setSelectedModelId(modelId: String?) {
        client.setSelectedModelId(modelId)
    }

    override suspend fun setSelectedReasoningEffort(effort: String?) {
        client.setSelectedReasoningEffort(effort)
    }

    override suspend fun respondToApproval(request: ApprovalRequest, accept: Boolean) {
        client.respondToApproval(request, accept)
    }

    override suspend fun listRecentWorkspaces(): WorkspaceRecentState = client.listRecentWorkspaces()

    override suspend fun listWorkspaceDirectory(path: String?): WorkspaceBrowseResult = client.listWorkspaceDirectory(path)

    override suspend fun activateWorkspace(cwd: String): WorkspaceActivationStatus = client.activateWorkspace(cwd)
}
