package net.paramnezia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.paramnezia.app.App
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.ota.Updater
import net.paramnezia.core.ota.Release
import java.text.DateFormat
import java.util.Date

@Composable
fun AppUpdates(app: App) {
    val available by app.updater.status.collectAsState()
    val state by app.repo.state.collectAsState()
    val release = (available as? Updater.Status.Available)?.release
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val lastCheck = remember(state.lastUpdateCheck) {
        state.lastUpdateCheck.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
    }
    UpdateCard(
        BuildConfig.VERSION_NAME,
        app.updater.enabled,
        release,
        lastCheck,
        checking,
        message,
        onDownload = {
            message = if (app.updater.openDownload()) null else "Не удалось открыть ссылку на обновление."
        },
        onCheck = {
            checking = true
            message = null
            scope.launch {
                try {
                    val checked = app.updater.checkNow()
                    message = when {
                        !checked -> "Не удалось подтвердить наличие обновлений. Попробуйте ещё раз."
                        app.updater.status.value is Updater.Status.Available -> null
                        else -> "Доступных обновлений нет."
                    }
                } finally {
                    checking = false
                }
            }
        },
    )
}

@Composable
internal fun UpdateCard(
    installed: String,
    enabled: Boolean,
    release: Release?,
    lastCheck: String?,
    checking: Boolean,
    message: String?,
    onDownload: () -> Unit,
    onCheck: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (release != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (release != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PnIcons.Refresh, contentDescription = null)
                Text("Обновления", style = MaterialTheme.typography.titleMedium)
            }
            Text("Установлена версия $installed", style = MaterialTheme.typography.bodyMedium)
            if (!enabled) {
                Text("Проверка обновлений недоступна в этой сборке.", style = MaterialTheme.typography.bodySmall)
            } else {
                if (release != null) {
                    Text("Версия ${release.versionName} доступна", style = MaterialTheme.typography.titleLarge)
                    ReleaseNotes(release)
                    Button(onClick = onDownload) { Text("Скачать обновление") }
                }
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (lastCheck != null) {
                    Text("Последняя успешная проверка · $lastCheck", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !checking, onClick = onCheck) {
                    Text(if (checking) "Проверяем…" else "Проверить обновления")
                }
            }
        }
    }
}

@Composable
fun UpdateBadge(app: App, modifier: Modifier = Modifier) {
    val status by app.updater.status.collectAsState()
    val release = (status as? Updater.Status.Available)?.release ?: return
    var show by remember { mutableStateOf(false) }
    var downloadFailed by remember { mutableStateOf(false) }
    Surface(
        onClick = { downloadFailed = false; show = true },
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text("Обновить", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Версия ${release.versionName} доступна") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Установлена версия ${BuildConfig.VERSION_NAME}")
                    ReleaseNotes(release)
                    if (downloadFailed) Text("Не удалось открыть ссылку на обновление.")
                }
            },
            confirmButton = {
                Button(onClick = { if (app.updater.openDownload()) show = false else downloadFailed = true }) {
                    Text("Скачать обновление")
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Позже") } },
        )
    }
}

@Composable
private fun ReleaseNotes(release: Release) {
    val lines = remember(release.notes) {
        release.notes.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("- ")) "• " + it.removePrefix("- ") else it }
            .take(24).toList()
    }
    if (lines.isEmpty()) return
    Text("Что изменилось", style = MaterialTheme.typography.labelLarge)
    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
