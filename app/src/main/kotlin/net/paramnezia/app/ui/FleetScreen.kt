package net.paramnezia.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
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
import net.paramnezia.app.App
import net.paramnezia.app.data.StoredLink
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.profile.SshCredentials
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
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    /** Standalone server to make a separate account on (no fleet). */
    var accountOn by remember { mutableStateOf<StoredProfile?>(null) }

    // Which links have been used: asked only while some are still open.
    LaunchedEffect(Unit) {
        if (state.links.any { !it.used && it.expiresAt > System.currentTimeMillis() }) runCatching { app.links.refresh() }
    }
    // Get the servers ready for links while the admin is still looking at this screen.
    LaunchedEffect(state.fleet?.adminSeed != null, state.profiles.count { it.ssh != null }) {
        if (state.fleet?.adminSeed != null || state.profiles.any { it.ssh != null }) runCatching { app.links.prewarm() }
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
            Text("Флот объединяет ваши серверы и устройства. Устройства сами получают новые серверы и ваши настройки, " +
                "а при блокировке сервера переключаются на резервный.")
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
            LinkList(state.links, busy, onOpen = { link = it }) { l -> work("Ссылка отозвана") { app.links.revoke(l) } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }

        val manifest = remember(fleet.manifest) { app.fleet.manifest() }
        val isAdmin = fleet.adminSeed != null
        val me = WgKey.fromBase64(fleet.deviceKey).publicKey().toBase64()
        Text(if (isAdmin) "Флот (вы администратор)" else "Флот", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Версия конфигурации: ${manifest?.version ?: "—"}", style = MaterialTheme.typography.bodySmall)
        if (fleet.lastSync > 0) {
            val via = when (fleet.lastSyncVia) { "tunnel" -> "с сервера"; "github" -> "через GitHub"; "tor" -> "через Tor (Orbot)"; else -> "с зеркала" }
            Text("Проверено ${android.text.format.DateUtils.getRelativeTimeSpanString(fleet.lastSync)}, $via",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (!isAdmin && manifest?.policy != null) {
            Text("Обход российских сайтов и блокировку рекламы настраивает администратор флота, они обновляются сами.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

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
        if (isAdmin) LinkList(state.links, busy, onOpen = { link = it }) { l -> work("Ссылка отозвана") { app.links.revoke(l) } }

        if (isAdmin) {
            Text("Доставка настроек", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Мои настройки на всех устройствах", fontWeight = FontWeight.Bold)
                            Text("Обход российского, блокировка рекламы, списки «через VPN» и «напрямую»",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.Switch(fleet.sharePolicy, enabled = !busy, onCheckedChange = { on ->
                            work(if (on) "Устройства получат ваши настройки" else "Устройства оставят свои настройки") {
                                app.fleet.sharePolicy(state.settings, on)
                            }
                        })
                    }
                }
            }
            ReserveChannel(fleet.gistToken != null, fleet.gistError, busy,
                onEnable = { token -> work("Резервный канал включён") { app.fleet.setReserveChannel(token) } },
                onDisable = { work("Резервный канал отключён") { app.fleet.clearReserveChannel() } },
                openTokenPage = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_TOKEN_PAGE)))
                })
        }

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
                    Text("Ссылка одноразовая. На Android человек скачает Paramnezia и одной кнопкой на той же странице подключится; на iPhone получит ключ для AmneziaVPN.",
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
                    SelectionContainer { Text(l.url, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Paramnezia", l.url))
                        toast("Ссылка скопирована")
                    }) { Text("Скопировать") }
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

/** GitHub page for a classic token with only the `gist` scope, prefilled. */
private const val GITHUB_TOKEN_PAGE = "https://github.com/settings/tokens/new?scopes=gist&description=Paramnezia"

/**
 * The reserve channel: a secret GitHub gist with the encrypted manifest. Devices read it when no
 * fleet server is reachable, so they learn about new servers even after every old one was banned.
 */
@Composable
private fun ReserveChannel(on: Boolean, error: String?, busy: Boolean, onEnable: (String) -> Unit, onDisable: () -> Unit, openTokenPage: () -> Unit) {
    var token by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Резервный канал: GitHub" + if (on) " · включён" else "", fontWeight = FontWeight.Bold)
            if (on) {
                Text("Если заблокируют все серверы, устройства найдут новые настройки на GitHub. Там лежит только " +
                    "зашифрованная и подписанная конфигурация: GitHub не может ни прочитать, ни подменить её.",
                    style = MaterialTheme.typography.bodySmall)
                if (error != null) {
                    Text("Не удалось обновить: $error. Вставьте новый токен.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("Сейчас устройства узнают о новых серверах только через уже известные. Включите канал, чтобы флот " +
                    "пережил блокировку всех серверов сразу. Нужен один раз токен GitHub с правом «gist»; срок действия " +
                    "выберите «No expiration».", style = MaterialTheme.typography.bodySmall)
            }
            if (!on || error != null) {
                TextButton(onClick = openTokenPage) { Text("1. Получить токен на GitHub") }
                OutlinedTextField(token, { token = it.trim() }, label = { Text("2. Вставьте токен") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && token.length >= 20, onClick = { onEnable(token); token = "" }) { Text("Включить") }
            }
            if (on) TextButton(enabled = !busy, onClick = onDisable) { Text("Отключить") }
        }
    }
}

/** Links handed out, with whether they have been used yet. */
@Composable
private fun LinkList(links: List<StoredLink>, busy: Boolean, onOpen: (StoredLink) -> Unit, onRevoke: (StoredLink) -> Unit) {
    if (links.isEmpty()) return
    Text("Ссылки-приглашения", style = MaterialTheme.typography.titleMedium)
    val now = System.currentTimeMillis()
    for (l in links.take(10)) {
        val open = !l.used && l.expiresAt > now
        Row(Modifier.fillMaxWidth().clickable(enabled = open) { onOpen(l) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.name)
                Text(when { l.used -> "использована"; !open -> "истекла"; else -> "ждёт" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            if (open) {
                TextButton(onClick = { onOpen(l) }) { Text("Отправить") }
                TextButton(enabled = !busy, onClick = { onRevoke(l) }) { Text("Отозвать") }
            }
        }
    }
}
