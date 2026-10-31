package io.paramnezia.app.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.paramnezia.app.App
import io.paramnezia.app.data.StoredLink
import io.paramnezia.app.data.StoredProfile
import io.paramnezia.core.config.WgQuick
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.profile.SshCredentials
import kotlinx.coroutines.launch

/**
 * The fleet: this owner's servers and trusted devices. Devices follow the admin's manifest on their
 * own; the admin device manages it here.
 */
@Composable
fun FleetScreen(app: App, modifier: Modifier, toast: (String) -> Unit) {
    val state by app.repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf<String?>(null) }
    var newDevice by remember { mutableStateOf(false) }
    var newServer by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<StoredLink?>(null) }
    /** Standalone server to make a separate account on (no fleet). */
    var accountOn by remember { mutableStateOf<StoredProfile?>(null) }

    // Which links have been used: asked only while some are still open.
    LaunchedEffect(Unit) {
        if (state.links.any { !it.used && it.expiresAt > System.currentTimeMillis() }) runCatching { app.links.refresh() }
    }

    fun work(done: String? = null, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }.onSuccess { done?.let(toast) }.onFailure { toast(it.message ?: "Ошибка") }
            busy = false
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val fleet = state.fleet
        if (fleet == null) {
            Text("Флот", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Флот объединяет ваши серверы и устройства. Устройства сами получают новые конфиги, переключаются на " +
                "резервный сервер при блокировке и обмениваются сведениями друг с другом без центрального сервера.")
            val managed = state.profiles.filter { it.ssh != null }
            if (managed.isEmpty()) {
                Text("Чтобы создать флот, сначала разверните свой сервер на вкладке «Добавить» или импортируйте ключ с полным доступом.",
                    style = MaterialTheme.typography.bodySmall)
            }
            for (p in managed) {
                Button(enabled = !busy, onClick = {
                    work("Флот создан") { app.fleet.create(p.name, WgQuick.parse(p.conf), p.ssh!!, Build.MODEL) }
                }) { Text("Создать флот на «${p.name}»") }
            }
            if (managed.isNotEmpty()) {
                Text("Или раздайте доступ без флота: каждому своя одноразовая ссылка и свой ключ.", style = MaterialTheme.typography.bodySmall)
                for (p in managed) {
                    OutlinedButton(enabled = !busy, onClick = { accountOn = p }) { Text("Ссылка для нового пользователя «${p.name}»") }
                }
            }
            LinkList(state.links, busy) { l -> work("Ссылка отозвана") { app.links.revoke(l) } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }

        val manifest = remember(fleet.manifest) { app.fleet.manifest() }
        val isAdmin = fleet.adminSeed != null
        val me = WgKey.fromBase64(fleet.deviceKey).publicKey().toBase64()
        Text(if (isAdmin) "Флот (вы администратор)" else "Флот", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Версия конфигурации: ${manifest?.version ?: "—"}", style = MaterialTheme.typography.bodySmall)

        Text("Серверы", style = MaterialTheme.typography.titleMedium)
        manifest?.servers?.forEachIndexed { i, s ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (i == 0) " · основной" else " · резервный", fontWeight = FontWeight.Bold)
                        Text("${s.host}:${s.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (isAdmin && i > 0) {
                        TextButton(enabled = !busy, onClick = { work("${s.name} теперь основной") { app.fleet.promote(s.id) } }) { Text("Сделать основным") }
                    }
                    if (isAdmin && manifest.servers.size > 1) {
                        TextButton(enabled = !busy, onClick = { work("Сервер удалён") { app.fleet.removeServer(s.id) } }) { Text("Удалить") }
                    }
                }
            }
        }
        if (isAdmin) OutlinedButton(enabled = !busy, onClick = { newServer = true }) { Text("Добавить сервер (VPS)") }

        Text("Устройства", style = MaterialTheme.typography.titleMedium)
        manifest?.devices?.forEach { d ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (d.publicKey == me) " · это устройство" else "", fontWeight = FontWeight.Bold)
                        Text(d.address, style = MaterialTheme.typography.bodySmall)
                    }
                    if (isAdmin && d.publicKey != me) {
                        TextButton(enabled = !busy, onClick = { work("Устройство отключено") { app.fleet.removeDevice(d.publicKey) } }) { Text("Отключить") }
                    }
                }
            }
        }
        if (isAdmin) Button(enabled = !busy, onClick = { newDevice = true }) { Text("Пригласить устройство") }
        if (isAdmin) LinkList(state.links, busy) { l -> work("Ссылка отозвана") { app.links.revoke(l) } }

        OutlinedButton(enabled = !busy, onClick = {
            work { toast(if (app.fleet.sync()) "Получена новая конфигурация" else "Конфигурация актуальна") }
        }) { Text("Проверить обновления конфигурации") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    if (newDevice) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDevice = false },
            title = { Text("Новое устройство") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Название, например «Телефон мамы»") }, singleLine = true)
                    Text("Ссылка одноразовая. На Android по ней скачается Paramnezia, уже настроенная; на iPhone ключ для AmneziaVPN.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    newDevice = false
                    work { link = app.links.forFleetDevice(name.trim()) }
                }) { Text("Создать ссылку") }
            },
            dismissButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    newDevice = false
                    work { invite = app.fleet.addDevice(name.trim()) }
                }) { Text("QR-код") }
            },
        )
    }

    accountOn?.let { p ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { accountOn = null },
            title = { Text("Новый пользователь") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Имя, например «Вика»") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    accountOn = null
                    work { link = app.links.forProfile(p, name.trim()) }
                }) { Text("Создать ссылку") }
            },
            dismissButton = { TextButton(onClick = { accountOn = null }) { Text("Отмена") } },
        )
    }

    link?.let { l ->
        AlertDialog(
            onDismissRequest = { link = null },
            title = { Text("Ссылка для «${l.name}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(l.url, fontWeight = FontWeight.Bold)
                    Text("Сработает один раз и действует 7 дней. Отправьте её в личном сообщении: кто откроет первым, тот и получит доступ.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, l.url), "Отправить ссылку"))
                }) { Text("Отправить") }
            },
            dismissButton = { TextButton(onClick = { link = null }) { Text("Готово") } },
        )
    }

    invite?.let { key ->
        AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text("Приглашение") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyQr(key)
                    Text("Отсканируйте в Paramnezia на новом устройстве. AmneziaVPN тоже примет этот ключ, но без автопереключения.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key), "Отправить ключ"))
                }) { Text("Отправить") }
            },
            dismissButton = { TextButton(onClick = { invite = null }) { Text("Готово") } },
        )
    }

    if (newServer) {
        var host by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var sshKey by remember { mutableStateOf("") }
        var master by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { newServer = false },
            title = { Text("Новый сервер") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AmneziaWG установится автоматически, все устройства флота получат к нему доступ.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("IP или домен (логин root)") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(sshKey, { sshKey = it }, label = { Text("или SSH-ключ") }, maxLines = 3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(master, { master = it })
                        Text("Сразу сделать основным")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = host.isNotBlank() && (password.isNotEmpty() || sshKey.isNotBlank()), onClick = {
                    newServer = false
                    work("Сервер добавлен во флот") { app.fleet.addServer(host, SshCredentials(host, password = password, privateKey = sshKey.trim()), master) {} }
                }) { Text("Развернуть") }
            },
            dismissButton = { TextButton(onClick = { newServer = false }) { Text("Отмена") } },
        )
    }
}

/** Links handed out, with whether they have been used yet. */
@Composable
private fun LinkList(links: List<StoredLink>, busy: Boolean, onRevoke: (StoredLink) -> Unit) {
    if (links.isEmpty()) return
    Text("Ссылки-приглашения", style = MaterialTheme.typography.titleMedium)
    val now = System.currentTimeMillis()
    for (l in links.take(10)) {
        val open = !l.used && l.expiresAt > now
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.name)
                Text(when { l.used -> "использована"; !open -> "истекла"; else -> "ждёт" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            if (open) TextButton(enabled = !busy, onClick = { onRevoke(l) }) { Text("Отозвать") }
        }
    }
}
