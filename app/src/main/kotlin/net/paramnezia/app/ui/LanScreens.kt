package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import net.paramnezia.app.App
import net.paramnezia.app.lan.LanFile
import net.paramnezia.app.lan.LanPeer
import net.paramnezia.app.lan.LanServer

/*
 * Sharing in the local network (docs/LAN-SHARE.md): the sharer's screen («Раздать по Wi-Fi») and,
 * on the other side, the offer to take settings from a sharer found nearby.
 */

/**
 * «Раздать по Wi-Fi»: while this is open, devices in the same network get the app at a short
 * address (or find this device by themselves) and ask for settings, which the owner allows here.
 */
@Composable
fun LanShareDialog(app: App, onDismiss: () -> Unit, toast: (String) -> Unit) {
    LaunchedEffect(Unit) { runCatching { app.lan.start() }.onFailure { toast(it.message ?: "Раздача не открылась") } }
    DisposableEffect(Unit) { onDispose { app.lan.stop() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LanSharePanel(app, onDismiss, toast)
    }
}

/** The share's content (separate from the dialog for the screenshots). */
@Composable
fun LanSharePanel(app: App, onDismiss: () -> Unit, toast: (String) -> Unit) {
    val sharing by app.lan.sharing.collectAsState()
    val preparing by app.lan.preparing.collectAsState()
    val scope = rememberCoroutineScope()
    var approving by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    run {
        Surface(
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(Space.M).widthIn(max = 640.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(Space.L - 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val s = sharing
                Text("Раздача по Wi-Fi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (s?.settings == true) "Пока открыт этот экран, телевизор, компьютер или телефон в этой же сети получат Paramnezia и настройки семьи. Интернет для этого не нужен."
                    else "Пока открыт этот экран, телевизор, компьютер или телефон в этой же сети получат Paramnezia. Интернет для этого не нужен.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s == null) {
                    Text(preparing?.let { "Готовлю: $it" } ?: "Открываю раздачу…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (s.error != null) {
                    Hint(s.error.orEmpty(), Icons.Filled.Warning, Brand.Coral)
                } else if (s.addresses.isEmpty()) {
                    Hint("Нет подключения к Wi-Fi или проводной сети. Подключитесь к той же сети, что и телевизор или компьютер, или включите точку доступа и подключите их к ней.",
                        Icons.Filled.Warning, Brand.Amber)
                } else {
                    AddressCard(s.addresses.first(), s.addresses.drop(1))
                    // Asks first: someone is waiting for an answer right now.
                    for (ask in s.asks) AskRow(ask, approving == ask.id,
                        onAllow = {
                            approving = ask.id
                            scope.launch {
                                runCatching { app.lan.approve(ask) }
                                    .onSuccess { toast("«${ask.device}» получил настройки") }
                                    .onFailure { toast(it.message ?: "Не удалось выдать настройки") }
                                approving = null
                            }
                        },
                        onDeny = { app.lan.deny(ask) })
                    Files(s.apk, s.exe, s.served)
                    if (s.given.isNotEmpty()) Hint("Получили настройки: ${s.given.joinToString()}", Icons.Filled.CheckCircle, Brand.Mint)
                    ExpandTile(Icons.Filled.Info, "Как поставить на телевизор", "Без браузера и длинных адресов", help, onToggle = { help = !help }) {
                        HowTo(s.addresses.first(), s.settings, LocalUiPlatform.current.deviceWhere)
                    }
                    Hint("Адрес работает только в этой сети. Настройки получает лишь то устройство, которому вы нажмёте «Разрешить».", Glyph.Shield)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Закрыть раздачу") }
                }
            }
        }
    }
}

@Composable
private fun AddressCard(main: String, others: List<String>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.M), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text("Откройте на другом устройстве", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FitText(main, MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), min = 18.sp,
                color = MaterialTheme.colorScheme.primary, align = TextAlign.Center)
            Text("в браузере, Downloader или файловом менеджере · или наведите камеру телефона",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White).padding(4.dp)) {
                AddressQr("http://$main/")
            }
            if (others.isNotEmpty()) Text("Другая сеть этого устройства: ${others.joinToString()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AskRow(ask: LanServer.Ask, busy: Boolean, onAllow: () -> Unit, onDeny: () -> Unit) {
    val what = when (ask.kind) { "tv" -> "Телевизор"; "windows" -> "Компьютер"; "android" -> "Телефон или планшет"; else -> "Браузер" }
    Surface(color = Brand.Violet.copy(alpha = 0.12f), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Glyph.PersonAdd, null, tint = Brand.Violet)
                Spacer(Modifier.width(Space.S + 4.dp))
                Column(Modifier.weight(1f)) {
                    Text("«${ask.device}» просит настройки", style = MaterialTheme.typography.titleMedium)
                    Text("$what · ${ask.from}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Button(enabled = !busy, onClick = onAllow, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Разрешить") }
                OutlinedButton(enabled = !busy, onClick = onDeny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Отклонить") }
            }
        }
    }
}

