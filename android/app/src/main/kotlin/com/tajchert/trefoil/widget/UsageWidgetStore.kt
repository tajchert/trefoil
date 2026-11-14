package com.tajchert.trefoil.widget

import android.content.Context
import android.util.AtomicFile
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import com.tajchert.trefoil.core.model.UsageRange
import com.tajchert.trefoil.core.model.UsageReport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One enabled provider instance as shown on widgets. [limits] is null when the provider reports none. */
@Serializable
data class UsageWidgetProvider(
    val instanceId: String,
    /** T3 driver literal ("codex", "claudeAgent", ...); see `ui/ProviderIconUi.kt` for icons. */
    val driver: String,
    val displayName: String,
    val limits: ProviderUsageLimits? = null,
)

/**
 * Last known usage of the most recently connected environment, persisted for home screen widgets.
 * Contains numbers, provider and model names and the machine name only: never tokens, URLs, paths or conversation content.
 */
@Serializable
data class UsageWidgetSnapshot(
    val environmentId: String,
    val machineName: String,
    /** Wall clock of the last successful write of any section. */
    val updatedAtMillis: Long,
    /** Wall clock when [providers] limits were last replaced from the server; null if never. */
    val limitsUpdatedAtMillis: Long? = null,
    val providers: List<UsageWidgetProvider> = emptyList(),
    /** Latest report per range; a range is missing until it has been read once. */
    val reports: Map<UsageRange, UsageReport> = emptyMap(),
    /** True when a background refresh can reach this environment without user input. */
    val backgroundRefresh: Boolean = false,
    /** Short user-facing reason of the last failed refresh, cleared on success. Never contains credentials. */
    val lastError: String? = null,
    val lastErrorAtMillis: Long? = null,
)

/**
 * Persists the widget snapshot in `noBackupFilesDir` and notifies widgets after every change.
 * [snapshot] is process-wide; widgets collect it inside `provideContent` so updates reach running sessions.
 */
class UsageWidgetStore internal constructor(
    private val file: AtomicFile,
    private val onChanged: suspend () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<UsageWidgetSnapshot?>(null)
    @Volatile private var loaded = false

    /** Current snapshot; call [read] once first if the process may have just started. */
    val snapshot: StateFlow<UsageWidgetSnapshot?> = mutable.asStateFlow()

    suspend fun read(): UsageWidgetSnapshot? = mutex.withLock { ensureLoaded() }

    /** Replaces the snapshot with [transform] of the current one; null deletes it. Widgets refresh when the value changed. */
    suspend fun update(transform: (UsageWidgetSnapshot?) -> UsageWidgetSnapshot?): UsageWidgetSnapshot? {
        val (changed, next) = mutex.withLock {
            val current = ensureLoaded()
            val next = transform(current)
            if (next != current) {
                withContext(Dispatchers.IO) { write(next) }
                mutable.value = next
            }
            (next != current) to next
        }
        if (changed) onChanged()
        return next
    }

    private suspend fun ensureLoaded(): UsageWidgetSnapshot? {
        if (!loaded) {
            mutable.value = withContext(Dispatchers.IO) {
                try {
                    if (file.baseFile.exists()) json.decodeFromString(UsageWidgetSnapshot.serializer(), file.readFully().decodeToString()) else null
                } catch (_: Exception) { file.delete(); null }
            }
            loaded = true
        }
        return mutable.value
    }

    private fun write(value: UsageWidgetSnapshot?) {
        if (value == null) { file.delete(); return }
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(UsageWidgetSnapshot.serializer(), value).encodeToByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }

    companion object {
        @Volatile private var instance: UsageWidgetStore? = null
        fun get(context: Context): UsageWidgetStore = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                UsageWidgetStore(AtomicFile(File(app.noBackupFilesDir, "usage-widget.v1.json"))) { UsageWidgets.updateAll(app) }
            }.also { instance = it }
        }
    }
}
