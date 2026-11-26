package com.tajchert.trefoil.ui.issues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.IssueLinkedThread
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.handPointer

/**
 * Threads that link an issue. Stateless: the caller hides it when [threads] is empty or linked threads are unsupported.
 * Rows only carry the thread title; archived and settled threads get a label.
 */
@Composable
internal fun LinkedThreadsSection(threads: List<IssueLinkedThread>, onOpen: (IssueLinkedThread) -> Unit, modifier: Modifier = Modifier) {
    if (threads.isEmpty()) return
    val c = Tf.colors
    Column(modifier.fillMaxWidth()) {
        Text("LINKED THREADS", Modifier.semantics { heading() }.padding(bottom = 6.dp), style = TrefoilType.section, color = c.muted)
        threads.forEach { thread ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).handPointer(true).clickable(role = Role.Button) { onOpen(thread) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(thread.title.ifBlank { "Untitled thread" }, Modifier.weight(1f, fill = false), style = TrefoilType.preview, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (thread.archived) ThreadLabel("Archived")
                else if (thread.settled) ThreadLabel("Settled")
            }
        }
    }
}

@Composable
private fun ThreadLabel(text: String) {
    val c = Tf.colors
    Text(text, Modifier.background(c.s3, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), style = TrefoilType.meta, color = c.muted, maxLines = 1)
}
