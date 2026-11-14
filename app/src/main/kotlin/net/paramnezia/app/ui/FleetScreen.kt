@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.paramnezia.app.App
import net.paramnezia.app.data.CloudServerRef
import net.paramnezia.app.data.StoredLink
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.app.fleet.Links
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.deploy.cloud.CloudProviders
import kotlinx.coroutines.launch
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.core.fleet.FleetDevice
import net.paramnezia.app.data.ServerReboot

/**
 * The fleet: this owner's servers and trusted devices, and everything for handing out access.
 * Devices follow the admin's manifest on their own; the admin device manages it here.
 *
 * Who comes here and what they need first:
 * - no server of their own yet → why a server is needed, and the way to get one;
 * - an admin with a server but no fleet → create the fleet (recommended) or invite people without one;
 * - a fleet admin → invite (the most frequent action), then devices, servers, sharing, delivery;
 * - a fleet member → which servers and devices there are and when settings last arrived.
 */
@Composable
fun FleetScreen(app: App, modifier: Modifier, toast: (String) -> Unit, onAddServer: () -> Unit = {}) {
    val state by app.repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val platform = LocalUiPlatform.current
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
    /** Standalone servers to make separate accounts on (no fleet); the dialog lets the admin pick one. */
    var accountOn by remember { mutableStateOf<List<StoredProfile>?>(null) }
    /** «Раздать по Wi-Fi» is open (the share runs while it is). */
    var lanShare by remember { mutableStateOf(false) }
    /** A server to reboot (asked to confirm, then the progress). */
    var rebootOf by remember { mutableStateOf<RebootTarget?>(null) }
    /** Owner: a device to make a co-admin, or (with [revoking]) to take the rights back from. */
    var adminOf by remember { mutableStateOf<FleetDevice?>(null) }
    var revoking by remember { mutableStateOf(false) }

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
    val openUrl: (String) -> Unit = { platform.openUrl(it) }
    val shareFamily: (String) -> Unit = { platform.shareText(familyMessage(it), "Отправить страницу") }
    val sync = { work { toast(if (app.fleet.sync()) "Получена новая конфигурация" else "Конфигурация актуальна") } }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.M, vertical = Space.S),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val fleet = state.fleet
        if (fleet == null) {
            val managed = state.profiles.filter { it.ssh != null }
            ScreenTitle("Семья", "Раздайте VPN семье и друзьям")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (managed.isEmpty()) {
                NeedServer(onAddServer)
                return@Column
            }
            // Recommended: a fleet survives a blocked server; plain accounts do not.
            FleetPitch(managed, busy) { p -> work("Семейная группа создана") { app.fleet.create(p.name, WgQuick.parse(p.conf), p.ssh!!, platform.deviceModel) } }
            Group {
                GroupRow(
                    "Просто выдать ключи", subtitle = "Каждому свой ключ ссылкой или файлом, без автообновления серверов",
                    icon = Glyph.PersonAdd, onClick = { if (!busy) accountOn = managed },
                    trailing = { Chevron() },
                )
            }
            SectionTitle("Серверы") { Pill(managed.size.toString()) }
            Group {
                for (p in managed) {
                    val ssh = p.ssh!!
                    GroupRow(
                        p.name, subtitle = ssh.host, icon = Glyph.Server,
                        trailing = { Overflow(busy, "Перезагрузить сервер" to { rebootOf = RebootTarget(p.name, ssh.host, ssh) }) },
                    )
                }
            }
            SectionTitle("Раздача")
            Group { LanShareRow("Телевизору и компьютеру рядом: приложение и ключ") { if (!busy) lanShare = true } }
            FamilyPage(state.pages[managed.first().ssh!!.host], busy, onCreate = { work { app.links.familyPage() } }, onShare = shareFamily, onOpen = openUrl)
            LinkList(state.links, busy, onOpen = { link = it }) { l -> work("Ссылка отозвана") { app.links.revoke(l) } }
            Spacer(Modifier.height(Space.M))
            return@Column
        }

        val manifest = remember(fleet.manifest) { app.fleet.manifest() }
        val isAdmin = fleet.adminSeed != null
        val isOwner = isAdmin && !fleet.coAdmin
        val me = WgKey.fromBase64(fleet.deviceKey).publicKey().toBase64()
        val adminDevices = manifest?.admins.orEmpty().map { it.device }.toSet()
        val servers = manifest?.servers.orEmpty()
        val devices = manifest?.devices.orEmpty()

        ScreenTitle(
            "Семья",
            if (isAdmin) (if (isOwner) "Вы владелец" else "Вы администратор") + " · ${plural(devices.size, "устройство", "устройства", "устройств")}, ${plural(servers.size, "сервер", "сервера", "серверов")}"
            else "Серверы и настройки приходят от администратора",
        ) {
            IconButton(enabled = !busy, onClick = sync) { Icon(PnIcons.Refresh, "Проверить обновления конфигурации") }
        }
        SyncStatus(fleet.lastSync, fleet.lastSyncVia)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (isAdmin) ActionTile(Glyph.PersonAdd, "Пригласить", "Ссылкой, файлом или QR-кодом, сразу нескольким", primary = true) {
            if (!busy) newDevice = true
        }
        if (isAdmin) ActionTile(Glyph.Wifi, "Раздать по Wi-Fi", "Телевизору и компьютеру рядом: приложение и настройки, без интернета", primary = false) {
            if (!busy) lanShare = true
        }

        SectionTitle("Устройства") { Pill(devices.size.toString()) }
        Group {
            for (d in devices) {
                val self = d.publicKey == me
                val ownerDevice = d.publicKey == manifest?.ownerDevice
                val coAdmin = d.publicKey in adminDevices
                val role = when { ownerDevice -> "владелец"; coAdmin -> "администратор"; else -> null }
                val actions = buildList<Pair<String, () -> Unit>> {
                    if (isOwner && !self) {
                        if (coAdmin) add("Забрать права администратора" to { revoking = true; adminOf = d })
                        else add("Сделать администратором" to { revoking = false; adminOf = d })
                    }
                    // A co-admin cannot take the owner's or another administrator's device off the family.
                    if (isAdmin && !self && (isOwner || (!ownerDevice && !coAdmin))) add("Отключить" to { work("Устройство отключено") { app.fleet.removeDevice(d.publicKey) } })
                }
                GroupRow(
                    d.name, subtitle = listOfNotNull(d.address.substringBefore('/'), role).joinToString(" · "),
                    monogram = d.name.trim().take(1).uppercase().ifEmpty { "?" },
                    accent = if (self) Brand.Violet else if (role != null) Brand.Mint else MaterialTheme.colorScheme.primary,
                    badge = if (self) "это устройство" else null,
                    trailing = { if (actions.isNotEmpty()) Overflow(busy, *actions.toTypedArray()) },
                )
            }
        }

        SectionTitle("Серверы") { Pill(servers.size.toString()) }
        if (isAdmin && servers.size < 2) {
            Hint("Добавьте резервный сервер: если основной заблокируют, устройства сами переключатся на него.", PnIcons.Warning, Brand.Amber)
        }
        Group {
            servers.forEachIndexed { i, s ->
                val actions = buildList<Pair<String, () -> Unit>> {
                    if (isAdmin) add("Перезагрузить сервер" to { rebootOf = RebootTarget(s.name, s.host, app.fleet.accessTo(s.id)) })
                    if (isAdmin && i > 0) add("Сделать основным" to { work("${s.name} теперь основной") { app.fleet.promote(s.id) } })
                    if (isOwner && servers.size > 1) add("Убрать сервер" to {
                        val bought = app.cloud.serverAt(s.host)
                        work("Сервер убран из семьи") { app.fleet.removeServer(s.id); boughtRemoved = bought }
                    })
                }
                GroupRow(
                    s.name, subtitle = "${s.host}:${s.port}", icon = Glyph.Server,
                    accent = if (i == 0) Brand.Mint else MaterialTheme.colorScheme.onSurfaceVariant,
                    badge = if (i == 0) "основной" else "резервный",
                    trailing = { if (actions.isNotEmpty()) Overflow(busy, *actions.toTypedArray()) },
                )
            }
            if (isOwner) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(Space.M), horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    FilledTonalButton(enabled = !busy, onClick = { buying = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Glyph.Cloud, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.S)); Text("У хостера", maxLines = 1)
                    }
                    OutlinedButton(enabled = !busy, onClick = { newServer = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Glyph.Server, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.S)); Text("Свой VPS", maxLines = 1)
                    }
                }
            }
        }

        if (isAdmin) {
            SectionTitle("Раздача")
            val host = remember(fleet.manifest) { app.fleet.managedServer()?.second?.host }
            if (host != null) FamilyPage(state.pages[host], busy, onCreate = { work { app.links.familyPage() } }, onShare = shareFamily, onOpen = openUrl)
            LinkList(state.links, busy, onOpen = { link = it }) { l -> work("Ссылка отозвана") { app.links.revoke(l) } }

            if (!isOwner) Hint("Права администратора выдал владелец семьи: вы приглашаете и отключаете людей и перезагружаете серверы. " +
                "Серверы, общие настройки и других администраторов меняет только он.", PnIcons.Info)
            if (isOwner) SectionTitle("Доставка настроек")
            if (isOwner) Group {
                GroupRow(
                    "Мои настройки на всех устройствах", subtitle = "Обход российского, блокировка рекламы, списки «через VPN» и «напрямую»",
                    icon = PnIcons.Settings,
                    onClick = { if (!busy) work(if (!fleet.sharePolicy) "Устройства получат ваши настройки" else "Устройства оставят свои настройки") {
                        app.fleet.sharePolicy(state.settings, !fleet.sharePolicy)
                    } },
                    trailing = {
                        Switch(fleet.sharePolicy, enabled = !busy, onCheckedChange = { on ->
                            work(if (on) "Устройства получат ваши настройки" else "Устройства оставят свои настройки") { app.fleet.sharePolicy(state.settings, on) }
                        })
                    },
                )
                ReserveChannel(fleet.gistToken != null, fleet.gistError, busy, toast,
                    onEnable = { token -> work("Резервный канал включён") { app.fleet.setReserveChannel(token) } },
                    onDisable = { work("Резервный канал отключён") { app.fleet.clearReserveChannel() } },
                    openTokenPage = { openUrl(GITHUB_TOKEN_PAGE) })
            }
        } else {
            if (manifest?.policy != null) {
                Hint("Обход российских сайтов и блокировку рекламы настраивает администратор семьи, они обновляются сами.", PnIcons.Info)
            }
            SectionTitle("Раздача")
            Group { LanShareRow("Приложение для телевизора и компьютера рядом; настройки выдаёт администратор") { lanShare = true } }
        }
        Spacer(Modifier.height(Space.M))
    }

    if (lanShare) LanShareDialog(app, onDismiss = { lanShare = false }, toast = toast)

    rebootOf?.let { t -> RebootDialog(app, t, onDismiss = { rebootOf = null }) }

    adminOf?.let { d ->
        AlertDialog(
            onDismissRequest = { adminOf = null },
            icon = { Icon(PnIcons.Settings, null) },
            title = { Text(if (revoking) "Забрать права у «${d.name}»?" else "Сделать «${d.name}» администратором?") },
            text = {
                Text(
                    if (revoking) "Устройство останется в семье и в VPN, но больше не сможет управлять семьёй и серверами: " +
                        "его ключ уберётся с серверов, а подписи перестанут приниматься."
                    else "Устройство сможет приглашать и отключать людей и перезагружать серверы. Серверы, общие настройки и " +
                        "других администраторов по-прежнему меняете только вы. У него будут свои ключи (а не ваши) — их можно " +
                        "забрать в любой момент. Права придут на устройство сами, при следующей проверке семьи (обычно сразу при открытии приложения).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    adminOf = null
                    if (revoking) work("Права администратора забраны") { app.fleet.revokeAdmin(d.publicKey) }
                    else work { val missed = app.fleet.grantAdmin(d.publicKey)
                        toast(if (missed == 0) "${d.name}: права администратора выданы" else "${d.name}: права выданы; на $missed сервер(ах) ключ добавится при следующей самопроверке") }
                }) { Text(if (revoking) "Забрать" else "Сделать администратором") }
            },
            dismissButton = { TextButton(onClick = { adminOf = null }) { Text("Отмена") } },
        )
    }

    if (newDevice) {
        PeopleDialog(
            title = "Пригласить в семью",
            hint = "Устройства семьи сами получают новые серверы и ваши настройки.",
            allowQr = true,
            onDismiss = { newDevice = false },
            onQr = { name -> newDevice = false; work { invite = app.fleet.addDevice(name) } },
        ) { names, delivery, _ ->
            newDevice = false
            work { created = createPeople(app, names, null, delivery, toast) }
        }
    }

    accountOn?.let { choices ->
        PeopleDialog(
            title = "Выдать ключи",
            hint = "Каждому свой ключ на выбранном сервере.",
            allowQr = false,
            servers = choices,
            onDismiss = { accountOn = null },
            onQr = {},
        ) { names, delivery, server ->
            accountOn = null
            work { created = createPeople(app, names, server ?: choices.first(), delivery, toast) }
        }
    }

    created?.let { list ->
        AlertDialog(
            onDismissRequest = { created = null },
            icon = { Icon(PnIcons.CheckCircle, null, tint = Brand.Mint) },
            title = { Text(if (list.size == 1) "Готово" else "Готово: ${list.size}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    val files = list.any { it.stored.file }
                    Text(
                        if (files) "Отправьте каждому его файл. Человек открывает файл в мессенджере, и Paramnezia (или AmneziaVPN) " +
                            "сразу подключается. Файл работает без ссылки и не одноразовый: отправляйте лично."
                        else "Отправьте каждому его ссылку. Ссылка одноразовая и действует 7 дней: на Android откроет Paramnezia " +
                            "сама (или поможет установить), на iPhone выдаст ключ для AmneziaVPN.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Group {
                        for (c in list) GroupRow(
                            c.name, monogram = c.name.trim().take(1).uppercase().ifEmpty { "?" },
                            trailing = {
                                FilledTonalButton(onClick = {
                                    if (c.stored.file) platform.shareFile(c.name, c.key)
                                    else platform.shareText(inviteMessage(c.name, c.stored.url), "Отправить ссылку")
                                }) { Icon(PnIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (c.stored.file) "Файл" else "Ссылку") }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { created = null }) { Text("Готово") } },
            dismissButton = {
                if (list.size > 1 && list.none { it.stored.file }) TextButton(onClick = {
                    platform.copy(list.joinToString("\n") { "${it.name}: ${it.stored.url}" })
                    toast("Все ссылки скопированы")
                }) { Text("Скопировать все") }
            },
        )
    }

    link?.let { l ->
        AlertDialog(
            onDismissRequest = { link = null },
            icon = { Icon(Glyph.Link, null) },
            title = { Text("Ссылка для «${l.name}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectionContainer {
                        Text(l.url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp))
                    }
                    Text("Сработает один раз и действует 7 дней. Отправьте её в личном сообщении: кто откроет первым, тот и получит доступ.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = { platform.shareText(inviteMessage(l.name, l.url), "Отправить ссылку") }) { Text("Отправить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    platform.copy(l.url)
                    toast("Ссылка скопирована")
                }) { Text("Скопировать") }
            },
        )
    }

    invite?.let { key ->
        AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text("Приглашение") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    KeyQr(key)
                    Text("Отсканируйте в Paramnezia на новом устройстве. AmneziaVPN тоже примет этот ключ, но без автопереключения.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    platform.shareText(key, "Отправить ключ")
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
        val form = remember { SshFormState() }
        var master by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { newServer = false },
            icon = { Icon(Glyph.Server, null) },
            title = { Text("Свой VPS для семьи") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("AmneziaWG установится сам, все устройства семьи получат доступ к серверу.", style = MaterialTheme.typography.bodyMedium)
                    SshFields(form, enabled = true, toast = toast, showName = true)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { master = !master }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(master, { master = it })
                        Text("Сразу сделать основным")
                    }
                }
            },
            confirmButton = {
                Button(enabled = form.ready, onClick = {
                    newServer = false
                    val creds = form.credentials()
                    work("Сервер добавлен в семью") { app.fleet.addServer(form.name.ifBlank { creds.host }, creds, master) {} }
                }) { Text("Развернуть") }
            },
            dismissButton = { TextButton(onClick = { newServer = false }) { Text("Отмена") } },
        )
    }
}

