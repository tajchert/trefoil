package com.tajchert.trefoil.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerConfigStreamTest {
    private fun objects(json: String): List<JsonObject> = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
    private fun event(json: String) = Json.parseToJsonElement(json).jsonObject

    private val held = objects("""[
        {"instanceId":"codex","driver":"codex","models":[{"slug":"sol"}],"slashCommands":[],"skills":[],"catalogDigest":"aaaa"},
        {"instanceId":"claude","driver":"claudeAgent","models":[{"slug":"opus"}]}
    ]""")

    @Test fun deltasKeepHeldListsTakeNewOnesAndFollowTheDeltaOrder() {
        val merged = applyProviderDeltas(held, objects("""[
            {"instanceId":"claude","driver":"claudeAgent","displayName":"Claude","models":[{"slug":"fable"}]},
            {"instanceId":"codex","driver":"codex","enabled":false,"catalogDigest":"bbbb"}
        ]"""))
        assertEquals(listOf("claude", "codex"), merged.map { it["instanceId"].toString().trim('"') })
        assertEquals(objects("""[{"slug":"fable"}]"""), merged[0]["models"]!!.jsonArray.map { it.jsonObject })
        assertEquals(held[0]["models"], merged[1]["models"])
        assertEquals(held[0]["slashCommands"], merged[1]["slashCommands"])
        assertEquals("\"bbbb\"", merged[1]["catalogDigest"].toString())
        assertEquals("false", merged[1]["enabled"].toString())
    }

    @Test fun aProviderMissingFromTheDeltaIsGoneAndOneNeverHeldWithoutModelsIsDropped() {
        val merged = applyProviderDeltas(held, objects("""[{"instanceId":"new","driver":"x"},{"instanceId":"fresh","driver":"y","models":[]}]"""))
        assertEquals(listOf("\"fresh\""), merged.map { it["instanceId"].toString() })
    }

    @Test fun streamEventsYieldTheirProviders() {
        assertEquals(held, streamProviders(event("""{"type":"snapshot","config":{"providers":${held}}}"""), emptyList()))
        assertEquals(held.take(1), streamProviders(event("""{"type":"providerStatuses","payload":{"providers":[${held[0]}]}}"""), held))
        assertEquals(held.take(1), streamProviders(event("""{"type":"providerStatusDeltas","payload":{"providers":[{"instanceId":"codex","driver":"codex","slashCommands":[],"skills":[],"catalogDigest":"aaaa"}]}}"""), held))
        assertNull("A snapshot without providers says nothing", streamProviders(event("""{"type":"snapshot","config":{}}"""), held))
        assertNull(streamProviders(event("""{"type":"settingsUpdated","payload":{"settings":{}}}"""), held))
        assertNull(streamProviders(event("""{"type":"providerStatusDeltas","payload":{"providers":"broken"}}"""), held))
    }

    @Test fun theSubscriptionAsksForTheSlimShapeAndNeverOmitsProviders() {
        assertEquals(setOf("workspaceSnapshotsOnDemand", "providerCatalogsOnDemand", "providerCatalogDigests", "providerStatusDeltas"), SERVER_CONFIG_SUBSCRIPTION_INPUT.keys)
        assertNull(SERVER_CONFIG_SUBSCRIPTION_INPUT["omitProviders"])
    }
}
