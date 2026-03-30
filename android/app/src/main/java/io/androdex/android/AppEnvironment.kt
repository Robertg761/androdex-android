package io.androdex.android

object AppEnvironment {
    data class FcmConfiguration(
        val applicationId: String,
        val projectId: String,
        val apiKey: String,
        val gcmSenderId: String,
    )

    val defaultRelayUrl: String
        get() = BuildConfig.ANDRODEX_DEFAULT_RELAY_URL.trim()

    val hasDefaultRelay: Boolean
        get() = defaultRelayUrl.isNotEmpty()

    val appEnvironment: String
        get() = if (BuildConfig.DEBUG) "development" else "production"

    val fcmConfiguration: FcmConfiguration?
        get() {
            val applicationId = BuildConfig.ANDRODEX_FCM_APPLICATION_ID.trim()
            val projectId = BuildConfig.ANDRODEX_FCM_PROJECT_ID.trim()
            val apiKey = BuildConfig.ANDRODEX_FCM_API_KEY.trim()
            val gcmSenderId = BuildConfig.ANDRODEX_FCM_GCM_SENDER_ID.trim()
            if (
                applicationId.isEmpty()
                || projectId.isEmpty()
                || apiKey.isEmpty()
                || gcmSenderId.isEmpty()
            ) {
                return null
            }
            return FcmConfiguration(
                applicationId = applicationId,
                projectId = projectId,
                apiKey = apiKey,
                gcmSenderId = gcmSenderId,
            )
        }

    val hasFcmConfiguration: Boolean
        get() = fcmConfiguration != null

    const val projectUrl: String = "https://github.com/Robertg761/androdex"
    const val issuesUrl: String = "https://github.com/Robertg761/androdex/issues"
    const val privacyPolicyUrl: String = "https://github.com/Robertg761/androdex/blob/main/Docs/PRIVACY_POLICY.md"
    const val bridgeUpdateCommand: String = "npm install -g androdex@latest"
    const val bridgeStartCommand: String = "androdex up"
    val appVersionLabel: String
        get() = BuildConfig.VERSION_NAME
}
