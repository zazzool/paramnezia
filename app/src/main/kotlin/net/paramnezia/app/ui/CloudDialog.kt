@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.paramnezia.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.paramnezia.app.App
import net.paramnezia.app.data.Cloud
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ServerManager
import net.paramnezia.deploy.cloud.CloudPlan
import net.paramnezia.deploy.cloud.CloudProviders
import net.paramnezia.deploy.cloud.CloudRegion

/**
 * «Купить сервер у хостера»: a token once, then location and price on one screen and a single
 * «Создать» tap. The cheapest plan that fits is preselected; the region last used comes first.
 */
@Composable
fun CloudBuyDialog(
    app: App,
    onDismiss: () -> Unit,
    toast: (String) -> Unit,
    install: suspend (name: String, ssh: SshCredentials, onStep: (ServerManager.Step) -> Unit) -> Unit,
) {
    val state by app.repo.state.collectAsState()
    val job by app.cloud.job.collectAsState()
    val scope = rememberCoroutineScope()
    val cloud = state.cloud
    val withToken = CloudProviders.all.filter { it.id in cloud.tokens }
    var provider by remember { mutableStateOf(cloud.lastProvider?.takeIf { it in cloud.tokens } ?: withToken.firstOrNull()?.id) }
    var addingToken by remember { mutableStateOf(withToken.isEmpty()) }
    var regions by remember { mutableStateOf<List<CloudRegion>?>(null) }
    var region by remember { mutableStateOf<CloudRegion?>(null) }
    var plans by remember { mutableStateOf<List<CloudPlan>?>(null) }
    var plan by remember { mutableStateOf<CloudPlan?>(null) }
    var showAllPlans by remember { mutableStateOf(false) }
    var showRussian by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(provider, addingToken) {
        val p = provider ?: return@LaunchedEffect
        if (addingToken) return@LaunchedEffect
        regions = null; region = null; plans = null; plan = null; error = null
        runCatching { app.cloud.regions(p) }
            .onSuccess { list -> regions = list; region = list.firstOrNull { it.id == cloud.lastRegion } ?: list.firstOrNull { !it.inRussia } ?: list.firstOrNull() }
            .onFailure { error = Cloud.describe(it) }
    }
    LaunchedEffect(region) {
        val p = provider ?: return@LaunchedEffect
        val r = region ?: return@LaunchedEffect
        plans = null; plan = null
        runCatching { app.cloud.plans(p, r.id) }
            .onSuccess { list -> plans = list; plan = CloudProviders.suggest(list) }
            .onFailure { error = Cloud.describe(it) }
    }

    val running = job?.let { !it.done && it.error == null } == true

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Сервер у хостера") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val j = job
                when {
                    j != null -> JobView(app, j, scope, toast)
                    addingToken -> TokenForm(app, provider, busy, onBusy = { busy = it }, onSaved = { id -> provider = id; addingToken = false }, onCancel = if (withToken.isEmpty()) null else ({ addingToken = false }))
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (p in withToken) FilterChip(selected = provider == p.id, onClick = { provider = p.id }, label = { Text(p.name) })
                            FilterChip(selected = false, onClick = { addingToken = true }, label = { Text("+ хостер") })
                        }
                        val rs = regions
                        if (rs == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (rs != null) {
                            Text("Где разместить", style = MaterialTheme.typography.labelLarge)
                            val visible = rs.filter { showRussian || !it.inRussia || it == region }
                            for (r in visible) Choice(region == r, r.name + if (r.inRussia) " (Россия: не поможет обойти блокировки)" else "") { region = r }
                            if (!showRussian && rs.any { it.inRussia }) TextButton(onClick = { showRussian = true }) { Text("Показать локации в России") }
                        }
                        val ps = plans
                        if (region != null && ps == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (ps != null) {
                            if (ps.isEmpty()) Text("Здесь сейчас нет тарифов, выберите другое место.")
                            val suggested = CloudProviders.suggest(ps)
                            val shown = if (showAllPlans) ps else listOfNotNull(plan)
                            Text("Тариф", style = MaterialTheme.typography.labelLarge)
                            for (p in shown) Choice(plan == p, planLabel(p) + if (p == suggested) " · рекомендую" else "") { plan = p }
                            if (!showAllPlans && ps.size > 1) TextButton(onClick = { showAllPlans = true }) { Text("Другие тарифы") }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("Хостер спишет деньги со своего баланса по своим тарифам. Сервер появится в вашем аккаунте у хостера; удалить его можно и оттуда, и из приложения.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = {
            val j = job
            when {
                j != null && (j.done || j.error != null) -> TextButton(onClick = { app.cloud.dismissJob(); if (j.done) onDismiss() }) { Text(if (j.done) "Готово" else "Закрыть") }
                j != null -> TextButton(onClick = onDismiss) { Text("Свернуть") }
                !addingToken -> {
                    val p = plan; val r = region; val pr = provider
                    TextButton(enabled = p != null && r != null && pr != null && !running, onClick = {
                        app.cloud.buy(pr!!, r!!, p!!, install)
                    }) { Text(if (p != null) "Создать за ${p.priceText()}" else "Создать") }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (job == null) TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") }
        },
    )
}

private fun planLabel(p: CloudPlan): String {
    val ram = if (p.ramMb >= 1024) "${p.ramMb / 1024} ГБ" else "${p.ramMb} МБ"
    val disk = if (p.diskGb > 0) ", диск ${p.diskGb} ГБ" else ""
    return "${p.cpu} vCPU, $ram$disk — ${p.priceText()}"
}

@Composable
private fun Choice(selected: Boolean, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TokenForm(app: App, initial: String?, busy: Boolean, onBusy: (Boolean) -> Unit, onSaved: (String) -> Unit, onCancel: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var id by remember { mutableStateOf(initial ?: CloudProviders.all.first().id) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val info = CloudProviders.info(id)!!
    Text("Приложение создаст сервер через API хостера и само установит на него AmneziaWG. Нужен токен API один раз; он хранится зашифрованным только на этом телефоне.",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in CloudProviders.all) FilterChip(selected = id == p.id, onClick = { id = p.id; error = null }, label = { Text(p.name) })
    }
    Text(info.tokenHint, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { runCatching { uri.openUri(info.tokenUrl) } }) { Text("Открыть панель ${info.name}") }
    OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Токен API") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = token.isNotBlank() && !busy, onClick = {
            onBusy(true); error = null
            scope.launch {
                runCatching { app.cloud.saveToken(id, token) }.onSuccess { onSaved(id) }.onFailure { error = Cloud.describe(it) }
                onBusy(false)
            }
        }) { Text("Проверить и сохранить") }
        if (onCancel != null) TextButton(enabled = !busy, onClick = onCancel) { Text("Назад") }
    }
    if (app.cloud.hasToken(id)) TextButton(enabled = !busy, onClick = { app.cloud.forgetToken(id) }) { Text("Забыть токен ${info.name}") }
}

@Composable
private fun JobView(app: App, j: Cloud.Job, scope: kotlinx.coroutines.CoroutineScope, toast: (String) -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    Text(j.label, fontWeight = if (j.done) FontWeight.Bold else FontWeight.Normal)
    if (!j.done && j.error == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Обычно 3–8 минут. Можно свернуть окно: работа продолжится, пока приложение открыто.", style = MaterialTheme.typography.bodySmall)
    }
    j.server?.host?.let { Text("IP: $it", style = MaterialTheme.typography.bodySmall) }
    val err = j.error
    if (err != null) {
        Text(err, color = MaterialTheme.colorScheme.error)
        val s = j.server
        if (s != null) {
            Text("Сервер уже создан у хостера и оплачивается. Удалите его, если не будете пробовать снова.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !deleting, onClick = {
                deleting = true
                scope.launch {
                    runCatching { app.cloud.delete(s) }.onSuccess { toast("Сервер удалён у хостера") }.onFailure { toast(Cloud.describe(it)) }
                    deleting = false
                }
            }) { Text("Удалить сервер у хостера") }
        }
    }
}
