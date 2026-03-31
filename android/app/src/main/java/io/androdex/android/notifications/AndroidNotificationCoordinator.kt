package io.androdex.android.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.androdex.android.AppEnvironment
import io.androdex.android.model.ConnectionStatus
import io.androdex.android.model.TurnTerminalState
import io.androdex.android.data.AndrodexRepositoryContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface NotificationCoordinator {
    fun onAppForegrounded()
    fun onAppBackgrounded()
    fun onPermissionPromptStarted()
    fun onPermissionResult(granted: Boolean)
    fun shouldRequestPermission(hasSavedPairing: Boolean): Boolean
    fun syncRegistration(connectionStatus: ConnectionStatus, hasSavedPairing: Boolean)
    fun notifyRunCompletion(threadId: String, turnId: String?, title: String, terminalState: TurnTerminalState)
}

object NoopNotificationCoordinator : NotificationCoordinator {
    override fun onAppForegrounded() = Unit
    override fun onAppBackgrounded() = Unit
    override fun onPermissionPromptStarted() = Unit
    override fun onPermissionResult(granted: Boolean) = Unit
    override fun shouldRequestPermission(hasSavedPairing: Boolean): Boolean = false
    override fun syncRegistration(connectionStatus: ConnectionStatus, hasSavedPairing: Boolean) = Unit
    override fun notifyRunCompletion(threadId: String, turnId: String?, title: String, terminalState: TurnTerminalState) = Unit
}

class AndroidNotificationCoordinator(
    private val context: Context,
    private val repository: AndrodexRepositoryContract,
    private val scope: CoroutineScope,
) : NotificationCoordinator {
    private val appContext = context.applicationContext
    private val store = AndrodexNotificationStore(appContext)
    private var lastRegistrationSignature: String? = null

    init {
        AndrodexNotificationPlatform.ensureRunCompletionChannel(appContext)
    }

    override fun onAppForegrounded() {
        AndrodexAppProcessState.isForeground = true
        AndrodexFirebaseSupport.fetchToken(appContext, store::savePushToken)
    }

    override fun onAppBackgrounded() {
        AndrodexAppProcessState.isForeground = false
    }

    override fun onPermissionPromptStarted() {
        store.markPromptedForPermission()
    }

    override fun onPermissionResult(granted: Boolean) {
        scope.launch {
            runCatching {
                syncManagedRegistration(force = true)
            }
        }
    }

    override fun shouldRequestPermission(hasSavedPairing: Boolean): Boolean {
        if (!hasSavedPairing || store.hasPromptedForPermission()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    override fun syncRegistration(connectionStatus: ConnectionStatus, hasSavedPairing: Boolean) {
        if (!hasSavedPairing || connectionStatus != ConnectionStatus.CONNECTED) {
            return
        }
        scope.launch {
            AndrodexFirebaseSupport.fetchToken(appContext, store::savePushToken)
            runCatching {
                syncManagedRegistration(force = false)
            }
        }
    }

    override fun notifyRunCompletion(
        threadId: String,
        turnId: String?,
        title: String,
        terminalState: TurnTerminalState,
    ) {
        if (AndrodexAppProcessState.isForeground || !areAlertsEnabled()) {
            return
        }
        val body = when (terminalState) {
            TurnTerminalState.COMPLETED -> "Response ready"
            TurnTerminalState.FAILED -> "Run failed"
            TurnTerminalState.STOPPED -> return
        }
        AndrodexNotificationPlatform.showRunCompletionNotification(
            context = appContext,
            notification = AndrodexRunCompletionNotification(
                threadId = threadId,
                turnId = turnId,
                title = title.ifBlank { "Conversation" },
                body = body,
            ),
        )
    }

    private suspend fun syncManagedRegistration(force: Boolean) {
        val token = store.loadPushToken()
        if (token.isNullOrBlank()) {
            return
        }

        val alertsEnabled = areAlertsEnabled()
        val authorizationStatus = notificationAuthorizationStatus()
        val signature = listOf(
            token,
            alertsEnabled.toString(),
            authorizationStatus,
            AppEnvironment.appEnvironment,
        ).joinToString("|")
        if (!force && signature == lastRegistrationSignature) {
            return
        }

        runCatching {
            repository.registerPushNotifications(
                deviceToken = token,
                alertsEnabled = alertsEnabled,
                authorizationStatus = authorizationStatus,
                appEnvironment = AppEnvironment.appEnvironment,
            )
        }.onSuccess {
            lastRegistrationSignature = signature
        }
    }

    private fun areAlertsEnabled(): Boolean {
        val notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        val permissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return notificationAlertsEnabled(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = permissionGranted,
            notificationsEnabled = notificationsEnabled,
        )
    }

    private fun notificationAuthorizationStatus(): String {
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
                "authorized"
            } else {
                "denied"
            }
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED -> "authorized"
            store.hasPromptedForPermission() -> "denied"
            else -> "not_determined"
        }
    }
}
