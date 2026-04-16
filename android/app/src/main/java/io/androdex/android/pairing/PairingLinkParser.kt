package io.androdex.android.pairing

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class PairingLink(
    val pairingUrl: String,
    val origin: String,
    val displayLabel: String,
)

internal fun extractPairingPayloadFromUriString(rawInput: String): String? {
    parsePairingLink(rawInput)?.let { return it.pairingUrl }

    val trimmed = rawInput.trim().takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.trim()?.lowercase() ?: return null
    if (scheme != "androdex" || uri.host?.trim()?.lowercase() != "pair") {
        return null
    }

    return extractUriParameter(uri.rawQuery, "payload")
}

internal fun parsePairingLink(rawInput: String): PairingLink? {
    val trimmed = rawInput.trim().takeIf { it.isNotEmpty() } ?: return null
    val pairingUrl = macNativeJsonPayloadToPairingUrl(trimmed) ?: trimmed
    val uri = runCatching { URI(pairingUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme?.trim()?.lowercase() ?: return null

    if (scheme == "androdex" && uri.host?.trim()?.lowercase() == "pair") {
        val payload = extractUriParameter(uri.rawQuery, "payload") ?: return null
        return parsePairingLink(payload)
    }

    if (scheme != "http" && scheme != "https") {
        return null
    }

    val origin = normalizeOrigin(uri) ?: return null
    val normalizedPath = uri.rawPath.orEmpty().trimEnd('/')
    if (!normalizedPath.endsWith("/pair")) {
        return null
    }

    if (resolvePairingToken(uri) == null) {
        return null
    }

    return PairingLink(
        pairingUrl = pairingUrl,
        origin = origin,
        displayLabel = uri.host?.trim().orEmpty().ifEmpty { origin },
    )
}

internal fun normalizeOrigin(rawUrl: String): String? {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
    return normalizeOrigin(uri)
}

internal fun isAllowedAppUrl(rawUrl: String, pairedOrigin: String): Boolean {
    val candidateOrigin = normalizeOrigin(rawUrl) ?: return false
    return candidateOrigin == normalizeOrigin(pairedOrigin)
}

internal fun shouldPersistAppUrl(rawUrl: String, pairedOrigin: String): Boolean {
    if (!isAllowedAppUrl(rawUrl, pairedOrigin)) {
        return false
    }

    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
    val normalizedPath = uri.rawPath.orEmpty().trimEnd('/')
    return !normalizedPath.endsWith("/pair")
}

private fun normalizeOrigin(uri: URI): String? {
    val scheme = uri.scheme?.trim()?.lowercase() ?: return null
    val authority = uri.rawAuthority?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return "$scheme://$authority"
}

private fun macNativeJsonPayloadToPairingUrl(rawInput: String): String? {
    val json = runCatching { JSONObject(rawInput) }.getOrNull() ?: return null
    val transport = json.optString("transport").trim().lowercase()
    val kind = json.optString("kind").trim().lowercase()
    if (transport != "mac-native" && kind != "mac-native") {
        return null
    }

    val rawBaseUrl = json.optString("httpBaseUrl")
        .trim()
        .ifEmpty { json.optString("baseUrl").trim() }
        .ifEmpty { json.optString("url").trim() }
    val credential = json.optString("credential").trim()
    if (rawBaseUrl.isEmpty() || credential.isEmpty()) {
        return null
    }

    val baseUrl = normalizeMacNativeMirrorBaseUrl(rawBaseUrl) ?: return null
    val encodedCredential = URLEncoder.encode(credential, Charsets.UTF_8.name())
    return "$baseUrl/pair?token=$encodedCredential"
}

private fun normalizeMacNativeMirrorBaseUrl(rawBaseUrl: String): String? {
    val uri = runCatching { URI(rawBaseUrl.trim()) }.getOrNull() ?: return null
    val scheme = when (uri.scheme?.trim()?.lowercase()) {
        "http" -> "http"
        "https" -> "https"
        "ws" -> "http"
        "wss" -> "https"
        else -> return null
    }
    val authority = uri.rawAuthority?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val path = uri.rawPath
        ?.trim()
        .orEmpty()
        .trimEnd('/')
    return buildString {
        append(scheme)
        append("://")
        append(authority)
        append(path)
    }
}

private fun resolvePairingToken(uri: URI): String? {
    extractUriParameter(uri.rawQuery, "token")?.let { return it }
    return extractUriParameter(uri.rawFragment, "token")
}

private fun extractUriParameter(rawQuery: String?, name: String): String? {
    val query = rawQuery?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return query
        .split('&')
        .asSequence()
        .mapNotNull { entry ->
            val separatorIndex = entry.indexOf('=')
            val rawKey = if (separatorIndex >= 0) entry.substring(0, separatorIndex) else entry
            val rawValue = if (separatorIndex >= 0) entry.substring(separatorIndex + 1) else ""
            val decodedKey = URLDecoder.decode(rawKey, Charsets.UTF_8).trim()
            if (decodedKey != name) {
                null
            } else {
                URLDecoder.decode(rawValue, Charsets.UTF_8).trim().ifEmpty { null }
            }
        }
        .firstOrNull()
}
