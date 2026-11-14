package com.tajchert.trefoil

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.T3Client
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class TrefoilViewModelLifecycleTest {
    private lateinit var model: TrefoilViewModel
    private lateinit var client: T3Client
    private lateinit var mutable: MutableStateFlow<ClientState>
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")

    @Before fun setUp() {
        model = TrefoilViewModel(RuntimeEnvironment.getApplication())
        client = TrefoilViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.get(model) as T3Client
        // Seed an established session without touching a real server or weakening production constructors.
        @Suppress("UNCHECKED_CAST")
        mutable = T3Client::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(client) as MutableStateFlow<ClientState>
        mutable.value = ClientState(environment = environment, connection = ConnectionStatus.Connected,
            selectedThreadId = "thread", canWrite = true)
        val socketType = Class.forName("okhttp3.WebSocket")
        val socket = Proxy.newProxyInstance(socketType.classLoader, arrayOf(socketType)) { _, method, _ ->
            when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Long.TYPE -> 0L; else -> null }
        }
        T3Client::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
        setPongAge(0)
    }

    @After fun tearDown() { model.viewModelScope.cancel(); client.close() }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun setPongAge(age: Long) = T3Client::class.java.getDeclaredField("pongAt").apply { isAccessible = true }.setLong(client, System.currentTimeMillis() - age)

    @Test fun stalePongReconnectsEvenWhenStateIsConnected() {
        model.pause()
        setPongAge(20_001)
        model.resume()
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.id > 0)
    }

    @Test fun resumeWithinGraceKeepsLiveSessionAndDoesNotStartAConnectionAttempt() {
        model.pause()
        advance(59_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        model.resume()
        advance(2_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertTrue(model.state.value.canWrite)
        assertEquals("thread", model.state.value.selectedThreadId)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun graceExpiresAndRetainsSelectionWithWritesDisabled() {
        model.pause()
        advance(BACKGROUND_CONNECTION_GRACE_MS + 1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        assertFalse(model.state.value.canWrite)
        assertEquals("thread", model.state.value.selectedThreadId)
    }

    @Test fun nextPauseGetsANewGracePeriod() {
        model.pause(); advance(40_000); model.resume()
        model.pause(); advance(40_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(20_001)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun socketLostDuringGraceStartsNormalReconnectOnResume() {
        model.pause(); advance(1_000)
        mutable.value = mutable.value.copy(connection = ConnectionStatus.Error, canWrite = false, error = "Connection lost")
        model.resume()
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.id > 0)
    }

    @Test fun repeatedPauseDoesNotExtendGrace() {
        model.pause(); advance(40_000); model.pause(); advance(20_001)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun filePickerKeepsSshLoginWhileGraceStillExpires() {
        val login = SshConnectionInput("Fixture", "fixture.invalid", "fixture")
        @Suppress("UNCHECKED_CAST")
        val pending = TrefoilViewModel::class.java.getDeclaredField("_sshLogin").apply { isAccessible = true }.get(model) as MutableStateFlow<SshConnectionInput?>
        pending.value = login
        model.sshFilePickerActive = true
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1)
        assertEquals(login, model.sshLogin.value)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }
}
