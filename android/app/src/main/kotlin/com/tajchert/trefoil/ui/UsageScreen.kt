package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*

/**
 * Full-pane Usage page: Limits | Cost | Tokens for the connected environment.
 * Limits come from [ClientState.providers]; cost/tokens from [usage].
 */
@Composable
internal fun UsageScreen(
    state: ClientState,
    usage: UsageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectRange: (UsageRange) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
) {
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val c = Tf.colors
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(TfIcons.Back, "Back", Modifier.size(22.dp), tint = c.text) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Usage", style = TrefoilType.rowTitle, color = c.text)
                state.environment?.name?.let { Text(it, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = onRefresh) { Icon(TfIcons.Refresh, "Refresh usage", Modifier.size(22.dp), tint = c.text) }
        }
        if (usage.limitsRefreshing || usage.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        HorizontalDivider(color = c.line)
        TabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.text) {
            listOf("Limits", "Cost", "Tokens").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        if (tab == 0) UsageLimits(state.providers, usage.limitsError)
        else UsageHistory(usage, tab == 1, onSelectRange, onRetry)
    }
}

@Composable
private fun UsageLimits(providers: List<ProviderInstance>, error: String?) {
    val visible = providers.filter { it.enabled && it.usageLimits != null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { item { UsageNote(it) } }
        if (visible.isEmpty()) item { UsageNote("No provider limits reported.") }
        items(visible, key = { it.instanceId }) { provider ->
            val limits = requireNotNull(provider.usageLimits)
            Surface(shape = RoundedCornerShape(16.dp), color = Tf.colors.s1, border = BorderStroke(1.dp, Tf.colors.line)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProviderMark(provider.driver, null, Modifier.size(22.dp))
                        Text(provider.displayName ?: provider.driver, style = TrefoilType.rowTitle, color = Tf.colors.text)
                    }
                    limits.windows.forEach { UsageWindow(it, now) }
                    if (limits.windows.isEmpty()) limits.notice?.let { UsageNote(it) }
                    usageCheckedAgo(limits.checkedAt, now)?.let { UsageNote(it) }
                }
            }
        }
    }
}

@Composable
private fun UsageWindow(window: UsageLimitWindow, now: Long) {
    val c = Tf.colors
    val remaining = window.remainingPercent
    val color = when { remaining <= 10 -> c.red; remaining <= 30 -> c.amber; else -> c.green }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(window.label, Modifier.weight(1f), style = TrefoilType.meta, color = c.text)
            Text("$remaining% left", style = TrefoilType.meta, color = color)
        }
        UsageBar(remaining / 100f, color, Modifier.semantics { stateDescription = "$remaining% left" }, usageElapsedShare(window, now)?.let { (1 - it).toFloat() })
        listOfNotNull(usagePace(window, now), formatUsageReset(window, now)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { UsageNote(it) }
    }
}

