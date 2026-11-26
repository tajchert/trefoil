package com.tajchert.trefoil.ui.issues

import com.tajchert.trefoil.core.model.IssueKey
import com.tajchert.trefoil.core.model.ThreadIssueLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadIssueChipsTest {
    private fun link(displayKey: String) = ThreadIssueLink(IssueKey("github", "github.com", "acme/widgets", displayKey.trimStart('#')), "p1", "https://github.com/acme/widgets/issues/1",
        displayKey = displayKey, title = "Title")

    @Test fun chipLabelShowsTheFirstKeyAndThePlusCount() {
        assertEquals("", issueChipLabel(emptyList()))
        assertEquals("#1", issueChipLabel(listOf(link("#1"))))
        assertEquals("#1 +2", issueChipLabel(listOf(link("#1"), link("#2"), link("#3"))))
    }

    @Test fun descriptionNamesTheCount() {
        assertEquals("Linked issue #1", issueChipDescription(listOf(link("#1"))))
        assertEquals("3 linked issues, first #1", issueChipDescription(listOf(link("#1"), link("#2"), link("#3"))))
    }

    @Test fun sourceBadgeIsAgentOrManual() {
        assertEquals("agent", issueSourceLabel("agent"))
        assertEquals("manual", issueSourceLabel("manual"))
        assertEquals("manual", issueSourceLabel("anything-else"))
    }

    @Test fun onlyHttpAndHttpsUrlsOpen() {
        assertTrue(isOpenableIssueUrl("https://github.com/acme/widgets/issues/1"))
        assertTrue(isOpenableIssueUrl("HTTP://example.com/x"))
        assertFalse(isOpenableIssueUrl("javascript:alert(1)"))
        assertFalse(isOpenableIssueUrl("file:///etc/passwd"))
        assertFalse(isOpenableIssueUrl("intent://scan/#Intent;end"))
        assertFalse(isOpenableIssueUrl("https://"))
        assertFalse(isOpenableIssueUrl("github.com/acme"))
    }
}
