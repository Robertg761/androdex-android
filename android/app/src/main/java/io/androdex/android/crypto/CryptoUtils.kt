package io.androdex.android.crypto

import android.util.Base64
import io.androdex.android.model.PhoneIdentityState
import io.androdex.android.model.RecoveryPayload
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val secureProtocolVersion = 1
const val pairingQrVersion = 3
const val secureHandshakeTag = "androdex-e2ee-v1"
const val trustedSessionResolveTag = "androdex-trusted-session-resolve-v1"
private const val secureHandshakeLabel = "client-auth"
const val secureClockSkewToleranceSeconds = 60L

private val secureRandom = SecureRandom()

fun generatePhoneIdentityState(): PhoneIdentityState {
    val privateKey = Ed25519PrivateKeyParameters(secureRandom)
    val publicKey = privateKey.generatePublicKey()
    return PhoneIdentityState(
        phoneDeviceId = UUID.randomUUID().toString(),
        phoneIdentityPrivateKey = encodeBase64(privateKey.encoded),
        phoneIdentityPublicKey = encodeBase64(publicKey.encoded),
    )
}

fun generateRecoveryPayload(
    relay: String,
    macDeviceId: String,
    macIdentityPublicKey: String,
    phoneDeviceId: String,
): RecoveryPayload {
    val privateKey = Ed25519PrivateKeyParameters(secureRandom)
    val publicKey = privateKey.generatePublicKey()
    return RecoveryPayload(
        version = 1,
        relay = relay,
        macDeviceId = macDeviceId,
        macIdentityPublicKey = macIdentityPublicKey,
        phoneDeviceId = phoneDeviceId,
        recoveryIdentityPrivateKey = encodeBase64(privateKey.encoded),
        recoveryIdentityPublicKey = encodeBase64(publicKey.encoded),
    )
}

fun buildTranscriptBytes(
    sessionId: String,
    protocolVersion: Int,
    handshakeMode: String,
    keyEpoch: Int,
    macDeviceId: String,
    trustedPhoneDeviceId: String = "",
    phoneDeviceId: String,
    macIdentityPublicKey: String,
    trustedRecoveryPublicKey: String = "",
    phoneIdentityPublicKey: String,
    nextRecoveryIdentityPublicKey: String = "",
    macEphemeralPublicKey: String,
    phoneEphemeralPublicKey: String,
    clientNonce: ByteArray,
    serverNonce: ByteArray,
    expiresAtForTranscript: Long,
): ByteArray {
    return buildList {
        add(encodeLengthPrefixedUtf8(secureHandshakeTag))
        add(encodeLengthPrefixedUtf8(sessionId))
        add(encodeLengthPrefixedUtf8(protocolVersion.toString()))
        add(encodeLengthPrefixedUtf8(handshakeMode))
        add(encodeLengthPrefixedUtf8(keyEpoch.toString()))
        add(encodeLengthPrefixedUtf8(macDeviceId))
        add(encodeLengthPrefixedUtf8(trustedPhoneDeviceId))
        add(encodeLengthPrefixedUtf8(phoneDeviceId))
        add(encodeLengthPrefixedBuffer(decodeBase64(macIdentityPublicKey)))
        add(encodeLengthPrefixedBuffer(decodeBase64(trustedRecoveryPublicKey)))
        add(encodeLengthPrefixedBuffer(decodeBase64(phoneIdentityPublicKey)))
        add(encodeLengthPrefixedBuffer(decodeBase64(nextRecoveryIdentityPublicKey)))
        add(encodeLengthPrefixedBuffer(decodeBase64(macEphemeralPublicKey)))
        add(encodeLengthPrefixedBuffer(decodeBase64(phoneEphemeralPublicKey)))
        add(encodeLengthPrefixedBuffer(clientNonce))
        add(encodeLengthPrefixedBuffer(serverNonce))
        add(encodeLengthPrefixedUtf8(expiresAtForTranscript.toString()))
    }.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
}

fun buildClientAuthTranscript(transcriptBytes: ByteArray): ByteArray {
    return transcriptBytes + encodeLengthPrefixedUtf8(secureHandshakeLabel)
}

