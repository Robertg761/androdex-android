package io.androdex.android.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import io.androdex.android.AppEnvironment

internal object AndrodexFirebaseSupport {
    fun ensureInitialized(context: Context): Boolean {
        if (!AppEnvironment.hasFcmConfiguration) {
            return false
        }
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            return true
        }

        val config = AppEnvironment.fcmConfiguration ?: return false
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(config.applicationId)
                .setApiKey(config.apiKey)
                .setProjectId(config.projectId)
                .setGcmSenderId(config.gcmSenderId)
                .build()
        )
        return true
    }

    fun fetchToken(
        context: Context,
        onSuccess: (String) -> Unit,
    ) {
        if (!ensureInitialized(context)) {
            return
        }

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            val normalized = token?.trim().orEmpty()
            if (normalized.isNotEmpty()) {
                onSuccess(normalized)
            }
        }
    }
}
