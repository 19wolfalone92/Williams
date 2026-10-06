package com.williamsbot

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LunaChatMessage(
    val role: String,
    val content: String
)

data class LunaReply(
    val answer: String,
    val mode: String,
    val provider: String,
    val model: String
)

data class AiForgeStatus(
    val lunaMode: String,
    val configuredAgents: Int
)

class AiForgeClient(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "williams_ai_forge_secure",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val coreUrl: String
        get() = prefs.getString("core_url", "")?.trimEnd('/') ?: ""

    val apiToken: String
        get() = prefs.getString("api_token", "")?.trim() ?: ""

    fun saveConnection(url: String, token: String) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Core URL не задан" }
        val localhost =
            normalized.startsWith("http://127.0.0.1") ||
                normalized.startsWith("http://localhost")
        require(normalized.startsWith("https://") || localhost) {
            "Core URL должен использовать HTTPS; HTTP разрешён только для localhost"
        }
        require(token.trim().length >= 16) {
            "Core API token должен содержать минимум 16 символов"
        }
        prefs.edit()
            .putString("core_url", normalized)
            .putString("api_token", token.trim())
            .apply()
    }

    fun status(): AiForgeStatus {
        val json = JSONObject(request("GET", "/v1/status", null))
        val configured = json.optJSONObject("configured_agents")
        var count = 0
        if (configured != null) {
            configured.keys().forEach { key ->
                if (configured.optBoolean(key, false)) count++
            }
        }
        return AiForgeStatus(
            lunaMode = json.optString("luna_mode", "FREE"),
            configuredAgents = count
        )
    }

    fun ask(history: List<LunaChatMessage>, message: String): LunaReply {
        val historyJson = JSONArray()
        history.takeLast(20).forEach {
            historyJson.put(
                JSONObject()
                    .put("role", it.role)
                    .put("content", it.content)
            )
        }

        val body = JSONObject()
            .put("message", message.trim())
            .put("history", historyJson)
            .put(
                "context",
                JSONObject()
                    .put("surface", "williams-android")
                    .put("locale", "ru-RU")
                    .put("app_version", BuildConfig.VERSION_NAME)
            )
            .toString()

        val json = JSONObject(request("POST", "/v1/luna", body))
        return LunaReply(
            answer = json.optString("answer", "Пустой ответ Core"),
            mode = json.optString("mode", "FREE"),
            provider = json.optString("provider", "UNKNOWN"),
            model = json.optString("model", "UNKNOWN")
        )
    }

    private fun request(method: String, path: String, body: String?): String {
        require(coreUrl.isNotBlank()) {
            "Сначала укажи Core URL в настройках Luna"
        }

        val builder = Request.Builder()
            .url(coreUrl + path)
            .header("Authorization", "Bearer " + apiToken)

        val requestBody = body?.toRequestBody("application/json".toMediaType())
        val request = builder.method(
            method,
            if (method == "POST") requestBody else null
        ).build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).optString("detail")
                }.getOrNull().orEmpty()
                error(
                    "Core HTTP ${response.code}: " +
                        (detail.ifBlank { "запрос отклонён" })
                )
            }
            return text
        }
    }
}
