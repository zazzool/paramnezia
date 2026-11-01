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
import net.paramnezia.app.data.CloudServerRef
import net.paramnezia.app.data.StoredLink
import net.paramnezia.app.fleet.Links
import net.paramnezia.deploy.cloud.CloudProviders
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
    var buying by remember { mutableStateOf(false) }
    /** A server bought from the app was just removed from the fleet: offer to delete it at the provider. */
    var boughtRemoved by remember { mutableStateOf<CloudServerRef?>(null) }
    var link by remember { mutableStateOf<StoredLink?>(null) }
    /** People just added in one go, with what to send each of them. */
    var created by remember { mutableStateOf<List<Links.Created>?>(null) }
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
                Text("Или раздайте доступ без флота: каждому свой ключ, ссылкой или файлом, можно сразу нескольким.", style = MaterialTheme.typography.bodySmall)
                for (p in managed) {
                    OutlinedButton(enabled = !busy, onClick = { accountOn = p }) { Text("Новые пользователи «${p.name}»") }
                }
            }
            if (managed.isNotEmpty()) {
                FamilyPage(state.pages[managed.first().ssh!!.host], busy,
                    onCreate = { work { app.links.familyPage() } },
                    onShare = { Share.text(context, familyMessage(it), "Отправить страницу") },
                    onOpen = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) })
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
                        TextButton(enabled = !busy, onClick = {
                            val bought = app.cloud.serverAt(s.host)
                            work("Сервер удалён из флота") { app.fleet.removeServer(s.id); boughtRemoved = bought }
                        }) { Text("Удалить") }
                    }
                }
            }
        }
        if (isAdmin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { buying = true }) { Text("Купить у хостера") }
            OutlinedButton(enabled = !busy, onClick = { newServer = true }) { Text("Свой VPS") }
        }

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
        if (isAdmin) Button(enabled = !busy, onClick = { newDevice = true }) { Text("Пригласить устройства") }
        if (isAdmin) {
            val host = remember(fleet.manifest) { app.fleet.managedServer()?.second?.host }
            if (host != null) FamilyPage(state.pages[host], busy,
                onCreate = { work { app.links.familyPage() } },
                onShare = { Share.text(context, familyMessage(it), "Отправить страницу") },
                onOpen = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) })
        }
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
        PeopleDialog(
            title = "Новые устройства флота",
            hint = "Устройства флота сами получают новые серверы и ваши настройки.",
            allowQr = true,
            onDismiss = { newDevice = false },
            onQr = { name -> newDevice = false; work { invite = app.fleet.addDevice(name) } },
        ) { names, delivery ->
            newDevice = false
            work { created = createPeople(app, names, null, delivery, toast) }
        }
    }

    accountOn?.let { p ->
        PeopleDialog(
            title = "Новые пользователи «${p.name}»",
            hint = "Каждому свой ключ на этом сервере, без флота.",
            allowQr = false,
            onDismiss = { accountOn = null },
            onQr = {},
        ) { names, delivery ->
            accountOn = null
            work { created = createPeople(app, names, p, delivery, toast) }
        }
    }

    created?.let { list ->
        AlertDialog(
            onDismissRequest = { created = null },
            title = { Text(if (list.size == 1) "Готово" else "Готово: ${list.size}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val files = list.any { it.stored.file }
                    Text(
                        if (files) "Отправьте каждому его файл. Человек открывает файл в мессенджере, и Paramnezia (или AmneziaVPN) " +
                            "сразу подключается. Файл работает без ссылки и не одноразовый: отправляйте лично."
                        else "Отправьте каждому его ссылку. Ссылка одноразовая и действует 7 дней: на Android откроет Paramnezia " +
                            "сама (или поможет установить), на iPhone выдаст ключ для AmneziaVPN.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    for (c in list) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                if (c.stored.file) Share.file(context, c.name, c.key)
                                else Share.text(context, inviteMessage(c.name, c.stored.url), "Отправить ссылку")
                            }) { Text(if (c.stored.file) "Отправить файл" else "Отправить ссылку") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { created = null }) { Text("Готово") } },
            dismissButton = {
                if (list.size > 1 && list.none { it.stored.file }) TextButton(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Paramnezia", list.joinToString("\n") { "${it.name}: ${it.stored.url}" }))
                    toast("Все ссылки скопированы")
                }) { Text("Скопировать все") }
            },
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
                    Share.text(context, inviteMessage(l.name, l.url), "Отправить ссылку")
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

    if (buying) CloudBuyDialog(app, onDismiss = { buying = false }, toast = toast) { n, ssh, onStep ->
        app.fleet.addServer(n, ssh, makeMaster = false, onStep = onStep)
    }
    boughtRemoved?.let { ref ->
        val provider = CloudProviders.info(ref.provider)?.name ?: ref.provider
        AlertDialog(
            onDismissRequest = { boughtRemoved = null },
            title = { Text("Удалить и у хостера?") },
            text = { Text("Сервер ${ref.host ?: ref.name} куплен у $provider из приложения${if (ref.price.isNotEmpty()) " (${ref.price})" else ""}. " +
                "Удалить его у хостера, чтобы он больше не оплачивался? Это необратимо.") },
            confirmButton = { TextButton(onClick = { boughtRemoved = null; work("Сервер удалён у $provider") { app.cloud.delete(ref) } }) { Text("Удалить у хостера") } },
            dismissButton = { TextButton(onClick = { boughtRemoved = null }) { Text("Оставить") } },
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

private fun familyMessage(url: String) =
    "Paramnezia — наш VPN. Здесь приложение и инструкция: $url\nНастройки пришлю отдельно, их достаточно открыть."

/**
 * The page on the admin's server to share with family and friends: the app, screenshots and how to
 * connect. Deployed with every new server; here it can be sent or opened.
 */
@Composable
private fun FamilyPage(url: String?, busy: Boolean, onCreate: () -> Unit, onShare: (String) -> Unit, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Страница для семьи и друзей", fontWeight = FontWeight.Bold)
            Text("Красивая страница на вашем сервере: скачать приложение, скриншоты и как подключиться. Отправьте её всем, " +
                "а личные настройки пришлите ссылкой или файлом.", style = MaterialTheme.typography.bodySmall)
            if (url == null) {
                OutlinedButton(enabled = !busy, onClick = onCreate) { Text("Создать страницу") }
            } else {
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onShare(url) }) { Text("Отправить") }
                    OutlinedButton(onClick = { onOpen(url) }) { Text("Открыть") }
                }
            }
        }
    }
}

