package io.androdex.android.pairing

import java.net.URI
import java.net.URLDecoder

data class PairingLink(
    val pairingUrl: String,
    val origin: String,
    val displayLabel: String,
)

internal fun extractPairingPayloadFromUriString(rawInput: String): String? {
    return parsePairingLink(rawInput)?.pairingUrl
}

internal fun parsePairingLink(rawInput: String): PairingLink? {
    val trimmed = rawInput.trim().takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
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
        pairingUrl = trimmed,
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
