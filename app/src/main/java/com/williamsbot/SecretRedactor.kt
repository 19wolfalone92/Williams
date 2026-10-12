package com.williamsbot

/**
 * Redacts credential-like fields before audit data is persisted or surfaced.
 * It is deliberately conservative: ordinary order/fill fields remain intact.
 */
internal object SecretRedactor {
    private val querySecret = Regex(
        """(?i)([?&\s]|^)(signature|api[_-]?key|api[_-]?secret|secret|authorization|token|password|x-mbx-apikey)=([^&\s,}]+)"""
    )
    private val jsonSecret = Regex(
        """(?i)("(?:signature|api[_-]?key|api[_-]?secret|secret|authorization|token|password|x-mbx-apikey)"\s*:\s*)("[^"]*"|'[^']*'|[^&,}\s]+)"""
    )
    private val headerSecret = Regex(
        """(?im)(\b(?:authorization|x-mbx-apikey)\s*:\s*)[^\r\n]+"""
    )

    fun redact(value: String?): String? {
        if (value == null) return null
        val querySafe = querySecret.replace(value) { match ->
            match.groupValues[1] + match.groupValues[2] + "=[REDACTED]"
        }
        val jsonSafe = jsonSecret.replace(querySafe) { match ->
            match.groupValues[1] + "\"[REDACTED]\""
        }
        return headerSecret.replace(jsonSafe) { match ->
            match.groupValues[1] + "[REDACTED]"
        }
    }
}
