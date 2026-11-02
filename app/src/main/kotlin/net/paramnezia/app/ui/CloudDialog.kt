@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import net.paramnezia.app.App
import net.paramnezia.app.data.Cloud
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ServerManager
import net.paramnezia.deploy.cloud.CloudCatalog
import net.paramnezia.deploy.cloud.CloudPlan
import net.paramnezia.deploy.cloud.CloudProviders
import net.paramnezia.deploy.cloud.CloudRegion
import net.paramnezia.deploy.cloud.Geo
import net.paramnezia.deploy.cloud.ProviderInfo

/*
 * «Новый сервер у хостера», a full screen in three layers:
 *  1. One tap. The recommended place (last used, else the foreign one closest to Russia) and the
 *     cheapest plan that fits AmneziaWG are already chosen; the big button at the bottom buys.
 *  2. One more tap opens the places (flags, grouped by part of the world, price from) or the plans
 *     (cards with CPU, memory, disk and price) in place.
 *  3. Hosters: any number connected by API token, switched with the strip at the top.
 * Catalogs come from the phone's cache at once and are refreshed behind (see Cloud.prefetch).
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
    val catalogs by app.cloud.catalogs.collectAsState()
    val cloud = state.cloud
    val connected = CloudProviders.all.filter { it.id in cloud.tokens }
    var provider by remember { mutableStateOf(cloud.lastProvider?.takeIf { it in cloud.tokens } ?: connected.firstOrNull()?.id) }
    var addingToken by remember { mutableStateOf(connected.isEmpty()) }
    var busy by remember { mutableStateOf(false) }

    // A hoster whose token was just removed is no longer offered.
    val current = provider?.takeIf { it in cloud.tokens } ?: connected.firstOrNull()?.id
    val showProviders = addingToken || current == null

    LaunchedEffect(Unit) { app.cloud.prefetch() }
    LaunchedEffect(current) { current?.let { app.cloud.refresh(it) } }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                val j = job
                val title = when {
                    j != null -> "Создаю сервер"
                    showProviders -> "Хостеры"
                    else -> "Новый сервер"
                }
                TopBar(
                    title,
                    onClose = { if (!busy) { if (showProviders && current != null) addingToken = false else onDismiss() } },
                    onRefresh = if (j == null && !showProviders) ({ app.cloud.refresh(current!!, force = true) }) else null,
                    refreshing = current?.let { catalogs[it]?.loading } == true,
                )
                when {
                    j != null -> JobScreen(app, j, toast, onClose = { app.cloud.dismissJob(); if (j.done) onDismiss() }, onHide = onDismiss)
                    showProviders -> ProvidersScreen(app, provider, busy, onBusy = { busy = it }, onSaved = { id -> provider = id; addingToken = false })
                    else -> PickScreen(
                        app, current!!, connected, catalogs[current] ?: Cloud.Catalog(loading = true), cloud.lastRegion,
                        onProvider = { provider = it }, onAddProvider = { addingToken = true }, install = install,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onClose: () -> Unit, onRefresh: (() -> Unit)?, refreshing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (onRefresh != null) {
            if (refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Обновить цены") }
        }
    }
}

// ---------------------------------------------------------------- picking

@Composable
private fun PickScreen(
    app: App,
    provider: String,
    connected: List<ProviderInfo>,
    cat: Cloud.Catalog,
    lastRegion: String?,
    onProvider: (String) -> Unit,
    onAddProvider: () -> Unit,
    install: suspend (name: String, ssh: SshCredentials, onStep: (ServerManager.Step) -> Unit) -> Unit,
) {
    val data = cat.data
    val rec = remember(data, lastRegion) { data?.let { CloudProviders.recommend(it, lastRegion) } }
    var regionId by remember(provider) { mutableStateOf<String?>(null) }
    var planId by remember(provider) { mutableStateOf<String?>(null) }
    var openPlaces by remember(provider) { mutableStateOf(false) }
    var openPlans by remember(provider) { mutableStateOf(false) }
    val region = data?.regions?.firstOrNull { it.id == regionId } ?: rec?.region
    val plans = region?.let { data?.plansIn(it.id) }.orEmpty()
    val suggested = CloudProviders.suggest(plans)
    val plan = plans.firstOrNull { it.id == planId } ?: suggested
    var exact by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(provider, region?.id, plan?.id) {
        exact = null
        if (plan != null) exact = app.cloud.exactPrice(provider, plan)
    }
    val priced = plan?.let { p -> exact?.let { p.copy(monthly = it) } ?: p }
    val info = CloudProviders.info(provider)!!

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.M),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            ProviderStrip(connected, provider, onProvider, onAddProvider)
            when {
                data == null && cat.error != null -> ErrorCard(cat.error) { app.cloud.refresh(provider, force = true) }
                data == null -> Skeleton()
                region == null || priced == null -> ErrorCard("У ${info.name} сейчас нечего купить: нет тарифов ни в одном месте.") { app.cloud.refresh(provider, force = true) }
                else -> {
                    if (cat.error != null) Hint("Показаны сохранённые цены: ${cat.error}", Icons.Filled.Warning, MaterialTheme.colorScheme.error)
                    Hero(
                        region, priced, info,
                        recommended = rec?.region?.id == region.id && plan?.id == suggested?.id,
                        reason = rec?.takeIf { it.region.id == region.id }?.reason,
                        catalogPrice = plan!!.monthly.takeIf { exact != null && it != exact },
                        openPlaces = openPlaces, openPlans = openPlans,
                        onPlaces = { openPlaces = !openPlaces; if (openPlaces) openPlans = false },
                        onPlans = { openPlans = !openPlans; if (openPlans) openPlaces = false },
                    )
                    AnimatedVisibility(openPlaces) {
                        Places(data, region.id) { r -> regionId = r.id; planId = null; openPlaces = false }
                    }
                    AnimatedVisibility(openPlans) {
                        Plans(plans, plan.id, suggested?.id) { p -> planId = p.id; openPlans = false }
                    }
                    Hint(
                        "${info.name} спишет деньги со своего баланса. Сервер появится в вашем аккаунте у хостера; удалить его можно и там, и в приложении.",
                        Icons.Filled.Info,
                    )
                }
            }
            Spacer(Modifier.height(Space.S))
        }
        if (region != null && priced != null) BuyBar(region, priced, info) { app.cloud.buy(provider, region, priced, install) }
    }
}

@Composable
private fun ProviderStrip(connected: List<ProviderInfo>, selected: String, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.S), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(connected, key = { it.id }) { p ->
            val on = p.id == selected
            Surface(
                shape = CircleShape,
                color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                border = if (on) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.clip(CircleShape).tactile { onSelect(p.id) },
            ) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Monogram(p, 28)
                    Spacer(Modifier.width(8.dp))
                    Text(p.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            Surface(
                shape = CircleShape, color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clip(CircleShape).tactile(onClick = onAdd),
            ) {
                Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Хостер", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Monogram(p: ProviderInfo, size: Int) {
    val c = Color(p.color)
    Box(Modifier.size(size.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(p.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.48f).sp)
    }
}

/** The one-tap offer: where, what, how much, and the two doors to change it. */
@Composable
private fun Hero(
    region: CloudRegion,
    plan: CloudPlan,
    info: ProviderInfo,
    recommended: Boolean,
    reason: String?,
    catalogPrice: Double?,
    openPlaces: Boolean,
    openPlans: Boolean,
    onPlaces: () -> Unit,
    onPlans: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val glow = Brush.linearGradient(listOf(Brand.Violet.copy(alpha = 0.16f), Brand.Cyan.copy(alpha = 0.10f)))
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(glow).padding(Space.M)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(region.flag, fontSize = 44.sp)
                Spacer(Modifier.width(Space.M))
                Column(Modifier.weight(1f)) {
                    FitText(region.city, MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), min = 16.sp)
                    Text(
                        listOfNotNull(region.country, region.reach?.title).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (recommended) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyph.Bolt, null, Modifier.size(16.dp), tint = Brand.Mint)
                    Spacer(Modifier.width(4.dp))
                    Text("Рекомендуем" + (reason?.let { ": $it" } ?: ""), style = MaterialTheme.typography.labelLarge, color = Brand.Mint)
                }
            }
            Spacer(Modifier.height(Space.M))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Spec(Glyph.Cpu, "${plan.cpu} vCPU", Modifier.weight(1f))
                Spec(Glyph.Ram, plan.ramText, Modifier.weight(1f))
                Spec(Glyph.Disk, if (plan.diskGb > 0) "${plan.diskGb} ГБ" else "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.M))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(plan.priceShort(), style = MaterialTheme.typography.displaySmall.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
                Text(" /мес", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Text(info.name, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (catalogPrice != null) Text(
                "с IPv4-адресом; в каталоге ${CloudPlan.formatMoney(catalogPrice)} ${CloudPlan.symbol(plan.currency)}",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            if (plan.tooSmall) {
                Spacer(Modifier.height(Space.S))
                Hint("Меньше 1 ГБ памяти: установка AmneziaWG может не поместиться", Icons.Filled.Warning, scheme.error)
            }
            Spacer(Modifier.height(Space.M))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Door(Icons.Filled.LocationOn, "Место", openPlaces, Modifier.weight(1f), onPlaces)
                Door(Glyph.Tune, "Тариф", openPlans, Modifier.weight(1f), onPlans)
            }
        }
    }
}

