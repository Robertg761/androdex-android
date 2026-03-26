package io.androdex.android.ui.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.ConversationKind
import io.androdex.android.model.ConversationMessage
import io.androdex.android.model.ConversationRole
import io.androdex.android.ui.state.BusyUiState
import io.androdex.android.ui.state.ConnectionBannerUiState

@Composable
internal fun StatusCapsule(
    state: ConnectionBannerUiState,
    modifier: Modifier = Modifier,
) {
    val (dotColor, label) = when (state.status) {
        ConnectionStatus.CONNECTED -> Color(0xFF34D399) to "Connected"
        ConnectionStatus.CONNECTING -> Color(0xFFFBBF24) to "Connecting"
        ConnectionStatus.HANDSHAKING -> Color(0xFFFBBF24) to "Handshaking"
        ConnectionStatus.RETRYING_SAVED_PAIRING -> Color(0xFFF59E0B) to "Waiting For Host"
        ConnectionStatus.RECONNECT_REQUIRED -> Color(0xFFF87171) to "Repair Pairing Required"
        ConnectionStatus.UPDATE_REQUIRED -> Color(0xFFF87171) to "Update Required"
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Disconnected"
    }
    val guidance = when (state.status) {
        ConnectionStatus.RETRYING_SAVED_PAIRING -> {
            "Saved pairing is still trusted. We'll retry automatically when the host or relay comes back."
        }
        ConnectionStatus.RECONNECT_REQUIRED -> {
            "Saved pairing needs attention. Reconnect from saved pairing or scan a fresh QR code if trust changed."
        }
        ConnectionStatus.UPDATE_REQUIRED -> updateRequiredGuidance(state.detail)
        else -> null
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    },
                    label = "statusLabel",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            state.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            guidance?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (state.detail.isNullOrBlank()) 4.dp else 2.dp),
                )
            }
            state.fingerprint?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "Host: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun BusyIndicator(state: BusyUiState) {
    AnimatedVisibility(
        visible = state.isVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            )
            state.label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun AgentActivityBanner(messages: List<ConversationMessage>) {
    val isStreaming = messages.any { it.isStreaming }
    val lastSystemMessage = messages.lastOrNull { it.role == ConversationRole.SYSTEM }

    val activityText = when {
        !isStreaming && lastSystemMessage == null -> null
        isStreaming -> "Agent is writing a response..."
        lastSystemMessage?.kind == ConversationKind.FILE_CHANGE -> {
            val fileName = lastSystemMessage.filePath
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
            if (fileName != null) "Edited $fileName" else "Edited files"
        }
        lastSystemMessage?.kind == ConversationKind.COMMAND -> {
            "Ran: ${lastSystemMessage.command?.take(40) ?: "command"}"
        }
        lastSystemMessage?.kind == ConversationKind.THINKING -> "Thinking..."
        lastSystemMessage?.kind == ConversationKind.PLAN -> "Planning..."
        else -> null
    }

    AnimatedVisibility(
        visible = isStreaming,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = activityText ?: "Working...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun updateRequiredGuidance(detail: String?): String {
    val normalized = detail?.lowercase()
        ?: return "Update the Android app or host bridge, then reconnect with the saved pairing."
    return when {
        normalized.contains("latest compatible androdex mobile app") -> {
            "This host bridge needs a newer Android app build. Update the Android app, then reconnect with the saved pairing."
        }
        normalized.contains("bridge and mobile client are not using the same secure transport version") -> {
            "The Android app and host bridge are out of sync. Update whichever side is older, then reconnect with the saved pairing."
        }
        normalized.contains("bridge version mismatch")
            || normalized.contains("different secure transport version") -> {
            "This saved pairing reached a host bridge with a different secure transport version. Update the host bridge or Android app, then reconnect."
        }
        else -> "Update the Android app or host bridge, then reconnect with the saved pairing."
    }
}
