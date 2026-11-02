package net.paramnezia.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.paramnezia.app.App
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.data.BatteryMode
import net.paramnezia.app.data.Settings
import net.paramnezia.app.data.UiMode
import net.paramnezia.core.routing.AppRules
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: App, modifier: Modifier, toast: (String) -> Unit) {
    val state by app.repo.state.collectAsState()
    val s = state.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(false) }

    fun change(f: (Settings) -> Settings) {
        app.repo.update { it.copy(settings = f(it.settings)) }
        app.reconnectIfUp()
    }

    if (apps) { AppsScreen(app, modifier) { apps = false }; return }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Маршрутизация", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Toggle("Российские сайты и приложения напрямую", "Банки, Госуслуги, маркетплейсы, Яндекс, VK, Max и другие идут мимо VPN", s.bypassRussia) { v -> change { it.copy(bypassRussia = v) } }
        Toggle("Локальная сеть напрямую", "Роутер, принтер, телевизор остаются доступны", s.bypassLan) { v -> change { it.copy(bypassLan = v) } }
        OutlinedButton(onClick = { apps = true }) { Text("Выбрать приложения…") }

        HorizontalDivider()
        Text("Приватность", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Toggle("Блокировать рекламу и трекеры", "Яндекс Метрика, AppMetrica, AdFox, реклама VK и Mail.ru, Firebase и другие, в том числе для российских сайтов", s.blockTrackers) { v -> change { it.copy(blockTrackers = v) } }
        Text("Приложения, которые целиком идут мимо VPN (банки и т. п.), не проходят через этот фильтр. Для них можно включить системный " +
            "«Частный DNS» с фильтрацией, например dns.adguard-dns.com.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS")) }
                .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS)) }
        }) { Text("Открыть «Частный DNS»") }

        HorizontalDivider()
        Text("Батарея", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        BatteryOption("Максимальная экономия", "Без фоновых пакетов. Входящие уведомления могут приходить с задержкой.", BatteryMode.MAX_SAVING, s.battery) { m -> change { it.copy(battery = m) } }
        BatteryOption("Сбалансированный", "Редкие пакеты при выключенном экране", BatteryMode.BALANCED, s.battery) { m -> change { it.copy(battery = m) } }
        BatteryOption("Надёжный", "Соединение всегда «тёплое», расход чуть выше", BatteryMode.RELIABLE, s.battery) { m -> change { it.copy(battery = m) } }

        HorizontalDivider()
        Text("Система", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Чтобы VPN включался сам после перезагрузки, включите для Paramnezia «Постоянную VPN» в настройках Android.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }) { Text("Настройки VPN Android") }

        HorizontalDivider()
        Text("Интерфейс", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        for ((mode, title, sub) in listOf(
            Triple(UiMode.AUTO, "Автоматически", "Простой на устройствах, приглашённых в семью, полный на остальных"),
            Triple(UiMode.SIMPLE, "Простой", "Одна кнопка. Полный режим: долгое нажатие на заголовок"),
            Triple(UiMode.FULL, "Полный", "Все настройки"),
        )) {
            Row(Modifier.fillMaxWidth().clickable { app.repo.update { it.copy(uiMode = mode) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.uiMode == mode, { app.repo.update { it.copy(uiMode = mode) } })
                Column {
                    Text(title)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        val managed = state.profiles.any { it.ssh != null } || state.fleet?.serverAccess?.isNotEmpty() == true
        if (managed) {
            HorizontalDivider()
            Text("Резервная копия", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Ключи ваших серверов с полным доступом (SSH). Сохраните их в надёжном месте до переустановки приложения: " +
                "по ним серверы снова добавляются сюда или в AmneziaVPN. Никому их не отправляйте.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                val text = app.servers.exportAllWithAccess()
                if (text.isEmpty()) toast("Нет серверов с полным доступом")
                else context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Сохранить ключи"))
            }) { Text("Сохранить ключи серверов") }
        }

        HorizontalDivider()
        Text("Обновления", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (app.updater.enabled) {
            Text("Приложение раз в 12 часов проверяет, есть ли новая версия, подписанная ключом релизов. Скачивает её браузер по вашему нажатию, " +
                "устанавливает Android с вашим подтверждением.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch {
                    app.updater.check()
                    if (app.updater.status.value is net.paramnezia.app.ota.Updater.Status.Available) app.updater.openDownload()
                    else toast("Установлена последняя версия")
                }
            }) { Text("Проверить обновления") }
        } else {
            Text("Эта сборка без ключа релизов: проверка обновлений выключена.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
    }
}

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
private fun BatteryOption(title: String, subtitle: String, mode: BatteryMode, current: BatteryMode, onPick: (BatteryMode) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPick(mode) }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(mode == current, { onPick(mode) })
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Per-app choice: automatic (by the bundled list), always through VPN, or always direct. */
@Composable
private fun AppsScreen(app: App, modifier: Modifier, onBack: () -> Unit) {
    val state by app.repo.state.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val rules = remember { AppRules.parse(context.assets.open("bypass/apps.txt").bufferedReader().use { it.readText() }) }
    val all = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != context.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }
    val s = state.settings

    fun set(pkg: String, mode: Int) {
        app.repo.update { st ->
            val t = st.settings.forceTunnel - pkg; val d = st.settings.forceDirect - pkg
            st.copy(settings = st.settings.copy(
                forceTunnel = if (mode == 1) t + pkg else t,
                forceDirect = if (mode == 2) d + pkg else d,
            ))
        }
        app.reconnectIfUp()
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Приложения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Поиск") }, singleLine = true)
        LazyColumn {
            items(all.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }, key = { it.first }) { (pkg, label) ->
                val direct = pkg in s.forceDirect || (pkg !in s.forceTunnel && s.bypassRussia && rules.matches(pkg))
                val mode = when (pkg) { in s.forceTunnel -> 1; in s.forceDirect -> 2; else -> 0 }
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(label)
                    Text(if (direct) "напрямую" else "через VPN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Row {
                        for ((m, title) in listOf(0 to "Авто", 1 to "VPN", 2 to "Напрямую")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(mode == m, { set(pkg, m) })
                                Text(title, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