@Composable
private fun Spec(icon: ImageVector, text: String, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        FitText(text, MaterialTheme.typography.labelLarge.merge(TabularNumbers), min = 10.sp)
    }
}

@Composable
private fun Door(icon: ImageVector, label: String, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (open) scheme.secondaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.6f),
        modifier = modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).tactile(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = scheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(if (open) "Скрыть" else "Другое: ${label.lowercase()}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Places grouped by part of the world, two per row: flag, city, price from, reach. */
@Composable
private fun Places(data: CloudCatalog, selected: String, onPick: (CloudRegion) -> Unit) {
    var showRussia by remember { mutableStateOf(false) }
    val sellable = data.sellable
    // Several data centres in one city (DigitalOcean nyc1/nyc3): tell them apart by code.
    val dupCities = sellable.groupBy { it.city }.filterValues { it.size > 1 }.keys
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        for ((area, list) in sellable.groupBy { it.area }.toSortedMap(compareBy { it.ordinal })) {
            if (area == Geo.Area.RUSSIA && !showRussia && list.none { it.id == selected }) {
                TextButton(onClick = { showRussia = true }) { Text("Показать места в России (не помогут обойти блокировки)") }
                continue
            }
            SectionTitle("${area.title} · ${list.size}")
            for (row in list.sortedWith(compareBy({ it.km ?: Int.MAX_VALUE }, { it.city })).chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    for (r in row) PlaceTile(r, data.plansIn(r.id), r.id == selected, r.city in dupCities, Modifier.weight(1f)) { onPick(r) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlaceTile(r: CloudRegion, plans: List<CloudPlan>, selected: Boolean, showCode: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val from = CloudProviders.suggest(plans)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        border = if (selected) BorderStroke(1.5.dp, scheme.primary) else null,
        modifier = modifier.heightIn(min = 76.dp).clip(RoundedCornerShape(20.dp)).tactile(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.flag, fontSize = 28.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FitText(r.city + if (showCode) " · ${r.id.uppercase()}" else "", MaterialTheme.typography.titleSmall, min = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReachDot(r.reach)
                    FitText(from?.let { "от ${it.priceText()}" } ?: r.country, MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, min = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun ReachDot(reach: Geo.Reach?) {
    val c = when (reach) { Geo.Reach.NEAR -> Brand.Mint; Geo.Reach.MID -> Brand.Amber; Geo.Reach.FAR -> Brand.Coral; null -> return }
    Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(c))
}

/** Plans as cards: the fitting ones first, too-small ones dimmed at the end, at most six at once. */
@Composable
private fun Plans(plans: List<CloudPlan>, selected: String, suggested: String?, onPick: (CloudPlan) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val ordered = plans.filter { !it.tooSmall } + plans.filter { it.tooSmall }
    val shown = if (all) ordered else ordered.take(6).let { s -> if (s.any { it.id == selected }) s else s + ordered.filter { it.id == selected } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        SectionTitle("Тарифы · ${plans.size}")
        for (p in shown) PlanCard(p, p.id == selected, p.id == suggested) { onPick(p) }
        if (!all && ordered.size > shown.size) TextButton(onClick = { all = true }) { Text("Ещё ${ordered.size - shown.size}") }
    }
}

@Composable
private fun PlanCard(p: CloudPlan, selected: Boolean, best: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        border = if (selected) BorderStroke(1.5.dp, scheme.primary) else null,
        modifier = Modifier.fillMaxWidth().alpha(if (p.tooSmall) 0.6f else 1f).clip(RoundedCornerShape(20.dp)).tactile(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = Space.M, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniSpec(Glyph.Cpu, "${p.cpu}")
                    MiniSpec(Glyph.Ram, p.ramText)
                    if (p.diskGb > 0) MiniSpec(Glyph.Disk, "${p.diskGb} ГБ")
                }
                if (best || p.tooSmall) {
                    Spacer(Modifier.height(6.dp))
                    if (best) Pill("Рекомендуем", Brand.Mint) else Pill("мало памяти", scheme.error)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(p.priceShort(), style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
                Text("в месяц", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (selected) {
                Spacer(Modifier.width(Space.S))
                Icon(Icons.Filled.Check, null, tint = scheme.primary)
            }
        }
    }
}

@Composable
private fun MiniSpec(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
    }
}

@Composable
private fun BuyBar(region: CloudRegion, plan: CloudPlan, info: ProviderInfo, onBuy: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.M, vertical = 12.dp)) {
            Text(
                "${region.flag} ${region.city} · ${info.name} · ${plan.cpu} vCPU · ${plan.ramText}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBuy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Glyph.Bolt, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Создать за ${plan.priceText()}", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "AmneziaWG установится сам · 3–8 минут",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
            )
        }
    }
}

/** Placeholder in the shape of the offer while the first catalog loads (later ones come from cache). */
@Composable
private fun Skeleton() {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val c = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(28.dp)).alpha(a).background(c))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Box(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(20.dp)).alpha(a).background(c))
            Box(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(20.dp)).alpha(a).background(c))
        }
        Text("Загружаю места и цены…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(text: String, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(Space.S))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            FilledTonalButton(onClick = onRetry) { Text("Повторить") }
        }
    }
}

