package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.UsageUiState
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class UsageController(private val scope: CoroutineScope, private val client: () -> TrefoilClient, private val zone: () -> String = { ZoneId.systemDefault().id }) {
    private val mutable = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var limitsJob: Job? = null

    fun open() { limits(true); load(state.value.range) }
    fun selectRange(range: UsageRange) {
        loadJob?.cancel()
        mutable.update { it.copy(range = range, loading = false, refreshing = false, failure = null, failureMessage = null) }
        load(range)
    }
    fun refresh() { limits(false); load(state.value.range, true) }
    fun retry() = load(state.value.range)
    fun reset() { loadJob?.cancel(); limitsJob?.cancel(); mutable.value = UsageUiState() }

    private fun limits(automatic: Boolean) {
        limitsJob?.cancel()
        limitsJob = scope.launch {
            mutable.update { it.copy(limitsRefreshing = true, limitsError = null) }
            try { client().refreshUsageLimits(automatic); ensureActive(); mutable.update { it.copy(limitsRefreshing = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: UsageException) { mutable.update { it.copy(limitsRefreshing = false, limitsError = e.message) } }
            catch (e: Exception) { mutable.update { it.copy(limitsRefreshing = false, limitsError = e.message ?: "Could not refresh limits.") } }
        }
    }
    private fun load(range: UsageRange, awaitRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = scope.launch {
            mutable.update { it.copy(loading = it.reports[range] == null, refreshing = it.reports[range] != null, failure = null, failureMessage = null) }
            try {
                var report = client().usageReport(range, zone(), awaitRefresh)
                ensureActive()
                mutable.update { it.copy(reports = it.reports + (range to report), loading = false, refreshing = report.refreshing && !awaitRefresh) }
                if (report.refreshing && !awaitRefresh) {
                    report = client().usageReport(range, zone(), true)
                    ensureActive()
                    mutable.update { it.copy(reports = it.reports + (range to report), refreshing = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loading = false, refreshing = false, failure = (e as? UsageException)?.reason ?: UsageFailure.Failed, failureMessage = e.message) } }
        }
    }
}
