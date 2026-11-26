package com.tajchert.trefoil.ui

import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class IssueAwareUriHandlerTest {
    private class Recorder : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) { opened += uri }
    }

    @Test fun anIssueLinkIsHandledAndNeverReachesThePlatform() {
        val platform = Recorder(); val seen = mutableListOf<String>()
        IssueAwareUriHandler(platform) { seen += it; true }.openUri("https://github.com/o/r/issues/7")
        assertEquals(listOf("https://github.com/o/r/issues/7"), seen)
        assertEquals(emptyList<String>(), platform.opened)
    }

    @Test fun anyOtherLinkFallsBackToThePlatformUnchanged() {
        val platform = Recorder()
        val handler = IssueAwareUriHandler(platform) { false }
        handler.openUri("https://example.com/docs?q=1#top")
        handler.openUri("mailto:a@b.c")
        assertEquals(listOf("https://example.com/docs?q=1#top", "mailto:a@b.c"), platform.opened)
    }
}
