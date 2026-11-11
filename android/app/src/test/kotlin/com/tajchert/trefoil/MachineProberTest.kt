package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MachineProberTest {
    private val direct = SavedEnvironment("lan", "Mac mini", "https://mini.example.test/", "fixture-token")
    private fun ssh(password: String? = "fixture-password", fingerprint: String = "SHA256:pinned") =
        SavedEnvironment("ssh", "home", "", "fixture-token", ssh = SshProfile("home.test", 22, "mike", fingerprint, "ssh-ed25519", password = password))

    private class FakeTunnel : ProbeTunnel {
        override val baseUrl = "http://127.0.0.1:41000/"
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun directMachinesUseTheSavedAddress() = runBlocking {
        var seen: String? = null
        val prober = MachineProber({ _, url -> seen = url; MachineProbe.Online }, { _, _ -> error("SSH is not used") })
        assertEquals(MachineProbe.Online, prober.probe(direct, emptyList()))
        assertEquals(direct.baseUrl, seen)
    }

    @Test fun directTimeoutAndFailuresAreUnreachable() = runBlocking {
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> awaitCancellation() }, { _, _ -> error("unused") }, directTimeoutMillis = 50).probe(direct, emptyList()))
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> error("boom") }, { _, _ -> error("unused") }).probe(direct, emptyList()))
    }

    @Test fun sshWithoutSavedCredentialsStaysUnknownWithoutConnecting() = runBlocking {
        var opened = false
        val prober = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> opened = true; FakeTunnel() })
        assertEquals(MachineProbe.Unknown, prober.probe(ssh(password = null), emptyList()))
        assertFalse(opened)
    }

    @Test fun sshProbesThroughAPinnedTunnelAndAlwaysClosesIt() = runBlocking {
        val tunnel = FakeTunnel()
        var pin: String? = null
        var url: String? = null
        val prober = MachineProber({ _, base -> url = base; MachineProbe.Online }, { _, expected -> pin = expected; tunnel })
        val trusted = listOf(SshHostKey("HOME.test", 22, "ssh-ed25519", "SHA256:trusted-store"))
        assertEquals(MachineProbe.Online, prober.probe(ssh(), trusted))
        assertEquals("SHA256:trusted-store", pin)
        assertEquals(tunnel.baseUrl, url)
        assertTrue(tunnel.closed)

        val failing = FakeTunnel()
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> error("boom") }, { _, _ -> failing }).probe(ssh(), emptyList()))
        assertTrue(failing.closed)
    }

    @Test fun sshHostKeyProblemsNeverPromptAndNeedAttention() = runBlocking {
        val key = SshHostKey("home.test", 22, "ssh-ed25519", "SHA256:other")
        val changed = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> throw SshHostKeyException(key, changed = true) })
        assertEquals(MachineProbe.NeedsAttention, changed.probe(ssh(), emptyList()))
        // No pinned key at all: a probe must not open a connection that would ask for trust.
        var opened = false
        val unpinned = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> opened = true; FakeTunnel() })
        assertEquals(MachineProbe.NeedsAttention, unpinned.probe(ssh(fingerprint = ""), emptyList()))
        assertFalse(opened)
    }

    @Test fun sshLoginFailuresMapToAuthOrUnreachable() = runBlocking {
        fun failing(message: String) = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> throw SshConnectionException(message) })
        assertEquals(MachineProbe.AuthFailed, failing("SSH authentication failed. The server rejected the username or credentials. Edit your login and try again.").probe(ssh(), emptyList()))
        assertEquals(MachineProbe.Unreachable, failing("SSH connection timed out. Check the address, port and network.").probe(ssh(), emptyList()))
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> awaitCancellation() }, sshTimeoutMillis = 50).probe(ssh(), emptyList()))
    }

    @Test fun autoConnectPrefersTheLastUsedMachine() {
        val a = SavedEnvironment("a", "A", "https://a.test/", "t")
        val b = SavedEnvironment("b", "B", "https://b.test/", "t")
        val c = SavedEnvironment("c", "C", "https://c.test/", "t")
        val all = listOf(a, b, c)
        assertEquals(AutoConnectDecision.Connect("b"), autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Online, "c" to MachineProbe.Checking)))
        assertEquals(AutoConnectDecision.Wait, autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Checking)))
        // Last-used unreachable: fall back to the only reachable machine once every probe is done.
        assertEquals(AutoConnectDecision.Wait, autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Unreachable, "c" to MachineProbe.Checking)))
        assertEquals(AutoConnectDecision.Connect("a"), autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Unreachable, "c" to MachineProbe.AuthFailed)))
        assertEquals(AutoConnectDecision.None, autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Unreachable, "c" to MachineProbe.Online)))
        assertEquals(AutoConnectDecision.Connect("c"), autoConnectDecision(null, all, mapOf("a" to MachineProbe.Unknown, "b" to MachineProbe.NeedsAttention, "c" to MachineProbe.Online)))
        assertEquals(AutoConnectDecision.None, autoConnectDecision("forgotten", all, emptyMap()))
        assertEquals(AutoConnectDecision.None, autoConnectDecision(null, emptyList(), emptyMap()))
    }
}