fun buildTrustedSessionResolveTranscript(
    macDeviceId: String,
    phoneDeviceId: String,
    phoneIdentityPublicKey: String,
    nonce: String,
    timestamp: Long,
): ByteArray {
    return buildList {
        add(encodeLengthPrefixedUtf8(trustedSessionResolveTag))
        add(encodeLengthPrefixedUtf8(macDeviceId))
        add(encodeLengthPrefixedUtf8(phoneDeviceId))
        add(encodeLengthPrefixedBuffer(decodeBase64(phoneIdentityPublicKey)))
        add(encodeLengthPrefixedUtf8(nonce))
        add(encodeLengthPrefixedUtf8(timestamp.toString()))
    }.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
}

fun buildTrustedSessionRecoverTranscript(
    macDeviceId: String,
    phoneDeviceId: String,
    recoveryIdentityPublicKey: String,
    nonce: String,
    timestamp: Long,
): ByteArray {
    return buildList {
        add(encodeLengthPrefixedUtf8("androdex-trusted-session-recover-v1"))
        add(encodeLengthPrefixedUtf8(macDeviceId))
        add(encodeLengthPrefixedUtf8(phoneDeviceId))
        add(encodeLengthPrefixedBuffer(decodeBase64(recoveryIdentityPublicKey)))
        add(encodeLengthPrefixedUtf8(nonce))
        add(encodeLengthPrefixedUtf8(timestamp.toString()))
    }.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
}

fun generateX25519PrivateKey(): X25519PrivateKeyParameters = X25519PrivateKeyParameters(secureRandom)

fun signEd25519(privateKeyBase64: String, payload: ByteArray): ByteArray {
    val signer = Ed25519Signer()
    signer.init(true, Ed25519PrivateKeyParameters(decodeBase64(privateKeyBase64), 0))
    signer.update(payload, 0, payload.size)
    return signer.generateSignature()
}

fun verifyEd25519(publicKeyBase64: String, payload: ByteArray, signature: ByteArray): Boolean {
    return runCatching {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(decodeBase64(publicKeyBase64), 0))
        signer.update(payload, 0, payload.size)
        signer.verifySignature(signature)
    }.getOrDefault(false)
}

fun deriveSharedSecret(
    privateKey: X25519PrivateKeyParameters,
    publicKeyBase64: String,
): ByteArray {
    val secret = ByteArray(32)
    privateKey.generateSecret(X25519PublicKeyParameters(decodeBase64(publicKeyBase64), 0), secret, 0)
    return secret
}

fun hkdfSha256(
    inputKeyMaterial: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    outputLength: Int,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val pseudoRandomKey = mac.doFinal(inputKeyMaterial)

    val result = ByteArray(outputLength)
    var generated = 0
    var previous = ByteArray(0)
    var counter = 1

    while (generated < outputLength) {
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
        expandMac.update(previous)
        expandMac.update(info)
        expandMac.update(counter.toByte())
        previous = expandMac.doFinal()
        val chunkSize = minOf(previous.size, outputLength - generated)
        System.arraycopy(previous, 0, result, generated, chunkSize)
        generated += chunkSize
        counter += 1
    }

    return result
}

fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    val encrypted = cipher.doFinal(plaintext)
    val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)
    val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
    return ciphertext to tag
}

fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return cipher.doFinal(ciphertext + tag)
}

fun secureNonce(sender: String, counter: Int): ByteArray {
    val nonce = ByteArray(12)
    nonce[0] = if (sender == "mac") 1 else 2
    var value = counter.toLong()
    for (index in 11 downTo 1) {
        nonce[index] = (value and 0xff).toByte()
        value = value shr 8
    }
    return nonce
}

fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

fun fingerprint(publicKeyBase64: String): String {
    return sha256(decodeBase64(publicKeyBase64))
        .joinToString("") { "%02x".format(it) }
        .take(12)
        .uppercase()
}

fun randomNonce(bytes: Int = 32): ByteArray = ByteArray(bytes).also(secureRandom::nextBytes)

fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

private fun encodeLengthPrefixedUtf8(value: String): ByteArray = encodeLengthPrefixedBuffer(value.toByteArray(Charsets.UTF_8))

private fun encodeLengthPrefixedBuffer(value: ByteArray): ByteArray {
    val length = byteArrayOf(
        ((value.size ushr 24) and 0xff).toByte(),
        ((value.size ushr 16) and 0xff).toByte(),
        ((value.size ushr 8) and 0xff).toByte(),
        (value.size and 0xff).toByte(),
    )
    return length + value
}
