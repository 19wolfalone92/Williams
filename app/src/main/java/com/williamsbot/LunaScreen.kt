package com.williamsbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LunaScreen(padding: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember { AiForgeClient(context) }
    val scope = rememberCoroutineScope()

    var coreUrl by remember { mutableStateOf(api.coreUrl) }
    var apiToken by remember { mutableStateOf(api.apiToken) }
    var messages by remember { mutableStateOf(emptyList<LunaChatMessage>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connectionText by remember { mutableStateOf("Core ещё не проверен") }
    var coreMode by remember { mutableStateOf("FREE") }
    var agentCount by remember { mutableStateOf(0) }

    fun testCore() {
        scope.launch(Dispatchers.IO) {
            try {
                api.saveConnection(coreUrl, apiToken)
                val status = api.status()
                withContext(Dispatchers.Main) {
                    coreMode = status.lunaMode
                    agentCount = status.configuredAgents
                    connectionText =
                        "CORE ONLINE • ${status.lunaMode} • agents: ${status.configuredAgents}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionText = e.message ?: "Core недоступен"
                }
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return

        val previous = messages
        messages = previous + LunaChatMessage("user", text)
        input = ""
        busy = true

        scope.launch(Dispatchers.IO) {
            try {
                api.saveConnection(coreUrl, apiToken)
                val reply = api.ask(previous, text)
                withContext(Dispatchers.Main) {
                    messages = messages + LunaChatMessage(
                        "assistant",
                        reply.answer + "\n\n[" + reply.mode + " • " + reply.provider + "]"
                    )
                    coreMode = reply.mode
                    connectionText =
                        "Luna отвечает • " + reply.provider + " • " + reply.model
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages = messages + LunaChatMessage(
                        "assistant",
                        "Не удалось получить ответ: " +
                            (e.message ?: "ошибка Core")
                    )
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (api.coreUrl.isNotBlank() && api.apiToken.isNotBlank()) {
            testCore()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.surface)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = AppColors.violet)
                    Spacer(Modifier.width(8.dp))
                    Text("LUNA AI", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        coreMode,
                        color = if (coreMode == "FREE") AppColors.green else AppColors.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    "Отдельный AI-чат внутри APK. Provider API keys сюда не вводятся.",
                    color = AppColors.textMuted,
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = coreUrl,
                    onValueChange = { coreUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("AI-Forge Core URL (HTTPS)") }
                )

                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Core API token") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { testCore() },
                        enabled = !busy &&
                            coreUrl.isNotBlank() &&
                            apiToken.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("ПРОВЕРИТЬ CORE")
                    }

                    OutlinedButton(
                        onClick = { messages = emptyList() },
                        enabled = messages.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("ОЧИСТИТЬ")
                    }
                }

                Text(
                    connectionText,
                    color = if (connectionText.contains("ONLINE")) {
                        AppColors.green
                    } else {
                        AppColors.textMuted
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                if (agentCount > 0) {
                    Text(
                        "На Core настроено агентов: $agentCount. Для Luna выбран первый доступный provider.",
                        color = AppColors.textMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = AppColors.surface
                            )
                        ) {
                            Column(
                                Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Готово к диалогу.",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "В FREE режиме Core работает без provider key. Это безопасный fallback, а не коммерческая LLM. После настройки provider тот же чат переключится на реальную модель.",
                                    color = AppColors.textMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                items(messages) { item ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (item.role == "user") {
                                AppColors.surface2
                            } else {
                                AppColors.surface
                            }
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                if (item.role == "user") "Вы" else "Luna",
                                color = if (item.role == "user") {
                                    AppColors.primary
                                } else {
                                    AppColors.violet
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(item.content)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 5,
                placeholder = { Text("Напишите Luna…") }
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { send() },
                enabled = input.isNotBlank() && !busy,
                modifier = Modifier.size(52.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Отправить"
                    )
                }
            }
        }
    }
}
