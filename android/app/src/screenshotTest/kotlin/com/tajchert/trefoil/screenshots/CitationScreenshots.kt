package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.AssistantCitation
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.joinCitationDraft
import com.tajchert.trefoil.ui.ConversationComposer
import com.tajchert.trefoil.ui.MessageRow
import com.tajchert.trefoil.ui.TrefoilTheme

private val fx = ConversationFixtures

private fun quote(text: String, comment: String? = null) =
    AssistantCitation("env-1", "thread-1", "assistant-1", text, 10, 10L + text.length, "…the earlier words ", " and what follows…", comment)

private val Quotes = listOf(
    quote("The retry only helps when the failure is transient, so cap it at three attempts.", comment = "Can we cap it lower for the mobile client?"),
    quote("Use a bounded window"),
)

private val SentText = joinCitationDraft("Please apply both to the sync code.", Quotes)

@Composable
private fun SentMessage(dark: Boolean) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) { MessageRow(ConversationMessage("user-1", "user", SentText), stacked = false, showMetadata = false) }
        }
    }
}

@Composable
private fun CitationComposer(dark: Boolean) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            ConversationComposer(fx.chatState(), null, joinCitationDraft("Apply both.", Quotes), {}, false, false, {}, {}, { _, _ -> }, stop = {}, onSend = {},
                onCancelQueued = {}, onSteerQueued = {}, onResumeQueue = {})
        }
    }
}

/** A sent message that quotes assistant responses: quote blocks (with the comment) and the user's words, never the raw link. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Citation_SentMessage_Light() = SentMessage(dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Citation_SentMessage_Dark() = SentMessage(dark = true)

/** The composer with two quotes as chips (one with a comment) above the words typed. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Citation_ComposerChips_Light() = CitationComposer(dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Citation_ComposerChips_Dark() = CitationComposer(dark = true)
