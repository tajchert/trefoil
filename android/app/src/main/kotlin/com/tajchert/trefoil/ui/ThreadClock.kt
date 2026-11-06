package com.tajchert.trefoil.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Detail routes stop clock work while backgrounded; returning immediately reads wall time. */
@Composable
internal fun rememberThreadWorkClock(ticking: Boolean): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock by produceState(System.currentTimeMillis(), lifecycle, ticking) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                value = System.currentTimeMillis()
                if (!ticking) awaitCancellation()
                delay(nextThreadClockDelay(value, true, emptyList()))
            } while (coroutineContext.isActive)
        }
    }
    return clock
}
