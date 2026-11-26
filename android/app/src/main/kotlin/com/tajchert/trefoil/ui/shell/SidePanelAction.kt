package com.tajchert.trefoil.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** The one action a tab shows in the docked side panel's tab bar, left of Close: what its own header used to carry. */
class PanelAction(val icon: ImageVector, val description: String, val enabled: Boolean, val onClick: () -> Unit)

/**
 * Where a screen hosted in the docked side panel hands its single header action to the panel's tab bar. The screen keeps the click
 * handler, the enabled rule and the accessible name; the panel only draws the button. Null while the tab shows no such action.
 */
@Stable
class SidePanelHost {
    var action by mutableStateOf<PanelAction?>(null)

    /** The list form, for a tab with more than one action (the Thread tab: Maximize, Add to chat). Wins over [action] while non-empty. */
    var actionList by mutableStateOf<List<PanelAction>>(emptyList())

    /** What the tab bar draws left of Close: the list form while a tab offers it, otherwise the single action. At most [MAX_ACTIONS]. */
    val shown: List<PanelAction> get() = actionList.ifEmpty { listOfNotNull(action) }.take(MAX_ACTIONS)

    companion object { const val MAX_ACTIONS = 2 }
}

/**
 * Offers [onClick] to the panel's tab bar while [host] is set and [active]. It leaves when this leaves composition, the tab changes
 * or [active] turns false; a newer offer from another screen is never removed by an older one going away.
 */
@Composable
fun PanelActionEffect(host: SidePanelHost?, active: Boolean, icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    if (host == null || !active) return
    val latest by rememberUpdatedState(onClick)
    DisposableEffect(host, icon, description, enabled) {
        val mine = PanelAction(icon, description, enabled) { latest() }
        host.action = mine
        onDispose { if (host.action === mine) host.action = null }
    }
}

/**
 * The list form of [PanelActionEffect]: offers up to [SidePanelHost.MAX_ACTIONS] actions in order while [active]. Click handlers are the
 * latest ones; the offer is replaced when an icon, name or enabled flag changes and is removed only if it is still this one's.
 */
@Composable
fun PanelActionsEffect(host: SidePanelHost?, active: Boolean, actions: List<PanelAction>) {
    if (host == null || !active) return
    val latest by rememberUpdatedState(actions)
    val key = actions.take(SidePanelHost.MAX_ACTIONS).map { Triple(it.icon, it.description, it.enabled) }
    DisposableEffect(host, key) {
        val mine = key.mapIndexed { i, (icon, description, enabled) -> PanelAction(icon, description, enabled) { latest.getOrNull(i)?.onClick?.invoke() } }
        host.actionList = mine
        onDispose { if (host.actionList === mine) host.actionList = emptyList() }
    }
}
