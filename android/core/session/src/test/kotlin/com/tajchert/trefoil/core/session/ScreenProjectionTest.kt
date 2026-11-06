package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenProjectionTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    @Test fun browserProjectionExcludesDesktopAndOtherThreadTabs() {
        val tabs = browserScreens(json("""{"sessions":[{"threadId":"t","tabId":"server","runtime":"server","navStatus":{"_tag":"Success","url":"https://fixture.test","title":"Fixture"}},{"threadId":"t","tabId":"desktop","runtime":"desktop","navStatus":{"_tag":"Idle"}},{"threadId":"other","tabId":"foreign","runtime":"server"}],"serverEpoch":"e","revision":2,"future":true}"""), "t", true)
        assertEquals(listOf("server"), tabs.map { it.tabId })
        assertEquals("Fixture", tabs.single().title)
        assertTrue(tabs.single().interactive)
    }
    @Test fun deviceProjectionKeepsHostIdentityAndOnlySelectedThreadSessions() {
        val targets = deviceScreens(json("""{"devices":[{"hostId":"local","id":"same","platform":"android","name":"Local","version":"16","booted":true,"physical":false},{"hostId":"remote","id":"same","platform":"android","name":"Remote","version":"16","booted":true,"physical":false}],"hosts":[{"id":"local","kind":"local"}],"simlockHosts":[{"id":"remote","kind":"simlock"}],"sessions":[{"threadId":"t","hostId":"remote","deviceId":"same","platform":"android","openedAt":"now"},{"threadId":"other","hostId":"local","deviceId":"same","platform":"android","openedAt":"now"}],"revision":3}"""), "t")
        assertEquals(1, targets.size)
        assertEquals("remote", targets.single().hostId)
        assertEquals("Remote", targets.single().name)
        assertTrue(targets.single().serveSimOnly)
    }
    @Test fun mediaValidationUsesSavedConsentAndExactEnvironmentRoot() {
        val environment = SavedEnvironment("env", "Test", "http://192.168.1.20:3773", "secret", allowInsecureHttp = true)
        val target = ScreenTarget.Device("t", "remote", "id", "android")
        val access = ScreenAccess("env", 1, target, "http://192.168.1.20:3773/api/device-hub", "ws://192.168.1.20:3773/api/device-hub", mapOf("wsTicket" to "ticket-secret", "hostId" to "remote"))
        assertTrue(validateScreenAccess(access, environment))
        assertFalse(validateScreenAccess(access, environment.copy(allowInsecureHttp = false)))
        assertFalse(validateScreenAccess(access.copy(httpBase = "https://elsewhere.test/api/device-hub"), environment))
        assertFalse(validateScreenAccess(access.copy(wsBase = "ws://elsewhere.test/api/device-hub"), environment))
        assertFalse(validateScreenAccess(access.copy(query = access.query + ("hostId" to "local")), environment))
        assertFalse(access.toString().contains("ticket-secret"))
        assertFalse(access.toString().contains("192.168"))
    }
}