@Composable
private fun UsageBar(share: Float, color: Color, modifier: Modifier = Modifier, tick: Float? = null) {
    val track = Tf.colors.s3
    val tickColor = Tf.colors.text
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(color, size = Size(size.width * share.coerceIn(0f, 1f), size.height), cornerRadius = radius)
        tick?.let {
            val x = (size.width * it.coerceIn(0f, 1f)).coerceIn(1.dp.toPx(), (size.width - 1.dp.toPx()).coerceAtLeast(1.dp.toPx()))
            drawLine(tickColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun UsageHistory(usage: UsageUiState, cost: Boolean, onSelectRange: (UsageRange) -> Unit, onRetry: () -> Unit) {
    val report = usage.report
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageRange.entries.forEach { range -> FilterChip(selected = usage.range == range, onClick = { onSelectRange(range) }, label = { Text(range.label) }) }
            }
        }
        if (report == null) {
            when {
                usage.loading -> item { CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "Loading usage" }) }
                usage.failure != null -> item { UsageFailureNote(usage, onRetry) }
                else -> item { UsageNote("No usage reported yet.") }
            }
        } else {
            if (usage.failure != null) item { UsageFailureNote(usage, onRetry) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (cost) formatUsageUsd(report.totalCostUsd) else formatUsageTokens(report.tokens.total), style = MaterialTheme.typography.headlineLarge, color = Tf.colors.text)
                    if (cost) {
                        UsageNote("Estimated from model pricing")
                        if (report.cacheSavingsUsd > 0) UsageNote("Cache saved ${formatUsageUsd(report.cacheSavingsUsd)}")
                    } else UsageNote("Input ${formatUsageTokens(report.tokens.input)} · Output ${formatUsageTokens(report.tokens.output)} · Cached ${formatUsageTokens(report.tokens.cachedInput)}")
                }
            }
            if (report.refreshing || usage.refreshing) item { UsageNote("Updating…") }
            if (usage.range.days > 1) item { UsageDailyChart(report.days, cost) }
            if (report.providers.isNotEmpty()) item { SectionLabel("Providers") }
            items(report.providers) { UsageBreakdownRow(it, report, cost) }
            if (report.models.isNotEmpty()) item { SectionLabel("Models") }
            items(report.models) { UsageBreakdownRow(it, report, cost) }
            if (report.unpricedRecords > 0) item { UsageNote("${report.unpricedRecords} unpriced records; estimated cost may be incomplete.") }
            if (report.pricingStatus == "unavailable") item { UsageNote("Model pricing is unavailable.") }
            items(report.sources.filter { it.status in setOf("missing", "partial", "failed") }) {
                UsageNote("${usageProviderLabel(it.provider)} usage: ${it.status}")
            }
        }
    }
}

@Composable
private fun UsageBreakdownRow(row: UsageBreakdown, report: UsageReport, cost: Boolean) {
    val value = if (cost) row.costUsd else row.tokens.total.toDouble()
    val total = if (cost) report.totalCostUsd else report.tokens.total.toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.model ?: usageProviderLabel(row.provider), Modifier.weight(1f), style = TrefoilType.meta, color = Tf.colors.text)
            Text(if (cost) formatUsageUsd(row.costUsd) else formatUsageTokens(row.tokens.total), style = TrefoilType.mono, color = Tf.colors.text)
        }
        UsageBar(usageShare(value, total), Tf.colors.green)
    }
}

@Composable
private fun UsageDailyChart(days: List<UsageDayTotal>, cost: Boolean) {
    fun value(day: UsageDayTotal) = if (cost) day.costUsd else day.tokens.toDouble()
    val peak = days.maxByOrNull { value(it) }
    val max = peak?.let { value(it) } ?: 0.0
    val description = peak?.let { "Daily ${if (cost) "cost" else "tokens"}. Peak ${it.day}: ${if (cost) formatUsageUsd(it.costUsd) else formatUsageTokens(it.tokens)}" } ?: "No daily usage"
    val color = Tf.colors.green
    val baseline = Tf.colors.line
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = description }) {
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height))
            if (days.isNotEmpty()) {
                val slot = size.width / days.size
                days.forEachIndexed { index, day ->
                    val height = size.height * usageShare(value(day), max)
                    drawRect(color, Offset(index * slot + slot * .15f, size.height - height), Size(slot * .7f, height))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.firstOrNull()?.day.orEmpty(), style = TrefoilType.meta, color = Tf.colors.muted)
            Text(days.lastOrNull()?.day.orEmpty(), style = TrefoilType.meta, color = Tf.colors.muted)
        }
    }
}

@Composable
private fun UsageFailureNote(usage: UsageUiState, onRetry: () -> Unit) {
    Column {
        UsageNote(when (usage.failure) {
            UsageFailure.NoPermission -> "This connection can't read usage. Pair again with a token that has diagnostics access."
            UsageFailure.NotConnected -> "Connect to this machine to view usage."
            UsageFailure.Unsupported -> "This T3 server doesn't report usage."
            UsageFailure.TimedOut -> usage.failureMessage ?: "Reading usage timed out. Try again."
            else -> usage.failureMessage ?: "This T3 server could not report usage."
        })
        if (usage.failure == UsageFailure.Failed || usage.failure == UsageFailure.TimedOut) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun UsageNote(text: String) { Text(text, style = TrefoilType.meta, color = Tf.colors.muted) }
