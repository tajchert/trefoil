package com.tajchert.trefoil.ui.issues

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.tajchert.trefoil.core.session.IssueLinkNotice

/**
 * Shows [notice] ("Thread started; the issue was not linked") until the user acts. The action calls [onRetry] once, which sends one new
 * explicit link; dismissing calls [onDismiss]. Cancelling the caller (the notice went away by itself) calls neither.
 */
internal suspend fun showIssueLinkNotice(snackbar: SnackbarHostState, notice: IssueLinkNotice, onRetry: () -> Unit, onDismiss: () -> Unit) {
    when (snackbar.showSnackbar(notice.message, "Link issue", withDismissAction = true, duration = SnackbarDuration.Indefinite)) {
        SnackbarResult.ActionPerformed -> onRetry()
        SnackbarResult.Dismissed -> onDismiss()
    }
}
