package io.relaydex.android

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.relaydex.android.data.RemodexRepository
import io.relaydex.android.model.ApprovalRequest
import io.relaydex.android.model.ClientUpdate
import io.relaydex.android.model.ConnectionStatus
import io.relaydex.android.model.ConversationKind
import io.relaydex.android.model.ConversationMessage
import io.relaydex.android.model.ConversationRole
import io.relaydex.android.model.ThreadSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class RemodexUiState(
    val pairingInput: String = "",
    val hasSavedPairing: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionDetail: String? = null,
    val secureFingerprint: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val selectedThreadId: String? = null,
    val selectedThreadTitle: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val composerText: String = "",
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val pendingApproval: ApprovalRequest? = null,
)

class MainViewModel(
    private val repository: RemodexRepository,
) : ViewModel() {
    private val uiStateFlow = MutableStateFlow(
        RemodexUiState(
            hasSavedPairing = repository.hasSavedPairing(),
            secureFingerprint = repository.currentFingerprint(),
        )
    )

    val uiState: StateFlow<RemodexUiState> = uiStateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            repository.updates.collect(::handleClientUpdate)
        }
    }

    fun consumeIntent(intent: Intent?) {
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val deepLinkText = intent?.data?.extractPairingPayload()
        val payload = sharedText?.takeIf { it.isNotEmpty() }
            ?: deepLinkText?.takeIf { it.isNotEmpty() }
            ?: return
        uiStateFlow.update { it.copy(pairingInput = payload) }
    }

    fun updatePairingInput(value: String) {
        uiStateFlow.update { it.copy(pairingInput = value) }
    }

    fun updateComposerText(value: String) {
        uiStateFlow.update { it.copy(composerText = value) }
    }

    fun clearError() {
        uiStateFlow.update { it.copy(errorMessage = null) }
    }

    fun connectWithCurrentPairingInput() {
        val payload = uiStateFlow.value.pairingInput.trim()
        if (payload.isEmpty()) {
            uiStateFlow.update { it.copy(errorMessage = "Paste or scan the pairing payload first.") }
            return
        }

        runBusyAction {
            repository.connectWithPairingPayload(payload)
            repository.refreshThreads()
        }
    }

    fun reconnectSaved() {
        runBusyAction {
            repository.reconnectSaved()
            repository.refreshThreads()
        }
    }

    fun disconnect(clearSavedPairing: Boolean = false) {
        runBusyAction {
            repository.disconnect(clearSavedPairing)
        }
    }

    fun refreshThreads() {
        runBusyAction {
            repository.refreshThreads()
        }
    }

    fun openThread(threadId: String) {
        uiStateFlow.update { current ->
            current.copy(
                selectedThreadId = threadId,
                selectedThreadTitle = current.threads.firstOrNull { it.id == threadId }?.title,
                messages = emptyList(),
            )
        }

        runBusyAction {
            repository.loadThread(threadId)
        }
    }

    fun closeThread() {
        uiStateFlow.update {
            it.copy(
                selectedThreadId = null,
                selectedThreadTitle = null,
                messages = emptyList(),
            )
        }
    }

    fun createThread() {
        runBusyAction {
            val thread = repository.startThread()
            repository.refreshThreads()
            repository.loadThread(thread.id)
            uiStateFlow.update { current ->
                current.copy(
                    selectedThreadId = thread.id,
                    selectedThreadTitle = thread.title,
                    messages = emptyList(),
                )
            }
        }
    }

    fun sendMessage() {
        val input = uiStateFlow.value.composerText.trim()
        if (input.isEmpty()) {
            return
        }

        runBusyAction {
            val threadId = uiStateFlow.value.selectedThreadId ?: repository.startThread().id.also { newThreadId ->
                repository.refreshThreads()
                uiStateFlow.update { current ->
                    current.copy(
                        selectedThreadId = newThreadId,
                        selectedThreadTitle = current.threads.firstOrNull { it.id == newThreadId }?.title ?: "Conversation",
                    )
                }
            }

            uiStateFlow.update { current ->
                current.copy(
                    composerText = "",
                    messages = current.messages + ConversationMessage(
                        id = UUID.randomUUID().toString(),
                        threadId = threadId,
                        role = ConversationRole.USER,
                        kind = ConversationKind.CHAT,
                        text = input,
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }

            repository.startTurn(threadId, input)
        }
    }

    fun respondToApproval(accept: Boolean) {
        val request = uiStateFlow.value.pendingApproval ?: return
        runBusyAction {
            repository.respondToApproval(request, accept)
        }
    }

    private fun runBusyAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            uiStateFlow.update { it.copy(isBusy = true) }
            try {
                block()
            } catch (error: Throwable) {
                uiStateFlow.update {
                    it.copy(errorMessage = error.message ?: "Request failed.")
                }
            } finally {
                uiStateFlow.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun handleClientUpdate(update: ClientUpdate) {
        when (update) {
            is ClientUpdate.Connection -> {
                uiStateFlow.update {
                    it.copy(
                        connectionStatus = update.status,
                        connectionDetail = update.detail,
                        secureFingerprint = update.fingerprint ?: it.secureFingerprint,
                    )
                }
            }

            is ClientUpdate.PairingAvailability -> {
                uiStateFlow.update {
                    it.copy(
                        hasSavedPairing = update.hasSavedPairing,
                        secureFingerprint = update.fingerprint,
                    )
                }
            }

            is ClientUpdate.ThreadsLoaded -> {
                uiStateFlow.update { current ->
                    current.copy(
                        threads = update.threads,
                        selectedThreadTitle = update.threads.firstOrNull { it.id == current.selectedThreadId }?.title
                            ?: current.selectedThreadTitle,
                    )
                }
            }

            is ClientUpdate.ThreadLoaded -> {
                uiStateFlow.update { current ->
                    current.copy(
                        selectedThreadTitle = update.thread?.title ?: current.selectedThreadTitle,
                        messages = update.messages,
                    )
                }
            }

            is ClientUpdate.AssistantDelta -> {
                val selectedThreadId = uiStateFlow.value.selectedThreadId
                if (update.threadId != null && selectedThreadId != null && update.threadId != selectedThreadId) {
                    return
                }
                uiStateFlow.update { current ->
                    current.copy(messages = applyAssistantDelta(current.messages, current.selectedThreadId, update))
                }
            }

            is ClientUpdate.AssistantCompleted -> {
                uiStateFlow.update { current ->
                    current.copy(messages = applyAssistantCompletion(current.messages, current.selectedThreadId, update))
                }
            }

            is ClientUpdate.ApprovalRequested -> {
                uiStateFlow.update { it.copy(pendingApproval = update.request) }
            }

            ClientUpdate.ApprovalCleared -> {
                uiStateFlow.update { it.copy(pendingApproval = null) }
            }

            is ClientUpdate.TurnCompleted -> {
                val selectedThreadId = uiStateFlow.value.selectedThreadId
                if (selectedThreadId != null && (update.threadId == null || update.threadId == selectedThreadId)) {
                    openThread(selectedThreadId)
                } else {
                    refreshThreads()
                }
            }

            is ClientUpdate.Error -> {
                uiStateFlow.update { it.copy(errorMessage = update.message) }
            }
        }
    }

    private fun applyAssistantDelta(
        messages: List<ConversationMessage>,
        selectedThreadId: String?,
        update: ClientUpdate.AssistantDelta,
    ): List<ConversationMessage> {
        val threadId = update.threadId ?: selectedThreadId ?: return messages
        val existingIndex = messages.indexOfLast { message ->
            message.role == ConversationRole.ASSISTANT
                && message.isStreaming
                && (update.turnId == null || message.turnId == update.turnId)
        }
        if (existingIndex >= 0) {
            val updated = messages.toMutableList()
            val message = updated[existingIndex]
            updated[existingIndex] = message.copy(text = message.text + update.delta)
            return updated
        }
        return messages + ConversationMessage(
            id = update.itemId ?: UUID.randomUUID().toString(),
            threadId = threadId,
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = update.delta,
            createdAtEpochMs = System.currentTimeMillis(),
            turnId = update.turnId,
            itemId = update.itemId,
            isStreaming = true,
        )
    }

    private fun applyAssistantCompletion(
        messages: List<ConversationMessage>,
        selectedThreadId: String?,
        update: ClientUpdate.AssistantCompleted,
    ): List<ConversationMessage> {
        val threadId = update.threadId ?: selectedThreadId ?: return messages
        val existingIndex = messages.indexOfLast { message ->
            message.role == ConversationRole.ASSISTANT
                && (update.turnId == null || message.turnId == update.turnId)
        }
        if (existingIndex >= 0) {
            val updated = messages.toMutableList()
            updated[existingIndex] = updated[existingIndex].copy(
                text = update.text,
                isStreaming = false,
            )
            return updated
        }
        return messages + ConversationMessage(
            id = update.itemId ?: UUID.randomUUID().toString(),
            threadId = threadId,
            role = ConversationRole.ASSISTANT,
            kind = ConversationKind.CHAT,
            text = update.text,
            createdAtEpochMs = System.currentTimeMillis(),
            turnId = update.turnId,
            itemId = update.itemId,
            isStreaming = false,
        )
    }
}

private fun Uri.extractPairingPayload(): String? {
    return getQueryParameter("payload")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
