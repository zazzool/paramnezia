package net.paramnezia.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paramnezia.app.App
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.data.BatteryMode
import net.paramnezia.app.data.Settings
import net.paramnezia.app.data.UiMode
import net.paramnezia.app.ota.Updater

/**
 * Settings on Windows: the same routing, privacy and interface choices as on Android (so a fleet
 * policy means the same everywhere), plus what only Windows has: start with Windows and the
 * service. Programs past the VPN are the Windows form of Android's per-app choice: the service
 * decides per connection by the program that opened it (tunnel/src/main/go/splitapp).
 */
@Composable
fun SettingsScreen(app: App, platform: DesktopUiPlatform, modifier: Modifier, toast: (String) -> Unit, askService: Boolean = true) {
    val state by app.repo.state.collectAsState()
    val s = state.settings
    val scope = rememberCoroutineScope()
    var autostart by remember { mutableStateOf(Autostart.enabled()) }
    var service by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        service = if (!askService) "3.1" else withContext(Dispatchers.IO) {
            runCatching { app.client.call(ServiceClient.Request("version")).version }.getOrNull()
        }
    }
    val follower = state.fleet != null && state.fleet?.adminSeed == null

    fun change(f: (Settings) -> Settings) {
        app.repo.update { it.copy(settings = f(it.settings)) }
        app.reconnectIfUp()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Маршрутизация")
        if (follower) Hint("Эти настройки приходят от администратора флота.")
        Toggle("Российские сайты напрямую", "Банки, Госуслуги, маркетплейсы, Яндекс, VK и другие идут мимо VPN", s.bypassRussia) { v -> change { it.copy(bypassRussia = v) } }
        Toggle("Локальная сеть напрямую", "Роутер, принтер, общие папки и телевизор остаются доступны", s.bypassLan) { v -> change { it.copy(bypassLan = v) } }
        ProgramsSection(s.forceDirect, enabled = !follower, pick = { platform.pickProgram() }) { f -> change { it.copy(forceDirect = f(it.forceDirect)) } }

        HorizontalDivider()
        Section("Приватность")
        Toggle("Блокировать рекламу и трекеры", "Яндекс Метрика, AdFox, реклама VK и Mail.ru, Firebase и другие, во всех программах и браузерах", s.blockTrackers) { v -> change { it.copy(blockTrackers = v) } }
        Hint("Пока VPN включён, Windows спрашивает адреса сайтов только у Paramnezia: провайдер не видит, какие сайты вы открываете, и не может подменить ответ.")

        HorizontalDivider()
        Section("Соединение")
        Option("Экономное", "Без фоновых пакетов. Для ноутбука от батареи; после простоя первое соединение чуть дольше.", s.battery == BatteryMode.MAX_SAVING) { change { it.copy(battery = BatteryMode.MAX_SAVING) } }
        Option("Сбалансированное", "Редкие пакеты, чтобы соединение не засыпало", s.battery == BatteryMode.BALANCED) { change { it.copy(battery = BatteryMode.BALANCED) } }
        Option("Надёжное", "Соединение всегда «тёплое»: для звонков и игр", s.battery == BatteryMode.RELIABLE) { change { it.copy(battery = BatteryMode.RELIABLE) } }

        HorizontalDivider()
        Section("Windows")
        Toggle("Запускать при входе в Windows", "Paramnezia стартует свёрнутой в трей и включает VPN, если он был включён", autostart) { v ->
            runCatching { Autostart.set(v) }.onSuccess { autostart = Autostart.enabled() }.onFailure { toast("Не удалось: ${it.message}") }
        }
        Text(
            when (service) {
                null -> "Служба Paramnezia не отвечает. Без неё VPN не включится: переустановите приложение."
                else -> "Служба Paramnezia работает (AmneziaWG $service). Закрытое окно сворачивается в трей, VPN продолжает работать."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (service == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )

        HorizontalDivider()
        Section("Интерфейс")
        for ((mode, title, sub) in listOf(
            Triple(UiMode.AUTO, "Автоматически", "Простой на ведомых устройствах флота, полный на остальных"),
            Triple(UiMode.SIMPLE, "Простой", "Одна кнопка. Полный режим: долгое нажатие на заголовок"),
            Triple(UiMode.FULL, "Полный", "Все настройки"),
        )) {
            Option(title, sub, state.uiMode == mode) { app.repo.update { it.copy(uiMode = mode) } }
        }

        val managed = state.profiles.any { it.ssh != null } || state.fleet?.serverAccess?.isNotEmpty() == true
        if (managed) {
            HorizontalDivider()
            Section("Резервная копия")
            Hint("Ключи ваших серверов с полным доступом (SSH). Сохраните их в надёжном месте до переустановки: по ним серверы снова " +
                "добавляются сюда, в Paramnezia на телефоне или в AmneziaVPN. Никому их не отправляйте.")
            OutlinedButton(onClick = {
                val text = app.servers.exportAllWithAccess()
                if (text.isEmpty()) toast("Нет серверов с полным доступом") else platform.shareFile("paramnezia-servers", text)
            }) { Text("Сохранить ключи серверов в файл") }
        }

        HorizontalDivider()
        Section("Обновления")
        if (app.updater.enabled) {
            Hint("Раз в 12 часов приложение проверяет, есть ли новая версия, подписанная ключом релизов. Установщик скачивает браузер по вашему нажатию.")
            OutlinedButton(onClick = {
                scope.launch {
                    app.updater.check()
                    if (app.updater.status.value is Updater.Status.Available) app.updater.openDownload() else toast("Установлена последняя версия")
                }
            }) { Text("Проверить обновления") }
        } else {
            Hint("Эта сборка без ключа релизов: проверка обновлений выключена.")
        }
        Text("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) для Windows", style = MaterialTheme.typography.bodySmall)
    }
}

/** Programs past the VPN: the list, "choose a program" and a pick from what is running now. */
@Composable
private fun ProgramsSection(forceDirect: Set<String>, enabled: Boolean, pick: () -> String?, edit: ((Set<String>) -> Set<String>) -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val list = Programs.excluded(forceDirect)
    Text("Программы мимо VPN", Modifier.padding(top = 4.dp))
    Hint("Эти программы ходят в интернет напрямую, с вашим обычным адресом, даже когда VPN включён: например, игры, банк-клиент или торрент. Остальные идут через VPN.")
    for (entry in list) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Programs.label(entry))
                Text(entry, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (enabled) TextButton(onClick = { edit { Programs.remove(it, entry) } }) { Text("Убрать") }
        }
    }
    if (!enabled) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pick()?.let { e -> edit { Programs.add(it, e) } } }) { Text("Выбрать программу…") }
        OutlinedButton(onClick = {
            if (running != null) running = null
            else scope.launch { running = withContext(Dispatchers.IO) { runCatching { Programs.running() }.getOrDefault(emptyList()) } }
        }) { Text(if (running == null) "Из запущенных" else "Скрыть") }
    }
    running?.let { all ->
        val left = all.filter { (e, _) -> list.none { it.equals(e, ignoreCase = true) } }
        if (left.isEmpty()) Hint("Других запущенных программ не нашлось.")
        for ((entry, path) in left) {
            Row(Modifier.fillMaxWidth().clickable { edit { Programs.add(it, entry) } }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Programs.label(entry))
                    Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                }
                Text("Добавить", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Option(title: String, subtitle: String, selected: Boolean, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onPick)
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
