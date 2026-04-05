package io.androdex.android.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.androdex.android.R
import io.androdex.android.ui.shared.LandingBackdrop
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexGroupedSurface
import io.androdex.android.ui.shared.remodexBottomSafeAreaPadding
import io.androdex.android.ui.state.FirstPairingOnboardingUiState
import io.androdex.android.ui.theme.RemodexTheme
import kotlinx.coroutines.delay

private const val OnboardingPageCount = 5
private const val CodexInstallStepIndex = 2
private val OnboardingContentMaxWidth = 420.dp

@Composable
internal fun FirstPairingOnboardingScreen(
    state: FirstPairingOnboardingUiState,
    onStartPairing: () -> Unit,
) {
    var currentPage by rememberSaveable { mutableStateOf(0) }
    var showCodexReminder by rememberSaveable { mutableStateOf(false) }
    val geometry = RemodexTheme.geometry

    ScaffoldLike(
        bottomBar = {
            OnboardingBottomBar(
                currentPage = currentPage,
                onBack = {
                    currentPage = (currentPage - 1).coerceAtLeast(0)
                },
                onContinue = {
                    if (currentPage == CodexInstallStepIndex) {
                        showCodexReminder = true
                        return@OnboardingBottomBar
                    }
                    if (currentPage < OnboardingPageCount - 1) {
                        currentPage += 1
                    } else {
                        onStartPairing()
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LandingBackdrop()
            AnimatedContent(
                targetState = currentPage,
                modifier = Modifier.fillMaxSize(),
                label = "firstPairingOnboardingPage",
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = geometry.pageHorizontalPadding,
                            vertical = geometry.pageVerticalPadding,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(geometry.spacing18),
                ) {
                    when (page) {
                        0 -> OnboardingWelcomePage()
                        1 -> OnboardingFeaturesPage()
                        2 -> OnboardingCommandPage(
                            stepNumber = 1,
                            icon = Icons.Outlined.Computer,
                            eyebrow = "STEP 1",
                            title = "Install Codex CLI",
                            description = "Codex is the agent that stays on your host. Androdex connects Android to that local runtime.",
                            command = state.codexInstallCommand,
                        )
                        3 -> OnboardingCommandPage(
                            stepNumber = 2,
                            icon = Icons.Default.Link,
                            eyebrow = "STEP 2",
                            title = "Install the bridge",
                            description = "Androdex adds the secure host bridge that prints the pairing QR and keeps reconnect working later.",
                            command = state.bridgeInstallCommand,
                        )
                        else -> OnboardingCommandPage(
                            stepNumber = 3,
                            icon = Icons.Default.QrCode2,
                            eyebrow = "STEP 3",
                            title = "Start pairing",
                            description = "Run this on your host. A fresh QR code will appear in the terminal, then Android can scan and connect.",
                            command = state.bridgeStartCommand,
                            footer = "If the QR scan is canceled, this step stays here so you can try again without repeating onboarding.",
                        )
                    }
                    Spacer(modifier = Modifier.height(96.dp))
                }
            }
        }
    }

    if (showCodexReminder) {
        AlertDialog(
            onDismissRequest = { showCodexReminder = false },
            title = {
                Text(
                    text = "Install Codex CLI First",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = "Copy and run `${state.codexInstallCommand}` on your host before moving on. Androdex will not work until Codex CLI is installed and available in your PATH.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                RemodexButton(
                    onClick = {
                        showCodexReminder = false
                        currentPage = (currentPage + 1).coerceAtMost(OnboardingPageCount - 1)
                    },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text("Continue anyway")
                }
            },
            dismissButton = {
                RemodexButton(
                    onClick = { showCodexReminder = false },
                    style = RemodexButtonStyle.Secondary,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text("Stay here")
                }
            },
        )
    }
}

@Composable
private fun OnboardingWelcomePage() {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Surface(
        modifier = Modifier.size(88.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.selectedRowFill.copy(alpha = 0.86f),
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "Androdex",
            modifier = Modifier.size(88.dp),
        )
    }

    Column(
        modifier = Modifier.widthIn(max = OnboardingContentMaxWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
    ) {
        Text(
            text = "Pair Androdex to your host",
            style = MaterialTheme.typography.displayLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Keep Codex running on your computer, then use Android as the secure remote control for threads, approvals, tools, and project switching.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }

    CalloutCard(
        icon = Icons.Outlined.Lock,
        title = "Pair once",
        description = "The first QR scan bootstraps trust. After that, reconnects can reuse the same trusted host without rescanning.",
    )
}

@Composable
private fun OnboardingFeaturesPage() {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = OnboardingContentMaxWidth),
        cornerRadius = geometry.cornerComposer,
        tonalColor = colors.subtleGlassTint,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing24,
                vertical = geometry.spacing24,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
        ) {
            Text(
                text = "Host-first by design",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
            )
            Text(
                text = "Androdex does not run Codex on the phone. The runtime, git actions, and file edits stay on your computer while Android controls the paired session remotely.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
            )

            FeatureHighlight(
                icon = Icons.Outlined.Computer,
                title = "Host-local runtime",
                description = "Threads, tools, and repo actions stay anchored to the right machine and workspace.",
            )
            FeatureHighlight(
                icon = Icons.Outlined.Lock,
                title = "Trusted reconnects",
                description = "Android can reconnect through the same trusted host before falling back to a fresh QR or manual payload.",
            )
            FeatureHighlight(
                icon = Icons.Default.Link,
                title = "Relay-compatible access",
                description = "The relay is only the transport. Your code and credentials stay on the host you paired.",
            )
        }
    }
}

@Composable
private fun OnboardingCommandPage(
    stepNumber: Int,
    icon: ImageVector,
    eyebrow: String,
    title: String,
    description: String,
    command: String,
    footer: String? = null,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = OnboardingContentMaxWidth),
        cornerRadius = geometry.cornerComposer,
        tonalColor = colors.subtleGlassTint,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing24,
                vertical = geometry.spacing24,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing18),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = colors.selectedRowFill.copy(alpha = 0.86f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = colors.secondarySurface.copy(alpha = 0.82f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing8)) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            }

            CommandCard(command = command)

            footer?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    currentPage: Int,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.groupedBackground.copy(alpha = 0.96f))
            .remodexBottomSafeAreaPadding()
            .padding(
                start = geometry.pageHorizontalPadding,
                end = geometry.pageHorizontalPadding,
                top = geometry.spacing16,
                bottom = geometry.spacing18,
            ),
        verticalArrangement = Arrangement.spacedBy(geometry.spacing16),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(OnboardingPageCount) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .background(
                            color = if (index == currentPage) colors.textPrimary else colors.textSecondary.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(999.dp),
                        )
                        .then(
                            if (index == currentPage) {
                                Modifier
                                    .widthIn(min = 24.dp)
                                    .height(8.dp)
                            } else {
                                Modifier
                                    .size(8.dp)
                            }
                        ),
                )
            }
        }

        if (currentPage > 0) {
            RemodexButton(
                onClick = onBack,
                style = RemodexButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = geometry.spacing20, vertical = geometry.spacing12),
            ) {
                Text(
                    text = "Back",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }

        RemodexButton(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = geometry.spacing20, vertical = geometry.spacing14),
        ) {
            Text(
                text = when (currentPage) {
                    0 -> "Get Started"
                    1 -> "Set Up"
                    OnboardingPageCount - 1 -> "Scan QR Code"
                    else -> "Continue"
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun CommandCard(command: String) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    val context = LocalContext.current
    var didCopy by remember(command) { mutableStateOf(false) }

    if (didCopy) {
        LaunchedEffect(command, didCopy) {
            delay(1_500)
            didCopy = false
        }
    }

    RemodexGroupedSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.secondarySurface.copy(alpha = 0.88f),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = geometry.spacing16,
                vertical = geometry.spacing16,
            ),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing12),
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )

            RemodexButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("androdex-command", command))
                    didCopy = true
                },
                style = RemodexButtonStyle.Secondary,
                contentPadding = PaddingValues(horizontal = geometry.spacing18, vertical = geometry.spacing12),
            ) {
                Text(
                    text = if (didCopy) "Copied" else "Copy command",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun CalloutCard(
    icon: ImageVector,
    title: String,
    description: String,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = OnboardingContentMaxWidth),
        cornerRadius = geometry.cornerComposer,
        tonalColor = colors.secondarySurface.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = geometry.spacing18,
                vertical = geometry.spacing16,
            ),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                color = colors.accentBlue.copy(alpha = 0.14f),
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.accentBlue,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing4)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun FeatureHighlight(
    icon: ImageVector,
    title: String,
    description: String,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = geometry.cornerLarge,
        tonalColor = colors.secondarySurface.copy(alpha = 0.62f),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = geometry.spacing14,
                vertical = geometry.spacing12,
            ),
            horizontalArrangement = Arrangement.spacedBy(geometry.spacing12),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                color = colors.selectedRowFill.copy(alpha = 0.88f),
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(geometry.spacing2)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ScaffoldLike(
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        containerColor = Color.Transparent,
        bottomBar = bottomBar,
        content = content,
    )
}
