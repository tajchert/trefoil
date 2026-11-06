package com.tajchert.trefoil.core.ssh

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory

class TunnelShutdownRegressionTest {
    @Test fun repeatedAndConcurrentCloseCannotAccumulateForwardingWorkers() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            repeat(12) {
                val tunnel = fixture.open()
                val forwarding = SshTunnel::class.java.getDeclaredField("forwarder").apply { isAccessible = true }.get(tunnel)
                val listener = forwarding.javaClass.getDeclaredField("runningThread").apply { isAccessible = true }.get(forwarding) as Thread
                val scope = SshTunnel::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(tunnel) as CoroutineScope
                val tasks = scope.coroutineContext.job.children.toList()
                try {
                    coroutineScope { List(4) { launch(Dispatchers.IO) { tunnel.close() } }.joinAll() }
                    withTimeout(5000) { tasks.joinAll() }
                    assertFalse(tunnel.active.value)
                    assertTrue(tasks.all { it.isCompleted })
                } finally {
                    if (listener.stackTrace.any { it.className.endsWith("LocalPortForwarder") && it.methodName == "listen" }) listener.interrupt()
                    withTimeout(5000) { tasks.joinAll() }
                    tunnel.close()
                }
            }
        }
    }
    @Test fun closeStopsForwardingCoroutineInsteadOfSpinningOnClosedSocket() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val tunnel = fixture.open()
            val forwarding = SshTunnel::class.java.getDeclaredField("forwarder").apply { isAccessible = true }.get(tunnel)
            val listener = forwarding.javaClass.getDeclaredField("runningThread").apply { isAccessible = true }.get(forwarding) as Thread
            val scope = SshTunnel::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(tunnel) as CoroutineScope
            val tasks = scope.coroutineContext.job.children.toList()
            try {
                tunnel.close()
                val bean = ManagementFactory.getThreadMXBean()
                val before = bean.getThreadCpuTime(listener.threadId())
                delay(200)
                val after = bean.getThreadCpuTime(listener.threadId())
                val cpuMillis = if (before >= 0 && after >= before) (after - before) / 1_000_000 else -1
                assertTrue("Forwarder must terminate after close; closed-socket loop consumed ${cpuMillis}ms CPU in 200ms; stack: ${listener.stackTrace.joinToString(" > ")}", tasks.all { it.isCompleted })
            } finally {
                // Baseline's closed socket loop ignores coroutine cancellation. Stop only that loop
                // so the isolated regression itself cannot leave a saturated shared worker behind.
                if (listener.stackTrace.any { it.className.endsWith("LocalPortForwarder") && it.methodName == "listen" }) listener.interrupt()
                withTimeout(5_000) { tasks.joinAll() }
                tunnel.close()
            }
        }
    }
}
