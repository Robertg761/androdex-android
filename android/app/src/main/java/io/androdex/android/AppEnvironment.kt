package io.androdex.android

object AppEnvironment {
    val defaultRelayUrl: String
        get() = BuildConfig.ANDRODEX_DEFAULT_RELAY_URL.trim()

    val hasDefaultRelay: Boolean
        get() = defaultRelayUrl.isNotEmpty()
}
