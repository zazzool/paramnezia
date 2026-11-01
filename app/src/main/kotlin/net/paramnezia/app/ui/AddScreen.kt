package net.paramnezia.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.paramnezia.app.App
import net.paramnezia.app.data.Cloud
import net.paramnezia.app.data.Servers
import net.paramnezia.core.amnezia.AmneziaQr
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ServerManager
import net.paramnezia.deploy.SshException
import kotlinx.coroutines.launch

@Composable
fun AddScreen(app: App, modifier: Modifier, pending: String?, onConsumed: () -> Unit, toast: (String) -> Unit, onDone: () -> Unit, onConnect: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val assembler = remember { AmneziaQr.Assembler() }
    var qrProgress by remember { mutableStateOf<String?>(null) }

    /** [connectAfter]: settings opened from a link or file connect at once, as the person expects. */
    fun runImport(connectAfter: Boolean = false, block: suspend () -> Servers.Imported) {
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    toast(when (it) {
                        Servers.Imported.JoinedFleet -> "Устройство добавлено во флот"
                        is Servers.Imported.Profiles -> "Добавлено: ${it.names.joinToString()}"
                    })
                    text = ""
                    onDone()
                    if (connectAfter) onConnect()
                }
                .onFailure { toast(it.message ?: "Не удалось импортировать") }
            busy = false
        }
    }

    LaunchedEffect(pending) {
        if (pending != null) { onConsumed(); runImport(connectAfter = true) { app.servers.import(pending) } }
    }

    lateinit var scanLauncher: androidx.activity.result.ActivityResultLauncher<ScanOptions>
    scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val chunk = AmneziaQr.parseChunk(contents)
        if (chunk == null) { runImport { app.servers.import(contents) }; return@rememberLauncherForActivityResult }
        // AmneziaVPN shows long keys as a series of QR codes: keep scanning until all have been seen.
        val payload = assembler.offer(chunk)
        if (payload != null) { qrProgress = null; runImport { app.servers.importQrPayload(payload) } }
        else { qrProgress = "QR ${assembler.received} из ${assembler.expected}"; scanLauncher.launch(scanOptions()) }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val content = uri?.let { runCatching { context.contentResolver.openInputStream(it)?.use { s -> s.reader().readText() } }.getOrNull() }
        if (content != null) runImport { app.servers.import(content) }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ключ или конфиг", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Подходят ключи vpn:// из AmneziaVPN, приглашения во флот и конфиги WireGuard/AmneziaWG.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("vpn://…") }, minLines = 3, maxLines = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && text.isNotBlank(), onClick = { runImport { app.servers.import(text) } }) { Text("Добавить") }
            OutlinedButton(enabled = !busy, onClick = { clipboard.getText()?.text?.let { text = it } }) { Text("Вставить") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { scanLauncher.launch(scanOptions()) }) { Text("Сканировать QR") }
            OutlinedButton(enabled = !busy, onClick = { fileLauncher.launch(arrayOf("*/*")) }) { Text("Из файла") }
        }
        qrProgress?.let { Text(it) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        DeployCard(app, toast, onDone)
    }
}

private fun scanOptions() = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setBeepEnabled(false)
    setOrientationLocked(false)
    setPrompt("Наведите камеру на QR-код")
}

/** One-click VPS: SSH access in, a working AmneziaWG server out. */
@Composable
private fun DeployCard(app: App, toast: (String) -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<ServerManager.Step?>(null) }
    var running by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }

    if (buying) CloudBuyDialog(app, onDismiss = { buying = false }, toast = toast) { n, ssh, onStep -> app.servers.deploy(n, ssh, onStep) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Новый сервер у хостера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Cloudzy или Timeweb Cloud: приложение само закажет VPS по API и установит на него AmneziaWG. Нужен только токен API хостера.",
                style = MaterialTheme.typography.bodySmall)
            Button(enabled = !running, onClick = { buying = true }) { Text("Купить сервер у хостера") }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Свой сервер на VPS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Нужен чистый VPS с Linux и доступ по SSH. Установка займёт 2–5 минут; сервер совместим с AmneziaVPN.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("IP или домен") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it.trim() }, Modifier.weight(2f), label = { Text("Логин") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Порт") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("или приватный SSH-ключ") }, maxLines = 4)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название (необязательно)") }, singleLine = true)
            step?.let { Text(stepLabel(it)) }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                enabled = !running && host.isNotBlank() && (password.isNotEmpty() || key.isNotBlank()),
                onClick = {
                    running = true
                    scope.launch {
                        val creds = SshCredentials(host, port.toIntOrNull() ?: 22, user.ifBlank { "root" }, password, key.trim())
                        runCatching { app.servers.deploy(name, creds) { s -> step = s } }
                            .onSuccess { toast("Сервер ${it.name} готов"); onDone() }
                            .onFailure { toast(deployError(it)) }
                        running = false
                    }
                },
            ) { Text("Развернуть") }
        }
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
