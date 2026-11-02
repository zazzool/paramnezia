package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import net.paramnezia.app.App
import net.paramnezia.app.data.Cloud
import net.paramnezia.app.data.Servers
import net.paramnezia.core.amnezia.AmneziaQr
import net.paramnezia.deploy.ServerManager
import net.paramnezia.deploy.SshException
import kotlinx.coroutines.launch

@Composable
fun AddScreen(app: App, modifier: Modifier, pending: String?, onConsumed: () -> Unit, toast: (String) -> Unit, onDone: () -> Unit, onConnect: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val platform = LocalUiPlatform.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val assembler = remember { AmneziaQr.Assembler() }
    var qrProgress by remember { mutableStateOf<String?>(null) }
    /** The key field is shown: asked for with «Ввести», or a paste that did not work. */
    var manual by remember { mutableStateOf(false) }
    var typeNow by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }

    /**
     * [connectAfter]: settings opened from a link or file connect at once, as the person expects.
     * [source]: the text that was imported; on failure it stays in the field to be fixed.
     */
    fun runImport(connectAfter: Boolean = false, source: String? = null, block: suspend () -> Servers.Imported) {
        busy = true; keyError = null
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    toast(when (it) {
                        Servers.Imported.JoinedFleet -> "Устройство добавлено в семью"
                        is Servers.Imported.Profiles -> "Добавлено: ${it.names.joinToString()}"
                    })
                    text = ""; manual = false
                    onDone()
                    if (connectAfter) onConnect()
                }
                .onFailure {
                    val msg = it.message ?: "Не удалось импортировать"
                    if (source != null) { text = source; manual = true; typeNow = false; keyError = msg } else toast(msg)
                }
            busy = false
        }
    }

    LaunchedEffect(pending) {
        if (pending != null) { onConsumed(); runImport(connectAfter = true) { app.servers.import(pending) } }
    }

    var scanAgain by remember { mutableStateOf(0) }
    val scan = platform.rememberQrScanner { contents ->
        val chunk = AmneziaQr.parseChunk(contents)
        if (chunk == null) { runImport { app.servers.import(contents) }; return@rememberQrScanner }
        // AmneziaVPN shows long keys as a series of QR codes: keep scanning until all have been seen.
        val payload = assembler.offer(chunk)
        if (payload != null) { qrProgress = null; runImport { app.servers.importQrPayload(payload) } }
        else { qrProgress = "QR ${assembler.received} из ${assembler.expected}"; scanAgain++ }
    }
    // The next code of a series: opened again once the result of the previous one is handled.
    LaunchedEffect(scanAgain) { if (scanAgain > 0) scan() }
    val pickFile = platform.rememberFilePicker { content -> runImport { app.servers.import(content) } }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.M, vertical = Space.S),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Добавить", "Ключ, который вам прислали, или свой сервер")

        // 1. Someone sent a key: the most common case, one tap from the clipboard.
        SectionTitle("Мне прислали ключ")
        ActionTile(Glyph.Paste, "Вставить ключ", "vpn://, приглашение или конфиг из буфера", primary = true) {
            if (busy) return@ActionTile
            val clip = clipboard.getText()?.text?.trim()
            if (clip.isNullOrEmpty()) { toast("Буфер обмена пуст: скопируйте ключ в мессенджере"); return@ActionTile }
            runImport(source = clip) { app.servers.import(clip) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallTile(Glyph.Qr, platform.qrLabel, Modifier.weight(1f), enabled = !busy) { scan() }
            SmallTile(Glyph.File, "Файл", Modifier.weight(1f), enabled = !busy) { pickFile() }
            SmallTile(Glyph.Keyboard, "Ввести", Modifier.weight(1f), selected = manual, enabled = !busy) {
                manual = !manual; typeNow = manual; keyError = null
            }
        }
        qrProgress?.let { Hint(it, Glyph.Qr, MaterialTheme.colorScheme.primary) }
        AnimatedVisibility(manual) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasteField(
                    text, { text = it; keyError = null }, "Ключ или конфиг",
                    placeholder = "vpn://… или [Interface]…", singleLine = false, minLines = 3, maxLines = 6, mono = true,
                    supporting = keyError ?: "Ключи AmneziaVPN, приглашения в семью, конфиги WireGuard и AmneziaWG",
                    isError = keyError != null, enabled = !busy, startTyping = typeNow,
                    onEmptyClipboard = { toast("Буфер обмена пуст") },
                )
                Button(
                    enabled = !busy && text.isNotBlank(),
                    onClick = { val t = text; runImport(source = t) { app.servers.import(t) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Добавить") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        // 2. An admin: a server of their own, bought in the app or an existing VPS.
        Spacer(Modifier.height(Space.S))
        SectionTitle("Свой сервер")
        DeployCard(app, toast, onDone)
        Spacer(Modifier.height(Space.M))
    }
}

/** Own server: bought from a hoster through its API, or an existing VPS over SSH. */
@Composable
private fun DeployCard(app: App, toast: (String) -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val form = remember { SshFormState() }
    var step by remember { mutableStateOf<ServerManager.Step?>(null) }
    var running by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    var vps by remember { mutableStateOf(false) }

    if (buying) CloudBuyDialog(app, onDismiss = { buying = false }, toast = toast) { n, ssh, onStep -> app.servers.deploy(n, ssh, onStep) }
    // Hoster prices are ready before the tap (cached copy at once, refreshed when older than 10 min).
    LaunchedEffect(Unit) { if (app.repo.current.cloud.tokens.isNotEmpty()) app.cloud.prefetch() }

    Group {
        GroupRow(
            "Купить у хостера", subtitle = "В одно нажатие: Cloudzy, Timeweb, Vultr, Hetzner, DigitalOcean · 3–8 минут",
            icon = Glyph.Cloud, onClick = { if (!running) buying = true },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
    ExpandTile(Glyph.Server, "Свой VPS", "Есть IP и пароль от хостера: установка 2–5 минут", vps, onToggle = { vps = !vps }) {
        SshFields(form, enabled = !running, toast = toast)
        if (running || step != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                step?.let { Text(stepLabel(it), style = MaterialTheme.typography.bodyMedium) }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Button(
            enabled = !running && form.ready,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                running = true
                scope.launch {
                    runCatching { app.servers.deploy(form.name, form.credentials()) { s -> step = s } }
                        .onSuccess { toast("Сервер ${it.name} готов"); form.clear(); step = null; onDone() }
                        .onFailure { toast(deployError(it)) }
                    running = false
                }
            },
        ) { Text(if (running) "Устанавливаю…" else "Развернуть сервер") }
        Text("Совместим с AmneziaVPN. Пароль хранится зашифрованным только на этом ${LocalUiPlatform.current.deviceWhere}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun stepLabel(s: ServerManager.Step) = Cloud.stepLabel(s)

private fun deployError(e: Throwable): String = when ((e as? SshException)?.code) {
    "auth_failed" -> "Неверный логин, пароль или ключ"
    "unreachable" -> "Сервер не отвечает по SSH"
    "host_key_changed" -> "Ключ сервера изменился с прошлого раза: возможна подмена"
    "no_package_manager" -> "Эта ОС сервера не поддерживается (нужен Debian, Ubuntu, CentOS или Fedora)"
    "sudo_password_required" -> "Нужен root или sudo без пароля"
    "docker_not_running", "docker_build_failed", "docker_run_failed" -> "Docker на сервере не запустился"
    "interface_not_up" -> "Туннель на сервере не поднялся"
    else -> e.message ?: "Ошибка установки"
}