@Composable
private fun Files(apk: LanFile?, exe: LanFile?, served: Map<String, Int>) {
    Group {
        GroupRow(
            "Android и Android TV", subtitle = apk?.let { "Paramnezia ${it.version}" + (served["apk"]?.let { n -> " · скачали $n" } ?: "") }
                ?: "Нет копии: откройте раздачу, когда будет интернет",
            icon = Glyph.Phone, accent = if (apk != null) Brand.Mint else Brand.Amber,
        )
        GroupRow(
            "Windows", subtitle = exe?.let { "Установщик ${it.version}" + (served["exe"]?.let { n -> " · скачали $n" } ?: "") }
                ?: "Нет копии: откройте раздачу, когда будет интернет",
            icon = Glyph.Cpu, accent = if (exe != null) Brand.Mint else Brand.Amber,
        )
    }
}

@Composable
private fun HowTo(address: String, settings: Boolean, where: String) {
    val steps = listOf(
        "Если на телевизоре есть браузер, откройте в нём $address.",
        "Если браузера нет: в Google Play на телевизоре установите бесплатное приложение «Downloader» и введите в нём $address. " +
            "Цифры удобнее набирать с телефона: приложение Google TV → пульт → клавиатура.",
        "Нажмите «Скачать Paramnezia», затем «Установить». Если Android спросит, разрешите установку из этого приложения.",
        if (settings) "Откройте Paramnezia на телевизоре: она сама найдёт это устройство и попросит настройки. Нажмите «Разрешить» здесь, и VPN заработает."
        else "Откройте Paramnezia на телевизоре и добавьте ключ.",
    )
    steps.forEachIndexed { i, t ->
        Row {
            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
            Text(t, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text("Компьютер: откройте адрес в браузере и скачайте установщик. Если Paramnezia там уже стоит, она найдёт раздачу на $where сама.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Asking a sharer for settings: who we wait for, and the call that asks (then imports the key). */
private class LanAsking(val waiting: LanPeer?, val ask: (LanPeer) -> Unit)

@Composable
private fun rememberLanAsk(app: App, toast: (String) -> Unit, onDone: () -> Unit): LanAsking {
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<LanPeer?>(null) }
    return LanAsking(waiting) { peer ->
        if (waiting == null) {
            waiting = peer
            scope.launch {
                runCatching { app.servers.import(app.lan.receive(peer)) }
                    .onSuccess { toast("Настройки получены от ${peer.name}"); onDone() }
                    .onFailure { toast(it.message ?: "Не получилось") }
                waiting = null
            }
        }
    }
}

@Composable
private fun Waiting(peer: LanPeer?) {
    peer ?: return
    Hint("Запрос отправлен. Нажмите «Разрешить» на ${peer.name}.", Glyph.Wifi, MaterialTheme.colorScheme.primary)
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

/**
 * Settings from a sharer in this network, one tap: a big button for each device nearby that hands
 * out settings (looked for while this is shown; nothing is shown while there is none).
 */
@Composable
fun NearbyOffers(app: App, toast: (String) -> Unit, onDone: () -> Unit) {
    val nearby by app.lan.nearby.collectAsState()
    val asking = rememberLanAsk(app, toast, onDone)
    DisposableEffect(Unit) {
        val watch = app.lan.watch()
        onDispose { watch.close() }
    }
    val givers = nearby.filter { it.info?.settings == true }
    if (givers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (peer in givers) {
            ActionTile(Glyph.Wifi, "Получить настройки от ${peer.name}", "рядом, в этой сети · ${peer.address}", primary = true) { asking.ask(peer) }
        }
        Waiting(asking.waiting)
    }
}

/** «Из раздачи по Wi-Fi» by address, for networks where devices do not see each other (guest Wi-Fi, some routers). */
@Composable
fun LanAddressTile(app: App, toast: (String) -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val asking = rememberLanAsk(app, toast, onDone)
    var open by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    ExpandTile(Glyph.Wifi, "По адресу раздачи", "Адрес с экрана «Раздать по Wi-Fi» на другом устройстве", open, onToggle = { open = !open }) {
        PasteField(
            address, { address = it; error = null }, "Адрес раздачи", placeholder = "192.168.1.5:8080",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, mono = true,
            supporting = error ?: "Устройства должны быть в одной сети",
            isError = error != null, enabled = asking.waiting == null && !checking,
        )
        Button(
            enabled = asking.waiting == null && !checking && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                checking = true
                scope.launch {
                    val peer = app.lan.peerAt(address)
                    checking = false
                    when {
                        peer == null -> error = "По этому адресу нет раздачи Paramnezia"
                        peer.info?.settings != true -> error = "Здесь раздают только приложение, без настроек"
                        else -> asking.ask(peer)
                    }
                }
            },
        ) { Text("Получить настройки") }
        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        Waiting(asking.waiting)
    }
}
