package com.williamsbot

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun applyCockpitOrientation(activity: Activity, choice: String? = null) {
    val prefs = activity.getSharedPreferences("williams_display", Context.MODE_PRIVATE)
    val selected = choice ?: prefs.getString("orientation", "AUTO") ?: "AUTO"
    if (choice != null) prefs.edit().putString("orientation", selected).apply()
    val orientation = when (selected) {
        "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
    if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
}

private fun Context.cockpitActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}

@Composable
internal fun CockpitOrientationMenu() {
    val activity = LocalContext.current.cockpitActivity()
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = activity != null) {
            Icon(Icons.Filled.ScreenRotation, contentDescription = "Ориентация экрана")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("AUTO" to "Авто: по настройке телефона",
                "LANDSCAPE" to "Горизонтально", "PORTRAIT" to "Вертикально").forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    activity?.let { applyCockpitOrientation(it, value) }
                })
            }
        }
    }
}

/** Values get the full line on a phone and two bounded columns on a wide pane. */
@Composable
internal fun ResponsiveCockpitField(
    label: String, value: String,
    color: androidx.compose.ui.graphics.Color = AppColors.textMuted
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 480.dp && fontScale < 1.5f) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), color = AppColors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
                Text(value, Modifier.weight(1.25f), color = color,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
                Text(value, Modifier.fillMaxWidth(), color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun AdaptiveCockpitScaffold(
    tab: Int, titles: List<String>, destinations: List<Int>, icons: List<ImageVector>,
    refreshing: Boolean, onTab: (Int) -> Unit, onRefresh: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val window = LocalWindowInfo.current.containerSize
    val landscape = window.width > window.height
    fun selected(target: Int) = if (target == 6) tab in listOf(3, 4, 5, 6) else tab == target
    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            Surface(color = AppColors.background) {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp, vertical = if (landscape) 2.dp else 6.dp)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("WILLIAMS BOT 2.1", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(if (landscape) "Futures Demo · H1" else "Futures Demo · 10 пар · LONG / SHORT",
                            style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
                    }
                    CockpitOrientationMenu()
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить рынок",
                            tint = if (refreshing) AppColors.amber else AppColors.primary)
                    }
                }
            }
        },
        bottomBar = {
            if (!landscape) NavigationBar(containerColor = AppColors.surface) {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = selected(destinations[index]),
                        onClick = { onTab(destinations[index]) },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) })
                }
            }
        }
    ) { padding ->
        // Keep the page at the same composition location in both layouts.
        // Rotation changes the side navigation, not the page's saveable identity.
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (landscape) {
                Column(
                    Modifier.width(84.dp).fillMaxHeight().background(AppColors.surface)
                        .verticalScroll(rememberScrollState()).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    titles.forEachIndexed { index, title ->
                        val active = selected(destinations[index])
                        Surface(color = if (active) AppColors.violet.copy(alpha = .18f) else AppColors.surface,
                            shape = RoundedCornerShape(14.dp)) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .selectable(selected = active, role = Role.Tab) { onTab(destinations[index]) }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(icons[index], contentDescription = title,
                                    tint = if (active) AppColors.violet else AppColors.textMuted)
                                Text(title, style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    color = if (active) AppColors.violet else AppColors.textMuted)
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content(PaddingValues(0.dp)) }
        }
    }
}

/** A read-only chart surface; opening/rotation dispatches no trading commands. */
@Composable
internal fun CockpitChartDialog(title: String, onDismiss: () -> Unit, chart: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = AppColors.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    CockpitOrientationMenu()
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть полный график")
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
                    item { chart() }
                }
            }
        }
    }
}
