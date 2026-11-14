package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SubagentWorkingRow(roster: SubagentRoster, onOpenAgents: (() -> Unit)?) {
    if (roster.liveCount == 0) return
    val c = Tf.colors
    val total = roster.subagents.size
    val label = "$total ${if (total == 1) "subagent" else "subagents"}"
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, ${roster.liveCount} working, ${roster.settledCount} done" }
            .then(if (onOpenAgents != null) Modifier.clickable(role = Role.Button, onClick = onOpenAgents) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val agents = roster.subagents.take(3)
        Box(Modifier.width((24 + (agents.size - 1) * 16).dp).height(24.dp)) {
            agents.forEachIndexed { index, agent ->
                Box(Modifier.offset(x = (index * 16).dp).size(24.dp).background(c.s2, CircleShape), contentAlignment = Alignment.Center) {
                    ProviderMark(agent.driver, null, Modifier.size(15.dp))
                }
            }
        }
        Column {
            Text(label, style = TrefoilType.meta.copy(fontWeight = FontWeight.Bold), color = c.text)
            Text("${roster.liveCount} working · ${roster.settledCount} done", style = TrefoilType.meta, color = c.blue)
        }
    }
}
