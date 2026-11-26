package com.tajchert.trefoil.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Env = SavedEnvironment("env", "Env", "https://e.test", "t")
private val Parent = ThreadSummary("p", "proj", "Parent", latestUserMessageAt = "2026-11-26T10:00:00Z")
private fun sideChat(id: String, title: String, createdAt: String = "2026-11-26T11:00:00Z", asked: Boolean = true, status: String = "idle", request: String? = null) =
    ThreadSummary(id, "proj", title, status = status, parentThreadId = "p", sideChat = true, createdAt = createdAt, latestUserMessageAt = if (asked) createdAt else null, pendingRequestKind = request)
private fun state(vararg threads: ThreadSummary, supported: Boolean = true, canWrite: Boolean = true, messages: List<ConversationMessage> = listOf(ConversationMessage("u1", "user", "Hello"))) =
    ClientState(environment = Env, connection = ConnectionStatus.Connected, selectedThreadId = "p", canWrite = canWrite, sideChatsSupported = supported, threads = listOf(Parent) + threads, messages = messages)

/** The composed composer text (quotes, thread references, words), `/side`, the side-chat row, and references in sent messages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThreadReferenceUiTest {
    @get:Rule val compose = createComposeRule()

    private val plan = threadReferenceFor("s1", "Plan side")
    private val notes = threadReferenceFor("s2", "Notes")
    private val quote = AssistantCitation("env", "p", "m1", "quoted text", 0, 11, "", "", null)

    /* ---------- composer text ---------- */

    @Test fun composedTextRoundTripsInTheFixedOrder() {
        val text = joinComposerText("what about these?", listOf(quote), listOf(plan, notes))
        val parts = splitComposerText(text)
        assertEquals(ComposerParts("what about these?", listOf(quote), listOf(plan, notes)), parts)
        assertEquals(text, joinComposerText(parts.words, parts.citations, parts.references))
        // Quotes first, then the thread references, then the words.
        assertTrue(text.startsWith("[") || text.indexOf("t3-citation") < text.indexOf("t3-context"))
        assertTrue(text.indexOf("t3-context") < text.indexOf("what about these?"))
    }

    @Test fun composedTextWithOnlyOneKindOfMarkerStillRoundTrips() {
        assertEquals(ComposerParts("hi", emptyList(), listOf(plan)), splitComposerText(joinComposerText("hi", emptyList(), listOf(plan))))
        assertEquals(ComposerParts("hi", listOf(quote), emptyList()), splitComposerText(joinComposerText("hi", listOf(quote), emptyList())))
        assertEquals(ComposerParts("plain", emptyList(), emptyList()), splitComposerText("plain"))
        assertEquals("plain", joinComposerText("plain", emptyList(), emptyList()))
    }

    @Test fun markersInAnyOrderSettleIntoTheFixedOrderAndStayThere() {
        val shuffled = "${plan.marker()}\n${quote.let { joinCitationDraft("", listOf(it)).trim() }}\n\nwords"
        val once = splitComposerText(shuffled).let { joinComposerText(it.words, it.citations, it.references) }
        val twice = splitComposerText(once).let { joinComposerText(it.words, it.citations, it.references) }
        assertEquals(once, twice)
        // Hand-ordered text keeps a stray line break in its words, but never loses a marker.
        splitComposerText(once).let { assertEquals(ComposerParts("words", listOf(quote), listOf(plan)), it.copy(words = it.words.trim())) }
    }

    @Test fun addingAReferenceKeepsWordsAndQuotesAndNeverDuplicates() {
        val base = joinComposerText("question", listOf(quote), emptyList())
        val once = addThreadReference(base, plan, 10_000)!!
        assertEquals(ComposerParts("question", listOf(quote), listOf(plan)), splitComposerText(once))
        assertEquals(once, addThreadReference(once, plan, 10_000))
        val both = addThreadReference(once, notes, 10_000)!!
        assertEquals(listOf(plan, notes), splitComposerText(both).references)
        assertEquals(1, Regex(Regex.escape("t3-context://v1/thread/${plan.contextId}")).findAll(both).count())
    }

    @Test fun addingAReferenceThatDoesNotFitChangesNothing() {
        assertNull(addThreadReference("words", plan, "words".length + 5))
        // An already present reference needs no room.
        val present = addThreadReference("", plan, 10_000)!!
        assertEquals(present, addThreadReference(present, plan, present.length))
    }

    @Test fun removingAReferenceLeavesTheRest() {
        val text = joinComposerText("words", listOf(quote), listOf(plan, notes))
        assertEquals(ComposerParts("words", listOf(quote), listOf(notes)), splitComposerText(removeThreadReference(text, plan.contextId)))
        assertEquals(text, removeThreadReference(text, "thread_unknown"))
        assertEquals("words", removeThreadReference(joinComposerText("words", emptyList(), listOf(plan)), plan.contextId))
    }

    /* ---------- /side ---------- */

    @Test fun sideCommandNeedsServerSupportAnEligibleThreadAndTheSpace() {
        assertEquals("why?", sideChatQuestion("/side why?", state(), false))
        assertNull(sideChatQuestion("/side why?", state(supported = false), false))
        assertNull(sideChatQuestion("/side why?", state(canWrite = false), false))
        assertNull(sideChatQuestion("/side why?", state(), isDraft = true))
        assertNull(sideChatQuestion("/side", state(), false))
        assertNull(sideChatQuestion("/sidekick why?", state(), false))
        assertNull(sideChatQuestion("please /side why?", state(), false))
        assertNull(sideChatQuestion("plain", state(), false))
    }

    @Test fun anEmptyQuestionIsRecognisedButIsNotAQuestion() {
        assertEquals("", sideChatQuestion("/side   ", state(), false))
    }

    @Test fun sideCommandIsOffOnASideChatASubagentAndAThreadWithoutAMessage() {
        val onSideChat = state(sideChat("s1", "Plan")).copy(selectedThreadId = "s1")
        assertNull(sideChatQuestion("/side why?", onSideChat, false))
        val subagent = ThreadSummary("sub", "proj", "Sub", parentThreadId = "p", relationshipToParent = "subagent", creationSource = "provider")
        assertNull(sideChatQuestion("/side why?", state(subagent).copy(selectedThreadId = "sub"), false))
        val fresh = state(messages = emptyList()).copy(threads = listOf(Parent.copy(latestUserMessageAt = null)))
        assertNull(sideChatQuestion("/side why?", fresh, false))
        assertEquals("why?", sideChatQuestion("/side why?", fresh.copy(messages = listOf(ConversationMessage("u", "user", "Hi"))), false))
    }

    @Test fun theQuestionKeepsItsQuotesAndReferences() {
        val text = joinComposerText("/side compare these", listOf(quote), listOf(plan))
        val question = sideChatQuestion(text, state(), false)!!
        assertEquals(ComposerParts("compare these", listOf(quote), listOf(plan)), splitComposerText(question))
    }

    /* ---------- Add to chat ---------- */

    @Test fun addToChatIsOnlyForAskedSideChatsOfTheOpenThread() {
        val asked = sideChat("s1", "Plan side")
        val s = state(asked, sideChat("s2", "Quiet", asked = false), sideChat("s3", "Gone").copy(deletedAt = "2026-11-26T12:00:00Z"),
            ThreadSummary("sub", "proj", "Sub", parentThreadId = "p", relationshipToParent = "subagent"), sideChat("s4", "Other").copy(parentThreadId = "other"))
        val insert = addToChatReference(s, "s1")!!
        assertEquals(Triple("env", "p", plan), Triple(insert.environmentId, insert.parentThreadId, insert.reference))
        listOf("s2", "s3", "sub", "s4", "missing").forEach { assertNull(it, addToChatReference(s, it)) }
    }

    /* ---------- links, chips and marks ---------- */

    @Test fun aThreadContextLinkIsNeverASafeLink() {
        assertFalse(isSafeLink(plan.marker().substringAfter("(").removeSuffix(")")))
        assertFalse(isSafeLink("t3-context://v1/thread/x"))
        assertFalse(isSafeLink("T3-CONTEXT://v1/thread/x"))
    }

    @Test fun sideChatMarksFollowTheThreadListIndicator() {
        assertEquals(SideChatMark.Approval, sideChatMark(sideChat("a", "A", request = "approval")))
        assertEquals(SideChatMark.Input, sideChatMark(sideChat("a", "A", request = "user_input")))
        assertEquals(SideChatMark.Working, sideChatMark(sideChat("a", "A", status = "running")))
        assertEquals(SideChatMark.None, sideChatMark(sideChat("a", "A")))
    }

    @Test fun sentTextSplitsIntoWordsAndReferencesInOrder() {
        val text = "before ${plan.marker()} ${notes.marker()} after"
        val parts = userTextParts(text, collectThreadReferenceMatches(text))
        assertEquals(listOf(UserTextPart.Words("before"), UserTextPart.Reference(plan), UserTextPart.Reference(notes), UserTextPart.Words("after")), parts)
        assertEquals(listOf(UserTextPart.Words("just words")), userTextParts("just words", emptyList()))
    }

    /* ---------- the conversation ---------- */

    private fun field() = compose.onNode(hasSetTextAction())
    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun theSideChatRowListsTheThreadsSideChatsNewestFirstAndOpensOne() {
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state(sideChat("s1", "Older", "2026-11-26T09:00:00Z"), sideChat("s2", "Newer", "2026-11-26T10:30:00Z")), {}, {}, { _, _, _ -> }, onOpenThread = { opened += it }) } }
        compose.onNodeWithTag("side-chats-row").assertIsDisplayed()
        val older = compose.onNodeWithContentDescription("Side chat: Older").fetchSemanticsNode().boundsInRoot.left
        val newer = compose.onNodeWithContentDescription("Side chat: Newer").fetchSemanticsNode().boundsInRoot.left
        assertTrue("newest first", newer < older)
        compose.onNodeWithContentDescription("Side chat: Older").performClick()
        compose.runOnIdle { assertEquals(listOf("s1"), opened) }
    }

    @Test fun theSideChatRowShowsAWaitingChatsDotInItsDescription() {
        compose.setContent { MaterialTheme { Conversation(state(sideChat("s1", "Plan", request = "approval")), {}, {}, { _, _, _ -> }, onOpenThread = {}) } }
        compose.onNodeWithContentDescription("Side chat: Plan, Needs approval").assertIsDisplayed()
    }

    @Test fun theSideChatRowIsHiddenWithoutSideChatsOrWithoutAWayToOpenThem() {
        compose.setContent { MaterialTheme { Conversation(state(), {}, {}, { _, _, _ -> }, onOpenThread = {}) } }
        compose.onAllNodesWithTag("side-chats-row").assertCountEquals(0)
    }

    @Test fun theSideChatRowIsHiddenInANewThreadDraft() {
        compose.setContent { MaterialTheme { Conversation(state(sideChat("s1", "Plan")), {}, {}, { _, _, _ -> }, draft = ThreadDraft("proj", ModelOption("codex", "gpt")), onOpenThread = {}) } }
        compose.onAllNodesWithTag("side-chats-row").assertCountEquals(0)
    }

    @Test fun sideStartsASideChatInsteadOfSendingAMessage() {
        val sent = mutableListOf<String>()
        val started = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state(), {}, {}, { _, _, _ -> }, submitMessage = { s, _ -> sent += s.text }, sideChat = SideChatUi(start = { started += it })) } }
        field().performTextInput("/side why is the sky blue?")
        compose.onNodeWithTag("side-chat-hint").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals(listOf("why is the sky blue?"), started); assertTrue(sent.isEmpty()) }
    }

    @Test fun sideIsAnOrdinaryMessageWhenSideChatsAreNotSupported() {
        val sent = mutableListOf<String>()
        val started = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state(supported = false), {}, {}, { _, _, _ -> }, submitMessage = { s, _ -> sent += s.text }, sideChat = SideChatUi(start = { started += it })) } }
        field().performTextInput("/side why?")
        compose.onAllNodesWithTag("side-chat-hint").assertCountEquals(0)
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals(listOf("/side why?"), sent); assertTrue(started.isEmpty()) }
    }

    @Test fun anEmptySideQuestionCannotBeSent() {
        val sent = mutableListOf<String>()
        val started = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state(), {}, {}, { _, _, _ -> }, submitMessage = { s, _ -> sent += s.text }, sideChat = SideChatUi(start = { started += it })) } }
        field().performTextInput("/side ")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertTrue(sent.isEmpty()); assertTrue(started.isEmpty()) }
    }

    @Test fun aStartedSideChatOpensAndClearsTheComposer() {
        val opened = mutableListOf<String>()
        var consumed = 0
        var side by mutableStateOf(SideChatUi())
        compose.setContent {
            MaterialTheme { Conversation(state(), {}, {}, { _, _, _ -> }, onOpenThread = { opened += it }, sideChat = side) }
        }
        side = SideChatUi(start = { side = SideChatUi(inFlight = true, start = { }) }, consume = { consumed++ })
        field().performTextInput("/side why?")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertTrue(side.inFlight) }
        compose.onNodeWithTag("side-chat-hint").assertIsDisplayed()
        side = SideChatUi(result = SideChatResult.Started("env", "p", "s9"), consume = { consumed++ })
        compose.runOnIdle { assertEquals(listOf("s9"), opened); assertEquals(1, consumed) }
        assertEquals("", fieldText())
    }

    @Test fun aFailedSideChatShowsItsReasonAndKeepsTheText() {
        var consumed = 0
        var side by mutableStateOf(SideChatUi())
        compose.setContent { MaterialTheme { Conversation(state(), {}, {}, { _, _, _ -> }, sideChat = side) } }
        side = SideChatUi(start = { side = SideChatUi(inFlight = true) }, consume = { consumed++ })
        field().performTextInput("/side why?")
        compose.onNodeWithContentDescription("Send message").performClick()
        side = SideChatUi(result = SideChatResult.Failed("env", "p", "This server does not support side chats."), consume = { consumed++ })
        compose.onNodeWithTag("side-chat-notice").assertIsDisplayed()
        compose.onNode(hasText("This server does not support side chats.")).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, consumed) }
        assertEquals("/side why?", fieldText())
        // Editing the text again dismisses the notice.
        field().performTextInput("!")
        compose.onAllNodesWithTag("side-chat-notice").assertCountEquals(0)
    }

    @Test fun aResultForAnotherThreadIsLeftForItsOwnComposer() {
        val opened = mutableListOf<String>()
        var consumed = 0
        var side by mutableStateOf(SideChatUi())
        compose.setContent { MaterialTheme { Conversation(state(), {}, {}, { _, _, _ -> }, onOpenThread = { opened += it }, sideChat = side) } }
        side = SideChatUi(start = { side = SideChatUi(inFlight = true) }, consume = { consumed++ })
        field().performTextInput("/side why?")
        compose.onNodeWithContentDescription("Send message").performClick()
        side = SideChatUi(result = SideChatResult.Started("env", "elsewhere", "s9"), consume = { consumed++ })
        compose.runOnIdle { assertTrue(opened.isEmpty()); assertEquals(0, consumed) }
        assertEquals("/side why?", fieldText())
    }

    @Test fun addToChatPutsOneMarkerInTheParentDraftEvenWhenAddedTwice() {
        val written = mutableMapOf<ComposerDraftKey, String>()
        var insert by mutableStateOf<ThreadReferenceInsert?>(null)
        var handled = 0
        val drafts = ComposerDraftUi(updateText = { key, text -> written[key] = text })
        compose.setContent {
            MaterialTheme {
                Conversation(state(sideChat("s1", "Plan side")), {}, {}, { _, _, _ -> }, composerDrafts = drafts, onOpenThread = {},
                    threadReferenceInsert = insert, onThreadReferenceInserted = { handled++; insert = null })
            }
        }
        insert = ThreadReferenceInsert("env", "p", plan)
        compose.runOnIdle { assertEquals(1, handled) }
        val key = ComposerDraftKey.Reply("env", "p")
        compose.runOnIdle { assertEquals(plan.marker(), written[key]) }
        compose.onAllNodesWithTag("thread-reference-chip").assertCountEquals(1)
        // The words typed after it keep the marker in front.
        field().performTextInput("compare")
        insert = ThreadReferenceInsert("env", "p", plan)
        compose.runOnIdle { assertEquals(2, handled); assertEquals(ComposerParts("compare", emptyList(), listOf(plan)), splitComposerText(written.getValue(key))) }
        compose.onAllNodesWithTag("thread-reference-chip").assertCountEquals(1)
    }

    @Test fun anInsertForAnotherThreadIsNotApplied() {
        val written = mutableMapOf<ComposerDraftKey, String>()
        var handled = 0
        val drafts = ComposerDraftUi(updateText = { key, text -> written[key] = text })
        compose.setContent {
            MaterialTheme { Conversation(state(), {}, {}, { _, _, _ -> }, composerDrafts = drafts, threadReferenceInsert = ThreadReferenceInsert("env", "other", plan), onThreadReferenceInserted = { handled++ }) }
        }
        compose.runOnIdle { assertTrue(written.isEmpty()); assertEquals(0, handled) }
        compose.onAllNodesWithTag("thread-reference-chip").assertCountEquals(0)
    }

    @Test fun aReferenceChipOpensItsSideChatAndItsCloseRemovesTheMarker() {
        val opened = mutableListOf<String>()
        val written = mutableMapOf<ComposerDraftKey, String>()
        val key = ComposerDraftKey.Reply("env", "p")
        val drafts = ComposerDraftUi(get = { k -> if (k == key) ComposerDraft(key, joinComposerText("hello", emptyList(), listOf(plan)), updatedAtMillis = 1) else null }, updateText = { k, t -> written[k] = t })
        compose.setContent { MaterialTheme { Conversation(state(sideChat("s1", "Plan side")), {}, {}, { _, _, _ -> }, composerDrafts = drafts, onOpenThread = { opened += it }) } }
        compose.onNodeWithTag("thread-reference-chip").assertIsDisplayed()
        compose.onNodeWithContentDescription("Thread: Plan side").performClick()
        compose.runOnIdle { assertEquals(listOf("s1"), opened) }
        compose.onNodeWithContentDescription("Remove thread reference").performClick()
        compose.runOnIdle { assertEquals("hello", written[key]) }
        compose.onAllNodesWithTag("thread-reference-chip").assertCountEquals(0)
    }

    @Test fun aSentMessageShowsTheReferenceAsAChipWithItsLabelNotTheLink() {
        val opened = mutableListOf<String>()
        val message = ConversationMessage("m1", "user", "see ${plan.marker()} please")
        compose.setContent { MaterialTheme { Conversation(state(sideChat("s1", "Plan side"), messages = listOf(message)), {}, {}, { _, _, _ -> }, onOpenThread = { opened += it }) } }
        compose.onNodeWithTag("thread-reference-bubble-chip").assertIsDisplayed()
        compose.onAllNodes(hasText("t3-context", substring = true)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Thread: Plan side").performClick()
        compose.runOnIdle { assertEquals(listOf("s1"), opened) }
    }

    @Test fun aReferenceToAThreadThatIsNotASideChatOfThisOneDoesNotOpen() {
        val opened = mutableListOf<String>()
        val message = ConversationMessage("m1", "user", "see ${threadReferenceFor("elsewhere", "Other chat").marker()}")
        compose.setContent { MaterialTheme { Conversation(state(messages = listOf(message)), {}, {}, { _, _, _ -> }, onOpenThread = { opened += it }) } }
        compose.onNodeWithTag("thread-reference-bubble-chip").assertIsDisplayed()
        compose.onNodeWithContentDescription("Thread: Other chat").performClick()
        compose.runOnIdle { assertTrue(opened.isEmpty()); assertNotNull(compose.onNodeWithTag("thread-reference-bubble-chip")) }
    }
}
