package com.tajchert.trefoil.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZoneId

internal const val WIDGET_FETCH_INTERVAL_MS = 10 * 60_000L

internal fun widgetProviders(providers: List<ProviderInstance>) = providers.filter { it.enabled }.map {
    UsageWidgetProvider(it.instanceId, it.driver, it.displayName ?: it.driver,
        it.usageLimits?.copy(unavailableMessage = null))
}

internal fun mergeWidgetSnapshot(old: UsageWidgetSnapshot?, environment: SavedEnvironment,
    providers: List<UsageWidgetProvider>, now: Long, background: Boolean): UsageWidgetSnapshot {
    val base = old?.takeIf { it.environmentId == environment.id }
        ?: UsageWidgetSnapshot(environment.id, environment.name, now)
    val changed = base.providers != providers
    val next = base.copy(machineName = environment.name, providers = providers, backgroundRefresh = background,
        limitsUpdatedAtMillis = if (changed) now else base.limitsUpdatedAtMillis)
    return if (next != base) next.copy(updatedAtMillis = now) else next
}

internal fun mergeWidgetReport(old: UsageWidgetSnapshot?, report: UsageReport, now: Long): UsageWidgetSnapshot? {
    if (old == null || old.environmentId != report.environmentId) return old
    val safe = report.copy(sources = report.sources.map { it.copy(message = null) })
    if (old.reports[report.range] == safe) return old
    return old.copy(reports = old.reports + (report.range to safe), updatedAtMillis = now,
        lastError = null, lastErrorAtMillis = null)
}

internal class WidgetFetchThrottle {
    private val attempts = mutableMapOf<String, Long>()
    fun acquire(id: String, now: Long): Boolean {
        val previous = attempts[id]
        if (previous != null && now - previous in 0 until WIDGET_FETCH_INTERVAL_MS) return false
        attempts[id] = now
        return true
    }
}

/** Queries the socket directly so worker skip decisions never depend on a delayed flow collector. */
internal object UsageWidgetLiveConnection {
    @Volatile var state: (() -> ClientState)? = null
    fun environmentId(): String? = state?.invoke()?.let {
        it.environment?.id?.takeIf { _ -> it.connection == ConnectionStatus.Connected }
    }
}

internal suspend fun usageWidgetsPlaced(context: Context): Boolean {
    val manager = GlanceAppWidgetManager(context)
    return UsageWidgets.all.any { manager.getGlanceIds(it.javaClass).isNotEmpty() }
}

/** Owns only widget reads; errors never enter the app's error state or disconnect its client. */
class UsageWidgetSync(private val context: Context, private val scope: CoroutineScope,
    private val client: TrefoilClient, environments: StateFlow<List<SavedEnvironment>>) {
    private val store = UsageWidgetStore.get(context)
    private val throttle = WidgetFetchThrottle()
    private val liveState = { client.state.value }
    init {
        UsageWidgetLiveConnection.state = liveState
        scope.launch {
            combine(client.state.map { Triple(it.environment?.takeIf { _ -> it.connection == ConnectionStatus.Connected },
                widgetProviders(it.providers), it.canReadUsage) }.distinctUntilChanged(), environments) { projection, saved ->
                projection.first?.let { environment -> Triple(saved.firstOrNull { it.id == environment.id } ?: environment.forStorage(),
                    projection.second, projection.third) }
            }.distinctUntilChanged().collect { projection ->
                if (projection != null) {
                    try {
                        store.update { mergeWidgetSnapshot(it, projection.first, projection.second,
                            System.currentTimeMillis(), widgetRefreshEligible(projection.first, emptyList())) }
                        if (projection.third) scope.launch {
                            try { if (usageWidgetsPlaced(context)) fetch() }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Widget failures are isolated from the app connection. */ }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* A later projection can retry storage. */ }
                }
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    if (usageWidgetsPlaced(context)) {
                        UsageWidgetRefresh.schedulePeriodic(context)
                        fetch()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Launcher/work scheduling failures must not affect the app. */ }
                delay(60_000)
            }
        }
    }
    suspend fun report(report: UsageReport) { store.update { mergeWidgetReport(it, report, System.currentTimeMillis()) } }
    suspend fun forget(id: String) { store.update { it?.takeUnless { snapshot -> snapshot.environmentId == id } } }
    fun close() { if (UsageWidgetLiveConnection.state === liveState) UsageWidgetLiveConnection.state = null }

    private suspend fun fetch() {
        val state = client.state.value
        val environment = state.environment ?: return
        if (state.connection != ConnectionStatus.Connected || !state.canReadUsage ||
            !throttle.acquire(environment.id, System.currentTimeMillis())) return
        // Ensure the first reports have a snapshot even if the mapping collector is still starting.
        store.update { mergeWidgetSnapshot(it, environment.forStorage(), widgetProviders(state.providers),
            System.currentTimeMillis(), widgetRefreshEligible(environment, emptyList())) }
        var failed = false
        suspend fun read(block: suspend () -> Unit) {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        read { client.refreshUsageLimits(automatic = true) }
        for (range in UsageRange.entries) read {
            val provisional = client.usageReport(range, ZoneId.systemDefault().id, false)
            report(provisional)
            if (provisional.refreshing) report(client.usageReport(range, ZoneId.systemDefault().id, true))
        }
        store.update { old -> if (old?.environmentId != environment.id) old else old.copy(
            lastError = if (failed) "Could not refresh usage." else null,
            lastErrorAtMillis = if (failed) System.currentTimeMillis() else null) }
    }
}
