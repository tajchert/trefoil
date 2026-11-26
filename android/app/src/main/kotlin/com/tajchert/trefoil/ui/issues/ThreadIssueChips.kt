package com.tajchert.trefoil.ui.issues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.ThreadIssueLink
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.TfAdaptiveSheet
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer

/** Label of the header chip: the first link's key, plus "+N" for the others. Empty when the thread has no linked issue. */
internal fun issueChipLabel(links: List<ThreadIssueLink>): String =
    when (links.size) { 0 -> ""; 1 -> links[0].displayKey; else -> "${links[0].displayKey} +${links.size - 1}" }

/** Spoken label of the header chip. */
internal fun issueChipDescription(links: List<ThreadIssueLink>): String =
    when (links.size) { 0 -> ""; 1 -> "Linked issue ${links[0].displayKey}"; else -> "${links.size} linked issues, first ${links[0].displayKey}" }

/** Badge text of a link's origin: "agent" when the agent linked it, otherwise "manual". */
internal fun issueSourceLabel(source: String): String = if (source == "agent") "agent" else "manual"

/** Only http and https links are opened from an issue row; anything else is dropped. */
internal fun isOpenableIssueUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return (scheme == "http" || scheme == "https") && url.length > scheme.length + 3
}

/**
 * Thread-header entry point for the linked issues: the first link's key and "+N" for the rest ("ABC-12 +2").
 * It draws nothing when [links] is empty; a click opens [ThreadIssueLinksSheet].
 */
@Composable
fun ThreadIssueChip(links: List<ThreadIssueLink>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return
    val c = Tf.colors
    val description = issueChipDescription(links)
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text,
        modifier = modifier.padding(end = 2.dp).focusRing(RoundedCornerShape(16.dp)).handPointer()
            .testTag("thread-issue-chip").semantics { contentDescription = description; role = Role.Button },
    ) {
        Row(Modifier.height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(TfIcons.Link, null, Modifier.size(16.dp))
            Text(issueChipLabel(links), style = TrefoilType.meta.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Every issue linked to the thread: key, title and a small "agent" / "manual" badge. A row calls [onOpen]. With [onUnlink] each row
 * also gets an Unlink action; null leaves it out.
 */
@Composable
fun ThreadIssueLinksSheet(
    links: List<ThreadIssueLink>, onOpen: (ThreadIssueLink) -> Unit, onDismiss: () -> Unit, onUnlink: ((ThreadIssueLink) -> Unit)? = null,
) {
    TfAdaptiveSheet(onDismiss = onDismiss, dialogMaxWidth = IssueSheetDialogWidth) { ThreadIssueLinksContent(links, onOpen, onUnlink) }
}

private val IssueSheetDialogWidth = 480.dp

/** Sheet body, separate from the sheet chrome so it renders standalone. */
@Composable
internal fun ThreadIssueLinksContent(links: List<ThreadIssueLink>, onOpen: (ThreadIssueLink) -> Unit, onUnlink: ((ThreadIssueLink) -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp).testTag("thread-issue-links")) {
        Text("Issues", Modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall, color = c.text)
        links.forEachIndexed { index, link ->
            IssueLinkRow(link, onOpen, onUnlink)
            if (index != links.lastIndex) HorizontalDivider(color = c.line)
        }
    }
}

@Composable
private fun IssueLinkRow(link: ThreadIssueLink, onOpen: (ThreadIssueLink) -> Unit, onUnlink: ((ThreadIssueLink) -> Unit)?) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().testTag("issue-link-${link.displayKey}"), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).focusRing().handPointer().clickable(role = Role.Button, onClickLabel = "Open ${link.displayKey}") { onOpen(link) }
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(link.displayKey, Modifier.weight(1f, fill = false), style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SourceBadge(link.source)
                }
                Text(link.title, style = TrefoilType.rowTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(TfIcons.ChevronRight, null, Modifier.padding(top = 2.dp).size(20.dp), tint = c.muted)
        }
        if (onUnlink != null) TextButton(onClick = { onUnlink(link) }, Modifier.testTag("issue-unlink-${link.displayKey}")) { Text("Unlink", color = c.red) }
    }
}

@Composable
private fun SourceBadge(source: String) {
    val c = Tf.colors
    Text(
        issueSourceLabel(source), Modifier.background(c.s2, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        style = TrefoilType.meta.copy(fontSize = 11.sp, lineHeight = 14.sp), color = c.muted, maxLines = 1,
    )
}
