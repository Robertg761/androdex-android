package io.androdex.android.transport.macnative

import java.net.URI

internal fun createMacNativeServerTarget(httpBaseUrl: String): MacNativeServerTarget {
    val normalizedHttpBaseUrl = normalizeMacNativeHttpBaseUrl(httpBaseUrl)
    return MacNativeServerTarget(
        httpBaseUrl = normalizedHttpBaseUrl,
        wsBaseUrl = resolveMacNativeWebSocketBaseUrl(normalizedHttpBaseUrl),
    )
}

internal fun normalizeMacNativeHttpBaseUrl(rawValue: String): String {
    val trimmed = rawValue.trim()
    require(trimmed.isNotEmpty()) { "HTTP base URL is required." }

    val uri = URI(trimmed)
    val scheme = uri.scheme?.lowercase() ?: error("HTTP base URL must include a scheme.")
    require(scheme == "http" || scheme == "https") {
        "HTTP base URL must use http or https."
    }

    val host = uri.host?.trim()?.lowercase() ?: error("HTTP base URL must include a host.")
    require(host.isNotEmpty()) { "HTTP base URL must include a host." }

    val normalizedPath = uri.path
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "/" }
        ?.trimEnd('/')

    return URI(
        scheme,
        uri.userInfo,
        host,
        uri.port,
        normalizedPath,
        null,
        null,
    ).toASCIIString()
}

internal fun resolveMacNativeWebSocketBaseUrl(httpBaseUrl: String): String {
    val normalizedHttpBaseUrl = normalizeMacNativeHttpBaseUrl(httpBaseUrl)
    val uri = URI(normalizedHttpBaseUrl)
    val wsScheme = when (uri.scheme.lowercase()) {
        "http" -> "ws"
        "https" -> "wss"
        else -> error("Unsupported HTTP scheme ${uri.scheme}.")
    }
    return URI(
        wsScheme,
        uri.userInfo,
        uri.host,
        uri.port,
        uri.path,
        null,
        null,
    ).toASCIIString()
}