/** No server of one's own: nothing to share yet. Says why and leads to the «Добавить» tab. */
@Composable
private fun NeedServer(onAddServer: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.L), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Brand.Coral.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Glyph.Server, null, Modifier.size(28.dp), tint = Brand.Coral)
            }
            Text("Сначала нужен свой сервер", style = MaterialTheme.typography.titleLarge)
            Text("Приглашать людей и создавать семейную группу можно на сервере, который вы купили в приложении или развернули на своём VPS.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAddServer, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Добавить сервер") }
        }
    }
    SectionTitle("Как это работает")
    Group {
        GroupRow("Свой сервер", subtitle = "Купленный у хостера из приложения или ваш VPS", icon = Glyph.Server)
        GroupRow("Приглашения", subtitle = "Ссылкой, файлом или QR-кодом, сразу всей семье", icon = Glyph.PersonAdd)
        GroupRow("Одна кнопка", subtitle = "Люди подключаются без настроек, при блокировке все сами переходят на резервный сервер", icon = Glyph.Shield)
    }
}

/** Why a fleet, and the button to make one on each own server. */
@Composable
private fun FleetPitch(managed: List<StoredProfile>, busy: Boolean, onCreate: (StoredProfile) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.L), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.Shield, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text("Создать семейную группу", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                Pill("рекомендую", MaterialTheme.colorScheme.onSecondaryContainer)
            }
            for (line in listOf(
                "Устройства сами получают новые серверы и ваши настройки",
                "Заблокировали сервер — все переключатся на резервный",
                "Приглашения ссылкой, файлом или QR-кодом",
            )) Row(verticalAlignment = Alignment.Top) {
                Text("•", color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(end = Space.S))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            for (p in managed) Button(enabled = !busy, onClick = { onCreate(p) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (managed.size == 1) "Создать семейную группу" else "Создать на «${p.name}»", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** When this device last got the configuration, and from where. */
@Composable
private fun SyncStatus(lastSync: Long, via: String?) {
    if (lastSync <= 0) {
        Hint("Конфигурация ещё не проверялась. Нажмите ⟳ вверху.", PnIcons.Info)
        return
    }
    val from = when (via) { "tunnel" -> "с сервера"; "github" -> "через GitHub"; "tor" -> "через Tor (Orbot)"; else -> "с зеркала" }
    Hint("Конфигурация актуальна: проверено ${ago(System.currentTimeMillis() - lastSync)}, $from",
        PnIcons.CheckCircle, Brand.Mint)
}

@Composable
private fun LanShareRow(subtitle: String, onClick: () -> Unit) = GroupRow(
    "Раздать по Wi-Fi", subtitle = subtitle, icon = Glyph.Wifi, onClick = onClick, trailing = { Chevron() },
)

@Composable
private fun Chevron() = Icon(PnIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/** «⋮» with the row's less frequent (and destructive) actions. */
@Composable
private fun Overflow(busy: Boolean, vararg actions: Pair<String, () -> Unit>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = !busy, onClick = { open = true }) { Icon(PnIcons.MoreVertical, "Действия") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for ((label, action) in actions) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
        }
    }
}

/** 1 устройство, 2 устройства, 5 устройств. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}

private fun familyMessage(url: String) =
    "Paramnezia — наш VPN. Здесь приложение и инструкция: $url\nНастройки пришлю отдельно, их достаточно открыть."

/**
 * The page on the admin's server to share with family and friends: the app, screenshots and how to
 * connect. Deployed with every new server; here it can be sent or opened.
 */
@Composable
private fun FamilyPage(url: String?, busy: Boolean, onCreate: () -> Unit, onShare: (String) -> Unit, onOpen: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Glyph.Globe, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(Space.M))
                Column(Modifier.weight(1f)) {
                    Text("Страница для семьи и друзей", style = MaterialTheme.typography.titleMedium)
                    Text("Приложение, скриншоты и как подключиться. Одна на всех, а личные настройки — ссылкой или файлом.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (url == null) {
                FilledTonalButton(enabled = !busy, onClick = onCreate, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Создать страницу") }
            } else {
                SelectionContainer {
                    Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    Button(onClick = { onShare(url) }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(PnIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.S)); Text("Отправить")
                    }
                    OutlinedButton(onClick = { onOpen(url) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Открыть") }
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
 * Names of the people to add (one per line or comma-separated, so a whole family goes in one step;
 * a list pasted from a chat works too) and how they get their settings: a one-time link or a file.
 */
@Composable
private fun PeopleDialog(
    title: String,
    hint: String,
    allowQr: Boolean,
    onDismiss: () -> Unit,
    onQr: (String) -> Unit,
    servers: List<StoredProfile> = emptyList(),
    onCreate: (List<String>, Links.Delivery, StoredProfile?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val names = text.split('\n', ',', ';').map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotEmpty() }.distinct()
    var delivery by remember { mutableStateOf(Links.Delivery.LINK) }
    var server by remember { mutableStateOf(servers.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Glyph.PersonAdd, null) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PasteField(text, { text = it }, "Кого пригласить", placeholder = "Мама, Папа, Вика", singleLine = false, minLines = 2, maxLines = 8,
                    supporting = if (names.size > 1) "${names.size}: ${names.joinToString(", ")}" else "По одному на строку или через запятую")
                if (servers.size > 1) {
                    Text("Сервер", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        servers.forEachIndexed { i, p ->
                            SegmentedButton(server == p, { server = p }, SegmentedButtonDefaults.itemShape(i, servers.size)) {
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text("Как отправить", style = MaterialTheme.typography.labelLarge)
                val options = listOf(Links.Delivery.LINK to "Ссылки", Links.Delivery.FILE to "Файлы")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (d, label) ->
                        SegmentedButton(delivery == d, { delivery = d }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                    }
                }
                Text(
                    if (delivery == Links.Delivery.LINK) "Одноразовые ссылки через ваш сервер: помогут установить приложение и сразу подключат."
                    else "Файл настроек каждому: открывается прямо из мессенджера, работает и без вашего сервера.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(enabled = names.isNotEmpty(), onClick = { onCreate(names, delivery, server) }) {
                Text(if (names.size > 1) "Создать (${names.size})" else "Создать")
            }
        },
        dismissButton = {
            Row {
                if (allowQr && names.size == 1) TextButton(onClick = { onQr(names.single()) }) { Text("QR-код") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** GitHub page for a classic token with only the `gist` scope, prefilled. */
private const val GITHUB_TOKEN_PAGE = "https://github.com/settings/tokens/new?scopes=gist&description=Paramnezia"

/**
 * The reserve channel: a secret GitHub gist with the encrypted manifest. Devices read it when no
 * fleet server is reachable, so they learn about new servers even after every old one was banned.
 * A row in the «delivery» group; its setup opens in place with one tap.
 */
@Composable
private fun ReserveChannel(on: Boolean, error: String?, busy: Boolean, toast: (String) -> Unit, onEnable: (String) -> Unit, onDisable: () -> Unit, openTokenPage: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    val needsToken = !on || error != null
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            GroupRow(
                "Резервный канал GitHub",
                subtitle = when {
                    error != null -> "Не обновляется: $error"
                    on -> "Семья останется с VPN, даже если заблокируют все серверы"
                    else -> "Выключен: устройства узнают о новых серверах только через старые"
                },
                icon = Glyph.Globe,
                accent = when { error != null -> MaterialTheme.colorScheme.error; on -> Brand.Mint; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                badge = if (on && error == null) "включён" else null,
                onClick = { open = !open },
                trailing = {
                    Icon(if (open) PnIcons.ChevronUp else PnIcons.ChevronDown, if (open) "Свернуть" else "Настроить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                },
            )
            AnimatedVisibility(open || error != null) {
                Column(Modifier.padding(start = Space.M, end = Space.M, bottom = Space.M), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (on) "Если заблокируют все серверы, устройства найдут новые настройки на GitHub. Там лежит только " +
                            "зашифрованная и подписанная конфигурация: GitHub не может ни прочитать, ни подменить её."
                        else "Нужен один раз токен GitHub с правом «gist»; срок действия выберите «No expiration». На GitHub лежит только " +
                            "зашифрованная и подписанная конфигурация.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (needsToken) {
                        OutlinedButton(onClick = openTokenPage, modifier = Modifier.fillMaxWidth()) {
                            Icon(Glyph.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.S)); Text("1. Получить токен на GitHub")
                        }
                        PasteField(token, { token = it.trim() }, "2. Токен GitHub", placeholder = "ghp_…", secret = true, enabled = !busy,
                            onEmptyClipboard = { toast("Буфер обмена пуст: скопируйте токен на GitHub") })
                        Button(enabled = !busy && token.length >= 20, onClick = { onEnable(token); token = "" }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text("3. Включить")
                        }
                    }
                    if (on) TextButton(enabled = !busy, onClick = onDisable) { Text("Отключить резервный канал") }
                }
            }
        }
    }
}

/** Links handed out, with whether they have been used yet. */
@Composable
private fun LinkList(links: List<StoredLink>, busy: Boolean, onOpen: (StoredLink) -> Unit, onRevoke: (StoredLink) -> Unit) {
    if (links.isEmpty()) return
    val now = System.currentTimeMillis()
    SectionTitle("Приглашения") { Pill(links.size.coerceAtMost(10).toString()) }
    Group {
        for (l in links.take(10)) {
            val open = !l.file && !l.used && l.expiresAt > now
            val status = when { l.file -> "файл настроек"; l.used -> "использована"; !open -> "истекла"; else -> "ждёт, ${expiresIn(l.expiresAt - now)}" }
            GroupRow(
                l.name, subtitle = status, icon = if (l.file) Glyph.File else Glyph.Link,
                accent = when { open -> Brand.Amber; l.used || l.file -> Brand.Mint; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                onClick = if (open) ({ onOpen(l) }) else null,
                trailing = {
                    if (open) TextButton(onClick = { onOpen(l) }) { Text("Отправить") }
                    if (open || (l.file && !l.fleetDevice)) Overflow(busy, (if (l.file) "Отключить доступ" else "Отозвать ссылку") to { onRevoke(l) })
                },
            )
        }
    }
}

private fun expiresIn(ms: Long): String {
    val h = ms / 3_600_000
    return if (h >= 24) "ещё ${plural((h / 24).toInt(), "день", "дня", "дней")}" else "ещё ${plural(h.toInt().coerceAtLeast(1), "час", "часа", "часов")}"
}

/** A server to reboot: its name, address and SSH access (null: only the hoster's reset is possible). */
private data class RebootTarget(val name: String, val host: String, val ssh: SshCredentials?)

/**
 * "Reboot the server": confirmation, then each stage as it happens (over SSH, or through the hoster
 * when SSH is dead; going down; booting; the VPN container back), and the outcome. Works with our own
 * VPN down or broken: everything goes over the physical network.
 */
@Composable
private fun RebootDialog(app: App, t: RebootTarget, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var started by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<ServerReboot.Stage?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val running = started && result == null
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        icon = { Icon(if (failed) PnIcons.Warning else PnIcons.Refresh, null, tint = if (failed) Brand.Coral else MaterialTheme.colorScheme.primary) },
        title = { Text(if (!started) "Перезагрузить «${t.name}»?" else if (result == null) "Перезагрузка…" else if (failed) "Не получилось" else "Сервер снова в сети") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !started -> Text(
                        "Сервер перезагрузится целиком, принудительно: это помогает, когда VPN не работает или сервер завис. " +
                            "Минуту-две VPN через него будет недоступен, устройства семьи на это время переключатся на резервный сервер, если он есть." +
                            if (t.ssh == null) "\n\nДоступа по SSH к этому серверу у приложения нет: перезагрузка пойдёт через хостера (нужен его токен в приложении)." else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    result == null -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stage?.label ?: "Начинаю…", style = MaterialTheme.typography.bodyMedium)
                        Text("Не закрывайте это окно до результата: обычно 1–3 минуты.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text(result!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when {
                !started -> TextButton(onClick = {
                    started = true
                    scope.launch {
                        runCatching { app.reboots.reboot(t.host, t.ssh) { stage = it } }
                            .onSuccess { o ->
                                result = (if (o.viaHoster) "Перезагружен через хостера" else "Перезагружен") + " за ${o.seconds} с." +
                                    when (o.vpnRunning) { true -> " VPN на сервере работает."; false -> " VPN на сервере пока не запустился: откройте «Семья» чуть позже, приложение проверит и починит его само."; null -> "" }
                                if (app.fleet.isAdmin) launch { runCatching { app.fleet.heal(null) } }
                            }
                            .onFailure { failed = true; result = it.message ?: "Ошибка" }
                    }
                }) { Text("Перезагрузить") }
                result != null -> TextButton(onClick = onDismiss) { Text("Готово") }
                else -> {}
            }
        },
        dismissButton = { if (!started) TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
