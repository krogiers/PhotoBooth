package com.photobooth.util

import java.util.concurrent.Callable
import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonExecutorsTest {

    @Test
    fun `daemonExecutor runs tasks on a named daemon thread`() {
        val exec = daemonExecutor("test-worker")
        try {
            val (daemon, name) = exec.submit(
                Callable { Thread.currentThread().isDaemon to Thread.currentThread().name },
            ).get()
            assertTrue(daemon, "thread must be a daemon")
            assertTrue(name.startsWith("test-worker-"), "unexpected thread name: $name")
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun `daemonThreadFactory numbers threads incrementally`() {
        val factory = daemonThreadFactory("scan")
        val names = List(3) { factory.newThread { }.name }
        assertTrue(names == listOf("scan-1", "scan-2", "scan-3"), names.toString())
    }
}
