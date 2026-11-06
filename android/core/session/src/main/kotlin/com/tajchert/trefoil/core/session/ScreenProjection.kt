package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.serialization.json.*

// Adapted from T3 Code threadDevicePreviews / mobile preview state (MIT).
internal fun browserScreens(value: JsonObject, threadId: String, interactive: Boolean): List<ScreenTarget.Browser> =
    value.objects("sessions").filter { it.string("threadId") == threadId && it.string("runtime") == "server" && it.string("tabId").isNotBlank() }.map {
        val nav = it.obj("navStatus")
        ScreenTarget.Browser(threadId, it.string("tabId"), nav.string("title").ifBlank { "Browser" }, nav.string("url"), interactive)
    }

internal fun deviceScreens(value: JsonObject, threadId: String): List<ScreenTarget.Device> {
    val hosts = value.objects("hosts") + value.objects("easHosts") + value.objects("simlockHosts")
    return value.objects("sessions").filter { it.string("threadId") == threadId && it.string("hostId").isNotBlank() && it.string("deviceId").isNotBlank() && it.string("platform") in setOf("android", "ios") }.map { session ->
        val device = value.objects("devices").firstOrNull { it.string("hostId") == session.string("hostId") && it.string("id") == session.string("deviceId") }
        ScreenTarget.Device(threadId, session.string("hostId"), session.string("deviceId"), session.string("platform"),
            device?.string("name")?.ifBlank { null } ?: if (session.string("platform") == "ios") "iOS Simulator" else "Android Emulator",
            hosts.firstOrNull { it.string("id") == session.string("hostId") }?.string("kind") == "simlock")
    }
}

internal fun screenBasePath(target: ScreenTarget): String = when (target) {
    is ScreenTarget.Browser -> "/api/preview-stream"
    is ScreenTarget.Device -> "/api/device-hub"
}

/** Rechecks the saved URL policy and exact media roots before a WebView receives credentials. */
fun validateScreenAccess(access: ScreenAccess, environment: SavedEnvironment): Boolean = runCatching {
    val target = access.target
    val base = validatedEnvironmentUrl(environment.baseUrl, environment.allowInsecureHttp)
    val expected = base.resolve(screenBasePath(access.target))!!.toString().removeSuffix("/")
    access.environmentId == environment.id && !access.credentials && access.httpBase == expected &&
        access.wsBase == expected.replaceFirst("http", "ws") &&
        !access.query["wsTicket"].isNullOrBlank() &&
        access.query.keys == (if (target is ScreenTarget.Device) setOf("wsTicket", "hostId") else setOf("wsTicket")) &&
        (target !is ScreenTarget.Device || access.query["hostId"] == target.hostId)
}.getOrDefault(false)

internal fun sameScreenTarget(first: ScreenTarget, second: ScreenTarget): Boolean = when {
    first is ScreenTarget.Browser && second is ScreenTarget.Browser -> first.threadId == second.threadId && first.tabId == second.tabId
    first is ScreenTarget.Device && second is ScreenTarget.Device -> first.threadId == second.threadId && first.hostId == second.hostId && first.deviceId == second.deviceId && first.platform == second.platform
    else -> false
}

/** Null means a process epoch changed: only a fresh list can establish the new baseline. */
internal fun applyPreviewScreenEvent(value: JsonObject, event: JsonObject): JsonObject? {
    val epoch = value.string("serverEpoch")
    if (epoch.isBlank()) return value
    if (epoch != event.string("serverEpoch")) return null
    val revision = (value["revision"] as? JsonPrimitive)?.longOrNull ?: -1
    val incoming = (event["revision"] as? JsonPrimitive)?.longOrNull ?: return value
    if (incoming <= revision) return value
    val sessions = value.objects("sessions").toMutableList()
    val index = sessions.indexOfFirst { it.string("tabId") == event.string("tabId") }
    when (event.string("type")) {
        "closed" -> if (index >= 0) sessions.removeAt(index)
        "opened", "navigated", "resized" -> {
            val snapshot = event["snapshot"] as? JsonObject ?: return value
            if (index < 0) sessions.add(snapshot) else sessions[index] = snapshot
        }
        "failed" -> if (index >= 0) sessions[index] = JsonObject(sessions[index] + ("navStatus" to buildJsonObject {
            put("_tag", "LoadFailed"); listOf("url", "title", "code", "description").forEach { key -> event[key]?.let { put(key, it) } }
        }))
        else -> return value
    }
    return JsonObject(value + mapOf("revision" to JsonPrimitive(incoming), "sessions" to JsonArray(sessions)))
}
