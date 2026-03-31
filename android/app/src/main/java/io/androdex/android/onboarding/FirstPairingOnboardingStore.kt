package io.androdex.android.onboarding

import android.content.Context

interface FirstPairingOnboardingStore {
    fun hasSeenFirstPairingOnboarding(): Boolean

    fun markFirstPairingOnboardingSeen()
}

class SharedPreferencesFirstPairingOnboardingStore(
    context: Context,
) : FirstPairingOnboardingStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun hasSeenFirstPairingOnboarding(): Boolean {
        return prefs.getBoolean(KEY_FIRST_PAIRING_ONBOARDING_SEEN, false)
    }

    override fun markFirstPairingOnboardingSeen() {
        prefs.edit().putBoolean(KEY_FIRST_PAIRING_ONBOARDING_SEEN, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "androdex_onboarding"
        const val KEY_FIRST_PAIRING_ONBOARDING_SEEN = "first_pairing_onboarding_seen"
    }
}

class InMemoryFirstPairingOnboardingStore(
    initialSeen: Boolean = false,
) : FirstPairingOnboardingStore {
    private var hasSeen = initialSeen

    override fun hasSeenFirstPairingOnboarding(): Boolean = hasSeen

    override fun markFirstPairingOnboardingSeen() {
        hasSeen = true
    }
}