/** The text that goes with a link in a messenger. */
private fun inviteMessage(name: String, url: String) = "Приглашение в VPN для «$name» (одноразовое, откройте на своём телефоне): $url"

/** Creates everyone and reports the ones that failed, so one bad name does not lose the rest. */
private suspend fun createPeople(app: App, names: List<String>, profile: StoredProfile?, delivery: Links.Delivery, toast: (String) -> Unit): List<Links.Created>? {
    val (ok, failed) = app.links.createMany(names, profile, delivery)
    if (failed.isNotEmpty()) toast("Не получилось: " + failed.joinToString("; ") { "${it.first} — ${it.second}" })
    return ok.ifEmpty { null }
}

/**
 * Names of the people to add (one per line, so a whole family goes in one step) and how they get
 * their settings: a one-time link or a file sent through a messenger.
 */
@Composable
private fun PeopleDialog(
    title: String,
    hint: String,
    allowQr: Boolean,
    onDismiss: () -> Unit,
    onQr: (String) -> Unit,
    onCreate: (List<String>, Links.Delivery) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val names = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    var delivery by remember { mutableStateOf(Links.Delivery.LINK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Имена, по одному на строку") },
                    placeholder = { Text("Мама\nПапа\nВика") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Text(hint, style = MaterialTheme.typography.bodySmall)
                for ((d, label, about) in listOf(
                    Triple(Links.Delivery.LINK, "Ссылки", "одноразовые, через ваш сервер; помогут установить приложение"),
                    Triple(Links.Delivery.FILE, "Файлы", "файл настроек каждому; открывается прямо из мессенджера"),
                )) {
                    Row(Modifier.fillMaxWidth().clickable { delivery = d }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(delivery == d, { delivery = d })
                        Column {
                            Text(label)
                            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = names.isNotEmpty(), onClick = { onCreate(names, delivery) }) {
                Text(if (names.size > 1) "Создать (${names.size})" else "Создать")
            }
        },
        dismissButton = {
            if (allowQr && names.size == 1) TextButton(onClick = { onQr(names.single()) }) { Text("QR-код") }
            else TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
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
    Text("Приглашения", style = MaterialTheme.typography.titleMedium)
    val now = System.currentTimeMillis()
    for (l in links.take(10)) {
        val open = !l.file && !l.used && l.expiresAt > now
        Row(Modifier.fillMaxWidth().clickable(enabled = open) { onOpen(l) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.name)
                Text(when { l.file -> "файл настроек"; l.used -> "использована"; !open -> "истекла"; else -> "ждёт" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            if (open) TextButton(onClick = { onOpen(l) }) { Text("Отправить") }
            if (open || (l.file && !l.fleetDevice)) {
                TextButton(enabled = !busy, onClick = { onRevoke(l) }) { Text(if (l.file) "Отключить" else "Отозвать") }
            }
        }
    }
}
