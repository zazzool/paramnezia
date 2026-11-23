package net.paramnezia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.paramnezia.app.App
import net.paramnezia.core.config.Camouflage
import net.paramnezia.core.config.ParamCatalog
import net.paramnezia.core.config.PathMtu
import net.paramnezia.core.config.ProtocolVersion
import net.paramnezia.deploy.ServerStatus
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import java.time.Instant

/**
 * One server in detail: how this device is connected to it right now, the protocol and every
 * AmneziaWG parameter in words, and (with admin access) what the server itself reports over SSH.
 *
 * Built after the detail views of WireGuard, AmneziaVPN and Tailscale (sections of label and value,
 * a tap copies, status pills, rarely needed parameters folded away) in the app's own style. Every
 * parameter carries who has to agree on it ([ParamCatalog.Scope]: the server, or only this device):
 * the same catalog will drive editing, so what is shown here is what an editor will change.
 */
@Composable
fun ServerScreen(app: App, serverId: String, modifier: Modifier, onBack: () -> Unit) {
    val platform = LocalUiPlatform.current
    val repoState by app.repo.state.collectAsState()
    val view = remember(serverId, repoState) { app.insights.view(serverId) }
    val tunnel by TunnelRuntime.state.collectAsState()
    val session by TunnelRuntime.session.collectAsState()
    val health by TunnelRuntime.health.collectAsState()
    var status by remember { mutableStateOf<ServerStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var params by remember { mutableStateOf(false) }

    if (view == null) {
        Column(modifier.fillMaxSize().padding(Space.M)) {
            Header("Сервер", null, onBack)
            Hint("Этого сервера больше нет в приложении.", PnIcons.Info)
        }
        return
    }
    LaunchedEffect(serverId, reload) {
        if (view.ssh == null) return@LaunchedEffect
        loading = true
        statusError = null
        runCatching { app.insights.status(serverId) }
            .onSuccess { status = it }
            .onFailure { statusError = it.message ?: it.toString() }
        loading = false
    }
    val live = session?.takeIf { it.serverId == serverId && tunnel is TunnelState.Up }
    // Handshake age, refreshed while the screen is open and this server is in use.
    var handshakeAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live != null) {
        while (live != null) {
            TunnelRuntime.stats()?.let { handshakeAt = it.lastHandshakeEpochSec * 1000 }
            now = System.currentTimeMillis()
            delay(2_000)
        }
    }
    val traffic = rememberLive(tunnel)
    val copy: (String) -> Unit = { platform.copy(it); platform.toast("Скопировано") }
    val scope = rememberCoroutineScope()
    val local = repoState.profiles.firstOrNull { it.id == serverId }
    val family = remember(repoState.fleet?.manifest) { app.fleet.manifest()?.servers.orEmpty() }
    val familyServer = family.firstOrNull { it.id == serverId }
    val canEditFamily = app.fleet.isOwner && familyServer != null
    val canPromoteFamily = app.fleet.isAdmin && familyServer != null && family.first().id != serverId
    val canMakeLocalPrimary = local != null && (
        repoState.profiles.first().id != serverId ||
            repoState.activeProfile?.let { it != serverId } == true ||
            (repoState.fleet != null && repoState.activeProfile == null)
        )
    val canShare = local?.ssh?.let { ssh ->
        app.fleet.isOwner && repoState.fleet?.serverAccess?.values.orEmpty().none {
            it.host.equals(ssh.host, ignoreCase = true) && it.port == ssh.port
        }
    } == true
    val canMove = canEditFamily && app.fleet.accessTo(serverId) != null
    var moving by remember(serverId) { mutableStateOf(false) }
    var editing by remember(serverId) { mutableStateOf(false) }
    var deleting by remember(serverId) { mutableStateOf(false) }
    var sharing by remember(serverId) { mutableStateOf(false) }
    var busy by remember(serverId) { mutableStateOf(false) }
    fun manage(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { action() }.onFailure { platform.toast(it.message ?: "Ошибка") }
            busy = false
        }
    }
    if (editing) {
        var name by remember { mutableStateOf(view.name) }
        var connection by remember { mutableStateOf(local?.conf ?: view.endpoint.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Редактировать сервер") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Название") })
                    OutlinedTextField(connection, { connection = it }, singleLine = local == null,
                        label = { Text(if (local == null) "Адрес VPN и порт" else "Конфигурация VPN") })
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && name.isNotBlank() && connection.isNotBlank(), onClick = {
                    manage {
                        if (local != null) {
                            app.servers.edit(serverId, name, connection)
                            if (live != null) app.reconnectIfUp()
                        } else app.fleet.editServer(serverId, name, connection)
                        editing = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
        )
    }
    if (moving) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) moving = false },
            title = { Text("У сервера новый IP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text("Укажите новый адрес того же сервера. После проверки SSH-ключа семья получит адрес при синхронизации.")
                    if (repoState.fleet?.gistId == null && family.size == 1) {
                        Text("Если старый адрес уже недоступен, участникам нужен резервный канал, настроенный заранее.")
                    }
                    OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("IPv4, IPv6 или домен") })
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && address.isNotBlank(), onClick = {
                    manage {
                        app.fleet.moveServerAddress(serverId, address)
                        moving = false
                        platform.toast("Новый адрес сохранён")
                    }
                }) { Text(if (busy) "Проверяем…" else "Обновить адрес") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { moving = false }) { Text("Отмена") } },
        )
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Удалить сервер?") },
        text = { Text(view.name) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                manage {
                    if (local != null) {
                        app.servers.remove(serverId)
                        if (app.serverCount() == 0) app.disconnect()
                        else if (live != null || repoState.activeProfile == serverId) app.reconnectIfUp()
                    } else app.fleet.removeServer(serverId)
                    onBack()
                }
            }) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } },
    )
    if (sharing) AlertDialog(
        onDismissRequest = { sharing = false },
        title = { Text("Добавить сервер в семью?") },
        text = { Text(view.name) },
        confirmButton = {
            TextButton(onClick = {
                sharing = false
                manage {
                    app.fleet.addServer(view.name, checkNotNull(local?.ssh), makeMaster = false) {}
                    platform.toast("Сервер добавлен в семью")
                }
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = { sharing = false }) { Text("Отмена") } },
    )
    val config = view.config
    val protocol = ProtocolVersion.of(config)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.M, vertical = Space.S),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(view.name, listOfNotNull(view.endpoint?.let { net.paramnezia.core.config.Uapi.splitEndpoint(it).first }, view.role).joinToString(" · "), onBack) {
            if (view.ssh != null) IconButton(enabled = !loading, onClick = { reload++ }) { Icon(PnIcons.Refresh, "Обновить сведения сервера") }
        }

        if (local != null || canEditFamily || canPromoteFamily) Group {
            if (canPromoteFamily || canMakeLocalPrimary) {
                GroupRow(
                    "Сделать основным",
                    subtitle = if (local == null) "Для всей семьи" else "На этом устройстве",
                    icon = Glyph.Server,
                    onClick = {
                        if (!busy) manage {
                            if (local == null) {
                                app.fleet.promote(serverId, useAutomaticSelection = true)
                            } else {
                                app.servers.makePrimary(serverId)
                                app.reconnectIfUp()
                            }
                            platform.toast("${view.name} теперь основной")
                        }
                    },
                )
            }
            if (local != null || canEditFamily) {
                GroupRow("Редактировать", icon = Glyph.Server, onClick = { if (!busy) editing = true })
            }
            if (canMove) GroupRow("У сервера новый IP", icon = Glyph.Server, onClick = { if (!busy) moving = true })
            if (canShare) GroupRow("Добавить в семью", icon = Glyph.Server, onClick = { if (!busy) sharing = true })
            if (local != null || (canEditFamily && family.size > 1)) {
                GroupRow("Удалить из списка", icon = Glyph.Server, onClick = { if (!busy) deleting = true })
            }
        }

        // The state in one line, coloured like the power button.
        Group {
            GroupRow(
                if (live != null) "Подключено через этот сервер" else "Сейчас не используется",
                subtitle = if (live != null) "${protocol.label} · ${variantText(live.variant)}" else protocol.label,
                icon = if (live != null) PnIcons.ShieldCheck else PnIcons.Shield,
                accent = if (live != null) Brand.Mint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (live != null) {
            SectionTitle("Соединение")
            Group {
                Info("Вход", variantText(live.variant), hint = "подпись первых пакетов, порт и адрес, подобранные для этой сети")
                Info("Размер пакета (MTU)", "${live.mtu} байт" + if (live.mtu > PathMtu.SAFE) " · подобран для сети" else " · безопасный")
                health.rttMs?.let { Info("Задержка", "$it мс") }
                if (handshakeAt > 0) Info("Последнее рукопожатие", ago(now - handshakeAt))
                Info("Трафик", "↓ ${formatBytes(traffic.rx)}  ↑ ${formatBytes(traffic.tx)}")
                Info("Keepalive", keepaliveText(live.keepalive))
            }
        }

        SectionTitle("Протокол")
        Group {
            Info("Версия", protocol.label)
            Info("Ключ сервера", app.insights.shortKey(config), mono = true, onClick = app.insights.serverKey(config)?.let { k -> { copy(k) } })
            Info("Предварительный ключ (PSK)", view.psk.label, hint = "постквантовая защита записанного трафика")
            Info("Адрес в туннеле", config.iface.addresses.joinToString { it.toString() }, mono = true)
            if (config.iface.dns.isNotEmpty()) Info("DNS в туннеле", config.iface.dns.joinToString(", "), mono = true)
            Info("Порты", (listOfNotNull(view.endpoint?.substringAfterLast(':')) + view.altPorts.map { it.toString() }).distinct().joinToString(", ")
                .ifEmpty { "—" }, hint = if (view.altPorts.isEmpty()) null else "основной и запасные: если порт закроют, устройство перейдёт на другой")
            if (view.altHosts.isNotEmpty()) Info("Другие адреса", view.altHosts.joinToString(", "), mono = true)
            val key = app.insights.serverKey(config)
            if (key != null && Camouflage.obfuscated(config)) Info("Своя маскировка сервера", mimicText(Camouflage.mimicFor(key)),
                hint = "с неё устройство начинает в каждой новой сети")
        }

        val groups = remember(config) { ParamCatalog.of(config) }
        if (groups.isNotEmpty()) {
            ExpandTile(
                PnIcons.Sliders, "Параметры AmneziaWG", "${groups.values.sumOf { it.size }} параметров · метка показывает, кто должен знать значение",
                expanded = params, onToggle = { params = !params },
            ) {
                for ((group, list) in groups) {
                    Text(group.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Group {
                        for ((spec, value) in list) {
                            Info(spec.label, ParamCatalog.describe(spec.param, value), hint = spec.hint, scope = spec.scope,
                                mono = spec.editor is ParamCatalog.Editor.Number || spec.editor is ParamCatalog.Editor.Range,
                                onClick = { copy(value) })
                        }
                    }
                }
                Hint("«Сервер» — значение проверяет сервер, менять его можно только для сервера и всех его устройств сразу. " +
                    "«Устройство» — только пакеты этого устройства: у каждого может быть своё (Paramnezia подбирает их для каждой сети сама).", PnIcons.Info)
            }
        }

        if (view.ssh != null) {
            SectionTitle("Сервер") { if (loading) Text("обновляю…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (loading && status == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            statusError?.let { Hint("Сервер не ответил: $it", PnIcons.Warning, Brand.Amber) }
            status?.let { st -> ServerReport(st, now) }
        } else {
            Hint("Сведения самого сервера (движок, люди, нагрузка) видны на устройствах с правами администратора.", PnIcons.Info)
        }
        Spacer(Modifier.height(Space.M))
    }
}

@Composable
private fun ServerReport(st: ServerStatus, now: Long) {
    Group {
        val engineAge = st.imageBuilt?.let { " · сборка ${date(it)}" }.orEmpty()
        Info("Движок AmneziaWG", (st.engine ?: "—") + engineAge, hint = st.tools)
        val container = st.container
        Info("Контейнер", when (container) {
            "running" -> "работает" + st.startedAt?.let { " · запущен ${ago(now - it.toEpochMilli())}" }.orEmpty()
            "missing" -> "не найден"
            null -> "—"
            else -> container
        }, accent = if (container == "running") Brand.Mint else Brand.Coral)
        if (st.peers != null) Info("Устройства", "${st.peers}" + (st.activePeers?.let { " · сейчас подключены $it" } ?: ""))
        val rx = st.rxBytes; val tx = st.txBytes
        if (rx != null && tx != null) Info("Трафик сервера", "↓ ${formatBytes(rx)}  ↑ ${formatBytes(tx)}", hint = "с последнего запуска контейнера")
        if (st.mtu != null) Info("MTU интерфейса", "${st.mtu} байт" + (st.iface?.let { " · $it" } ?: ""))
        if (st.ports.isNotEmpty()) Info("Открытые порты VPN", st.ports.joinToString(", ") + (st.ipv6?.let { " · IPv6" } ?: ""))
        Info("Настройка под поток", if (st.tuned) "включена · ${st.congestion ?: ""}".trimEnd(' ', '·') else "ещё нет — применится при самопроверке",
            accent = if (st.tuned) Brand.Mint else Brand.Amber, hint = "согласование размера TCP, буферы, BBR")
    }
    Group {
        Info("Система", listOfNotNull(st.os, st.kernel).joinToString(" · ").ifEmpty { "—" })
        st.load?.let { load -> Info("Нагрузка", load + (st.cpus?.let { " · ядер: $it" } ?: ""), mono = true) }
        if (st.memTotalMb != null) Info("Память", "свободно ${st.memFreeMb ?: 0} из ${st.memTotalMb} МБ")
        st.uptimeSec?.let { Info("Работает без перезагрузки", uptimeText(it)) }
        st.dockerVersion?.let { Info("Docker", it, mono = true) }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(PnIcons.ArrowLeft, "Назад") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing?.invoke()
    }
}

/**
 * One fact: label above, value below (monospace for addresses, keys and numbers), an optional
 * explanation, and who has to agree on it. A tap copies when [onClick] is set.
 */
@Composable
private fun Info(
    label: String,
    value: String,
    hint: String? = null,
    mono: Boolean = false,
    scope: ParamCatalog.Scope? = null,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.then(if (onClick != null) Modifier.tactile(onClick = onClick) else Modifier)
                .heightIn(min = 56.dp).padding(horizontal = Space.M, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value, style = MaterialTheme.typography.bodyLarge, color = accent ?: MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (scope != null) {
                Spacer(Modifier.width(Space.S))
                Pill(scope.label, if (scope == ParamCatalog.Scope.SERVER) Brand.Violet else Brand.Cyan)
            }
        }
    }
}

private fun mimicText(m: Camouflage.Mimic): String = when (m) {
    Camouflage.Mimic.STUN -> "STUN (как звонок WebRTC)"
    Camouflage.Mimic.WEBRTC -> "WebRTC: STUN и DTLS"
    Camouflage.Mimic.PLAIN -> "без подписи"
}

/** A Camouflage variant id (`stun@host:port`) in words. */
internal fun variantText(id: String): String {
    if (id.isEmpty()) return "как в настройках сервера"
    val mimic = Camouflage.Mimic.of(id.substringBefore('@'))?.let(::mimicText) ?: id.substringBefore('@')
    val endpoint = id.substringAfter('@', "")
    val port = endpoint.substringAfterLast(':', "")
    val v6 = endpoint.startsWith("[")
    return listOf(mimic, if (port.isNotEmpty()) "порт $port" else null, if (v6) "IPv6" else null).filterNotNull().joinToString(" · ")
}

private fun keepaliveText(ka: String): String = when (ka.trim()) {
    "", "0" -> "выключен (экономия батареи)"
    else -> "каждые ${ka.replace("-", "–")} с"
}

private fun uptimeText(sec: Long): String {
    val d = sec / 86_400; val h = sec / 3600 % 24; val m = sec / 60 % 60
    return when {
        d > 0 -> "$d дн. $h ч"
        h > 0 -> "$h ч $m мин"
        else -> "$m мин"
    }
}

private val MONTHS = listOf("янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")

/** "16 нояб. 2026" without the JDK's locale data (absent on Windows). */
private fun date(i: Instant): String {
    val d = i.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
}
