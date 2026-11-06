package com.tajchert.trefoil.ui.screens

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StreamDocumentTest {
    @Test fun configurationCannotCloseScriptElement() {
        val escaped = escapeStreamConfiguration("{\"deviceId\":\"</script><script>alert(1)</script>\"}")
        assertFalse(escaped.contains("<"))
        assertEquals("</script><script>alert(1)</script>", Json.parseToJsonElement(escaped).let {
            (it as kotlinx.serialization.json.JsonObject)["deviceId"].toString().removeSurrounding("\"")
        })
    }
    @Test fun resourcesStayUnderExactOriginAndApiRoot() {
        val policy = StreamResourcePolicy("https://env.example/api/device-hub", "wss://env.example/api/device-hub")
        assertTrue(policy.allowsResource("https://env.example/api/device-hub/vendor/serve-sim/stream?wsTicket=media"))
        listOf("https://env.example.evil/api/device-hub/x", "https://env.example/api/device-hub-evil/x",
            "http://env.example/api/device-hub/x", "file:///etc/passwd", "content://private", "https://env.example/api/preview-stream/x",
            "https://user:pass@env.example/api/device-hub/x", "https://env.example/api/device-hub/../secret").forEach {
            assertFalse(it, policy.allowsResource(it))
        }
        assertFalse(policy.allowsNavigation("https://env.example/"))
        assertFalse(policy.allowsNavigation("about:blank"))
        assertTrue(policy.csp.contains("connect-src https://env.example wss://env.example;"))
        assertFalse(policy.csp.contains("connect-src *"))
        assertTrue(policy.csp.contains("frame-src 'none'"))
    }
    @Test fun messagesRejectMalformedFieldsAndNeverExposeRemoteErrorDetail() {
        assertNull(parseStreamMessage("[]"))
        assertNull(parseStreamMessage("{\"type\":\"input\",\"connected\":\"true\"}"))
        assertNull(parseStreamMessage("{\"type\":\"status\",\"status\":\"invented\"}"))
        assertNull(parseStreamMessage("{\"type\":\"viewport\",\"width\":0,\"height\":1}"))
        assertEquals(StreamMessage.Input(true), parseStreamMessage("{\"type\":\"input\",\"connected\":true,\"newField\":1}"))
        assertEquals(StreamMessage.Status(StreamPhase.Error), parseStreamMessage("{\"type\":\"status\",\"status\":\"error\",\"detail\":\"secret remote content\"}"))
        assertNull(parseStreamMessage("{\"type\":\"control\",\"canOperate\":true,\"controller\":\"unknown\"}"))
    }
    @Test fun scriptPayloadCannotCloseHtmlScriptAndDeviceTextUsesUnicodeSharedPath() {
        val policy = StreamResourcePolicy("https://env.example/api/device-hub", "wss://env.example/api/device-hub")
        val html = streamDocument("{}", "const example='</ScRiPt><script>bad()';", false, "Bridge1", "Nonce1", policy, true)
        assertEquals(1, Regex("</script[ >]", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html.contains("<\\/script"))
        val target = com.tajchert.trefoil.core.model.ScreenTarget.Device("t", "h", "d", "android")
        val script = streamCommandScript(target, StreamCommand.Text("Café 👩‍💻"))
        assertNotNull(script)
        assertTrue(script!!.contains("T3DeviceStream?.text("))
        assertNull(streamCommandScript(target.copy(platform = "ios"), StreamCommand.Text("hello")))
    }
    @Test fun controllerDropsCommandsAndDoesNotReplayAfterRebinding() {
        val controller = StreamController()
        assertFalse(controller.send(StreamCommand.Home))
        val sent = mutableListOf<StreamCommand>()
        val first = controller.attach { sent += it; true }
        assertTrue(controller.send(StreamCommand.Home))
        controller.detach(first)
        assertFalse(controller.send(StreamCommand.Back))
        val second = controller.attach { sent += it; true }
        controller.detach(first)
        assertTrue(controller.send(StreamCommand.Rotate))
        controller.detach(second)
        assertEquals(listOf(StreamCommand.Home, StreamCommand.Rotate), sent)
    }
}
