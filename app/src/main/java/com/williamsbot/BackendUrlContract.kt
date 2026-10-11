package com.williamsbot

import java.net.URI

/**
 * URL policy for the optional dashboard backend and the device-local runtime.
 * Local HTTP is permitted only for the exact loopback listener. Remote control
 * APIs must use a structurally valid HTTPS URL.
 */
object BackendUrlContract {
    fun normalize(value: String): String = value.trim().trimEnd('/')

    fun isLocalRuntimeUrl(value: String): Boolean =
        runCatching {
            val normalized = normalize(value)
            val uri = URI(normalized)
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host?.lowercase() in setOf("127.0.0.1", "localhost") &&
                uri.port == 18080 &&
                uri.rawUserInfo == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        }.getOrDefault(false)

    fun isSecureRemoteUrl(value: String): Boolean =
        runCatching {
            val uri = URI(normalize(value))
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        }.getOrDefault(false)
}
