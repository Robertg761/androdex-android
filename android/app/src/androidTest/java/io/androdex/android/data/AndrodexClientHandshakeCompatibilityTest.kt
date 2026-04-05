package io.androdex.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.androdex.android.crypto.buildLegacyTranscriptBytesWithoutRecoveryFields
import io.androdex.android.crypto.buildTranscriptBytes
import io.androdex.android.crypto.encodeBase64
import io.androdex.android.crypto.generatePhoneIdentityState
import io.androdex.android.crypto.generateRecoveryPayload
import io.androdex.android.crypto.generateX25519PrivateKey
import io.androdex.android.crypto.randomNonce
import io.androdex.android.crypto.signEd25519
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndrodexClientHandshakeCompatibilityTest {
    @Test
    fun resolveVerifiedServerHelloSignature_acceptsLegacyBridgeTranscriptWithoutRecoveryFields() {
        val macIdentity = generatePhoneIdentityState()
        val phoneIdentity = generatePhoneIdentityState()
        val nextRecovery = generateRecoveryPayload(
            relay = "wss://relay.example/relay",
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            macIdentityPublicKey = macIdentity.phoneIdentityPublicKey,
            phoneDeviceId = phoneIdentity.phoneDeviceId,
        )
        val macEphemeralPublicKey = encodeBase64(generateX25519PrivateKey().generatePublicKey().encoded)
        val phoneEphemeralPublicKey = encodeBase64(generateX25519PrivateKey().generatePublicKey().encoded)
        val clientNonce = randomNonce()
        val serverNonce = randomNonce()

        val legacyTranscript = buildLegacyTranscriptBytesWithoutRecoveryFields(
            sessionId = "mac.51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            protocolVersion = 1,
            handshakeMode = "qr_bootstrap",
            keyEpoch = 1,
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            macIdentityPublicKey = macIdentity.phoneIdentityPublicKey,
            phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
            macEphemeralPublicKey = macEphemeralPublicKey,
            phoneEphemeralPublicKey = phoneEphemeralPublicKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            expiresAtForTranscript = 1234L,
        )
        val signature = signEd25519(macIdentity.phoneIdentityPrivateKey, legacyTranscript)

        val result = resolveVerifiedServerHelloSignature(
            expectedSessionId = "mac.51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            protocolVersion = 1,
            handshakeMode = "qr_bootstrap",
            keyEpoch = 1,
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            trustedPhoneDeviceId = "",
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            macIdentityPublicKey = macIdentity.phoneIdentityPublicKey,
            trustedRecoveryPublicKey = "",
            phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
            requestedNextRecoveryIdentityPublicKey = nextRecovery.recoveryIdentityPublicKey,
            bootstrapRecoveryRequested = true,
            macEphemeralPublicKey = macEphemeralPublicKey,
            phoneEphemeralPublicKey = phoneEphemeralPublicKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            expiresAtForTranscript = 1234L,
            macSignature = signature,
        )

        assertNotNull(result)
        assertFalse(result!!.bootstrapRecoveryAcceptedByBridge)
        assertTrue(result.transcriptBytes.contentEquals(legacyTranscript))
    }

    @Test
    fun resolveVerifiedServerHelloSignature_prefersCurrentTranscriptWhenRecoveryKeyIsSigned() {
        val macIdentity = generatePhoneIdentityState()
        val phoneIdentity = generatePhoneIdentityState()
        val nextRecovery = generateRecoveryPayload(
            relay = "wss://relay.example/relay",
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            macIdentityPublicKey = macIdentity.phoneIdentityPublicKey,
            phoneDeviceId = phoneIdentity.phoneDeviceId,
        )
        val macEphemeralPublicKey = encodeBase64(generateX25519PrivateKey().generatePublicKey().encoded)
        val phoneEphemeralPublicKey = encodeBase64(generateX25519PrivateKey().generatePublicKey().encoded)
        val clientNonce = randomNonce()
        val serverNonce = randomNonce()

        val currentTranscript = buildTranscriptBytes(
            sessionId = "mac.51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            protocolVersion = 1,
            handshakeMode = "qr_bootstrap",
            keyEpoch = 1,
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            trustedPhoneDeviceId = "",
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            macIdentityPublicKey = macIdentity.phoneIdentityPublicKey,
            trustedRecoveryPublicKey = "",
            phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
            nextRecoveryIdentityPublicKey = nextRecovery.recoveryIdentityPublicKey,
            macEphemeralPublicKey = macEphemeralPublicKey,
            phoneEphemeralPublicKey = phoneEphemeralPublicKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            expiresAtForTranscript = 1234L,
        )
        val signature = signEd25519(macIdentity.phoneIdentityPrivateKey, currentTranscript)

        val result = resolveVerifiedServerHelloSignature(
            expectedSessionId = "mac.51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            protocolVersion = 1,
            handshakeMode = "qr_bootstrap",
            keyEpoch = 1,
            macDeviceId = "51b5f04f-b19d-4fa7-b0ea-df5b31adb240",
            trustedPhoneDeviceId = "",
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            macIdentityPublicKey = macIdentity.phoneIdentityPublicKey,
            trustedRecoveryPublicKey = "",
            phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
            requestedNextRecoveryIdentityPublicKey = nextRecovery.recoveryIdentityPublicKey,
            bootstrapRecoveryRequested = true,
            macEphemeralPublicKey = macEphemeralPublicKey,
            phoneEphemeralPublicKey = phoneEphemeralPublicKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            expiresAtForTranscript = 1234L,
            macSignature = signature,
        )

        assertNotNull(result)
        assertTrue(result!!.bootstrapRecoveryAcceptedByBridge)
        assertTrue(result.transcriptBytes.contentEquals(currentTranscript))
    }
}
