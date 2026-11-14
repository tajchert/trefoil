package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.UsageRange

/**
 * Full-pane Usage page: Limits | Cost | Tokens for the connected environment.
 * Limits come from [ClientState.providers]; cost/tokens from [usage].
 */
@Composable
internal fun UsageScreen(
    state: ClientState,
    usage: UsageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectRange: (UsageRange) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) // TODO(usage-ui-screen): implement
}
