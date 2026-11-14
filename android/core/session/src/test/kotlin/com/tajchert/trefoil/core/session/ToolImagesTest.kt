package com.tajchert.trefoil.core.session

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolImagesTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun projection(item: String) = json("""{"thread":{"id":"child"},"turnItems":[$item]}""")

    @Test fun structuredImageBytesNeverEnterDisplayText() {
        val activity = toolActivities(projection("""{"id":"i","type":"dynamic_tool","output":{"content":[{"type":"text","text":"caption"},{"type":"image","mimeType":"image/png","data":"SECRET_BASE64"},{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"OTHER_BASE64"}}]}}""")).single()
        assertFalse(activity.detail.contains("BASE64"))
        assertFalse(activity.output.orEmpty().contains("BASE64"))
        assertTrue(activity.output.orEmpty().contains("caption"))
    }

    @Test fun changedViewedPathInvalidatesRetainedDetails() {
        val initial = projection("""{"id":"i","type":"dynamic_tool","viewedImagePath":"/tmp/one.png","outputOmitted":true}""")
        val loaded = hydrateActivityProjection(initial, "i", json("""{"id":"i","type":"dynamic_tool","output":[{"type":"image","mimeType":"image/png"}]}"""))
        val changed = projection("""{"id":"i","type":"dynamic_tool","viewedImagePath":"/tmp/two.png","outputOmitted":true}""")
        assertTrue(toolActivities(retainHydratedActivities(changed, loaded)).single().outputOmitted)
    }

    @Test fun markerShapesKeepAcceptedOrderAndIgnoreMalformedOrUnsafeMarkers() {
        val output = """{"content":[{"type":"image","mimeType":{}},{"type":"image","mimeType":"image/svg+xml","data":"SECRET_BASE64"},{"type":"image","source":{"type":"url","media_type":"image/png","data":"SECRET_BASE64"}},{"type":"image","source":{"type":"base64"},"mimeType":"image/png"},{"type":"image","mimeType":"IMAGE/GIF"},{"type":"text","text":"caption"},{"type":"image","source":{"type":"base64","media_type":"image/webp"}}]}"""
        val activity = toolActivities(projection("""{"id":"i","type":"dynamic_tool","output":$output}""")).single()
        assertEquals(listOf("image/gif", "image/webp"), activity.images.map { it.mimeType })
        assertEquals(listOf(0, 1), activity.images.map { it.toolOutputIndex })
        assertFalse(activity.detail.contains("SECRET_BASE64"))
        assertEquals(1, toolActivities(projection("""{"id":"i","type":"dynamic_tool","output":{"type":"image","mimeType":"image/png"}}""")).single().images.size)
        // Stringified JSON and nested unrelated objects are not indexed by the server.
        assertTrue(toolActivities(projection("""{"id":"i","type":"dynamic_tool","output":"[{\"type\":\"image\",\"mimeType\":\"image/png\"}]"}""")).single().images.isEmpty())
    }

    @Test fun fileUrlNormalizationRejectsRemoteAuthoritiesQueriesTraversalAndNonImages() {
        val rejected = listOf("file://remote/tmp/a.png", "file:///tmp/a.png?x", "file:///tmp/a.png#x", "file:///tmp/%2e%2e/a.png", "file:///tmp/a%00.png", "file:relative.png", "https://example.test/a.png", "data:image/png;base64,bytes", "/tmp/a.svg", "/tmp/a.txt")
        rejected.forEach { path ->
            val item = buildJsonObject { put("id", "i"); put("type", "dynamic_tool"); put("viewedImagePath", path) }
            assertTrue(path, toolActivities(projection(item.toString())).single().images.isEmpty())
        }
        val accepted = toolActivities(projection("""{"id":"i","type":"dynamic_tool","viewedImagePath":"file:///tmp/a%20b+c.png"}""")).single().images.single()
        assertEquals("/tmp/a b+c.png", accepted.path)
        assertEquals("a b+c.png", accepted.fileName)
    }

    @Test fun inheritedHydratedImagesUseSourceIdentityAndExpireOnRevisionChange() {
        val compact = json("""{"thread":{"id":"child"},"visibleTurnItems":[{"position":4,"sourceThreadId":"parent","sourceItemId":"native","item":{"id":"visible","type":"dynamic_tool","status":"completed","updatedAt":"one","outputOmitted":true}}]}""")
        assertTrue(toolActivities(compact).single().images.isEmpty())
        val loaded = hydrateActivityProjection(compact, "visible", json("""{"id":"native","type":"dynamic_tool","output":[{"type":"image","mimeType":"image/png"}]}"""))
        val image = toolActivities(loaded).single().images.single()
        assertEquals("parent", image.sourceThreadId)
        assertEquals("native", image.toolOutputItemId)
        assertEquals(image.identity, toolActivities(retainHydratedActivities(compact, loaded)).single().images.single().identity)
        val revised = json(compact.toString().replace("\"one\"", "\"two\""))
        assertTrue(toolActivities(retainHydratedActivities(revised, loaded)).single().images.isEmpty())
        val reloaded = hydrateActivityProjection(revised, "visible", json("""{"id":"native","type":"dynamic_tool","output":[{"type":"image","mimeType":"image/png"}]}"""))
        assertNotEquals(image.identity, toolActivities(reloaded).single().images.single().identity)
    }

    @Test fun outputContentAndViewedPathRevisionChangeReferenceIdentity() {
        val first = projection("""{"id":"i","type":"dynamic_tool","viewedImagePath":"/tmp/a.png","updatedAt":"one","output":{"type":"image","mimeType":"image/png","data":"ONE"}}""")
        val next = json(first.toString().replace("\"one\"", "\"two\"").replace("\"ONE\"", "\"TWO\""))
        val before = toolActivities(first).single().images
        val after = toolActivities(next).single().images
        assertEquals(2, before.size)
        before.zip(after).forEach { (old, new) -> assertNotEquals(old.identity, new.identity) }
        val sameRevisionDifferentBytes = json(first.toString().replace("\"ONE\"", "\"OTHER\""))
        assertNotEquals(before.last().identity, toolActivities(sameRevisionDifferentBytes).single().images.last().identity)
    }

    @Test fun sourceArrayIsNotADirectMimeMarker() {
        val activity = toolActivities(projection("""{"id":"i","type":"dynamic_tool","output":{"type":"image","source":[],"mimeType":"image/png"}}""")).single()
        assertTrue(activity.images.isEmpty())
    }
}