// ---------------------------------------------------------------- hosters and tokens

/** All supported hosters: how to pay, which are connected; tap one to connect it with a token. */
@Composable
private fun ProvidersScreen(app: App, initial: String?, busy: Boolean, onBusy: (Boolean) -> Unit, onSaved: (String) -> Unit) {
    val state by app.repo.state.collectAsState()
    var open by remember { mutableStateOf(initial?.takeIf { it !in state.cloud.tokens } ?: CloudProviders.all.firstOrNull { it.id !in state.cloud.tokens }?.id) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Text(
            "Приложение само купит сервер через API хостера и установит на него AmneziaWG. Токен API вводится один раз и хранится зашифрованным только на этом телефоне.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        for (p in CloudProviders.all) {
            ProviderCard(app, p, p.id in state.cloud.tokens, expanded = open == p.id, busy = busy, onBusy = onBusy,
                onToggle = { open = if (open == p.id) null else p.id }, onSaved = onSaved)
        }
        Hint("Другой хостер с API? Его можно добавить в приложение: список и как добавить в providers/README.md репозитория.", Icons.Filled.Info)
        Spacer(Modifier.height(Space.M))
    }
}

@Composable
private fun ProviderCard(
    app: App, p: ProviderInfo, connected: Boolean, expanded: Boolean, busy: Boolean,
    onBusy: (Boolean) -> Unit, onToggle: () -> Unit, onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(
        shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainer,
        border = if (expanded) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.4f)) else null,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).tactile(onClick = onToggle).padding(Space.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Monogram(p, 40)
                Spacer(Modifier.width(Space.M))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        if (connected) { Spacer(Modifier.width(Space.S)); Pill("подключён", Brand.Mint) }
                    }
                    Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (connected && !expanded) Icon(Icons.Filled.Check, null, tint = Brand.Mint)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = Space.M, end = Space.M, bottom = Space.M), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (connected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                            Button(onClick = { onSaved(p.id) }) { Text("Выбрать") }
                            OutlinedButton(enabled = !busy, onClick = { app.cloud.forgetToken(p.id) }) { Text("Отключить") }
                        }
                    } else {
                        Hint(p.tokenHint, Glyph.Key)
                        OutlinedButton(onClick = { runCatching { uri.openUri(p.tokenUrl) } }) {
                            Text("Открыть панель ${p.name}")
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                        PasteField(token, { token = it.trim(); error = null }, "Токен API ${p.name}", secret = true, enabled = !busy,
                            onEmptyClipboard = { error = "Буфер обмена пуст: скопируйте токен в панели ${p.name}" })
                        error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(
                            enabled = token.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                            onClick = {
                                onBusy(true); error = null
                                scope.launch {
                                    runCatching { app.cloud.saveToken(p.id, token) }.onSuccess { onSaved(p.id) }.onFailure { error = Cloud.describe(it) }
                                    onBusy(false)
                                }
                            },
                        ) { Text("Проверить и подключить") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- progress

private val STAGES = listOf("Заказ у хостера", "Запуск сервера", "Загрузка системы", "Установка AmneziaWG", "Готово")

@Composable
private fun JobScreen(app: App, j: Cloud.Job, toast: (String) -> Unit, onClose: () -> Unit, onHide: () -> Unit) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val running = !j.done && j.error == null
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.M),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            if (j.what.isNotEmpty()) Text(j.what, style = MaterialTheme.typography.titleMedium)
            Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    STAGES.forEachIndexed { i, name ->
                        val done = i < j.stage || j.done
                        val now = i == j.stage && running
                        val failed = i == j.stage && j.error != null
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                when {
                                    done -> Box(Modifier.size(24.dp).clip(CircleShape).background(Brand.Mint), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White)
                                    }
                                    now -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                    failed -> Icon(Icons.Filled.Warning, null, tint = scheme.error)
                                    else -> Box(Modifier.size(12.dp).clip(CircleShape).border(1.5.dp, scheme.outline, CircleShape))
                                }
                            }
                            Spacer(Modifier.width(Space.M))
                            Column {
                                Text(name, style = MaterialTheme.typography.titleSmall, color = if (done || now) scheme.onSurface else scheme.onSurfaceVariant)
                                if (now || failed) Text(j.label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            j.server?.host?.let { Text("IP: $it", style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), color = scheme.onSurfaceVariant) }
            if (running) Hint("Обычно 3–8 минут. Окно можно свернуть: работа продолжится, пока приложение открыто.", Icons.Filled.Info)
            val err = j.error
            if (err != null) {
                Hint(err, Icons.Filled.Warning, scheme.error)
                val s = j.server
                if (s != null) {
                    Text("Сервер уже создан у хостера и оплачивается. Удалите его, если не будете пробовать снова.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !deleting, onClick = {
                        deleting = true
                        scope.launch {
                            runCatching { app.cloud.delete(s) }.onSuccess { toast("Сервер удалён у хостера") }.onFailure { toast(Cloud.describe(it)) }
                            deleting = false
                        }
                    }) { Text("Удалить сервер у хостера") }
                }
            }
        }
        Surface(color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(Space.M)) {
                if (running) FilledTonalButton(onClick = onHide, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Свернуть") }
                else Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (j.done) "Готово" else "Закрыть") }
            }
        }
    }
}
