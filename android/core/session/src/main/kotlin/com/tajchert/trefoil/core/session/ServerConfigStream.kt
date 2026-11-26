/* Provider delta merge adapted from T3 Code's `applyServerProviderDeltas` (packages/contracts/src/server.ts, MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.protocol.objects
import com.tajchert.trefoil.core.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `subscribeServerConfig` opt-ins of T3 mobile (#35072): providers without per-cwd `workspaceSnapshots`, a
 * `catalogDigest` in place of machine slash commands and skills (Trefoil reads neither), and provider changes as
 * `providerStatusDeltas`. All are `Schema.optional`; older servers drop the keys and send the full shape.
 */
internal val SERVER_CONFIG_SUBSCRIPTION_INPUT: JsonObject = buildJsonObject {
    put("workspaceSnapshotsOnDemand", true)
    put("providerCatalogsOnDemand", true)
    put("providerCatalogDigests", true)
    put("providerStatusDeltas", true)
}

/** The provider lists a `providerStatusDeltas` entry may leave out when they equal the last list sent. */
private val PROVIDER_DELTA_LISTS = listOf("models", "slashCommands", "skills")

/**
 * T3 `applyServerProviderDeltas`: each delta (every provider, in order) takes the lists it leaves out from the provider
 * with its `instanceId` in [previous]. A provider whose `models` neither the delta nor [previous] holds cannot be
 * rebuilt and is dropped, like an unreadable one; Trefoil reads no slash commands or skills, so their absence is kept.
 */
internal fun applyProviderDeltas(previous: List<JsonObject>, deltas: List<JsonObject>): List<JsonObject> {
    val held = previous.associateBy { it.string("instanceId") }
    return deltas.mapNotNull { delta ->
        val base = held[delta.string("instanceId")]
        val restored = PROVIDER_DELTA_LISTS.mapNotNull { key -> if (key in delta) null else base?.get(key)?.let { key to it } }
        val provider = if (restored.isEmpty()) delta else JsonObject(delta + restored)
        provider.takeIf { it["models"] is JsonArray }
    }
}

/** The providers of a config stream event, or null when it carries none: a snapshot's, or a provider status event's. */
internal fun streamProviders(event: JsonObject, previous: List<JsonObject>): List<JsonObject>? {
    fun list(value: JsonElement?) = (value as? JsonArray)?.mapNotNull { it as? JsonObject }
    return when (event.string("type")) {
        "snapshot" -> list((event["config"] as? JsonObject)?.get("providers"))
        "providerStatuses" -> list((event["payload"] as? JsonObject)?.get("providers"))
        "providerStatusDeltas" -> list((event["payload"] as? JsonObject)?.get("providers"))?.let { applyProviderDeltas(previous, it) }
        else -> null
    }
}

/** The model catalog of [providers]: models of each provider not explicitly disabled, with its runtime modes. */
internal fun catalogModels(providers: List<JsonObject>): List<ModelOption> =
    providers.filter { (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false }.flatMap { provider ->
        val modes = supportedRuntimeModes(provider)
        provider.objects("models").map { catalogModelOption(provider.string("instanceId"), modes, it) }
    }
