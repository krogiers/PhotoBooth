package com.photobooth.server

import com.photobooth.util.daemonExecutor
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exposes [DownloadServer] on the public internet via a Cloudflare "quick
 * tunnel" (`cloudflared tunnel --url ...`), so a guest's phone can fetch
 * their photo over its own mobile data — no shared network with the booth
 * needed at all. Requires `cloudflared` installed (`brew install cloudflared`
 * on macOS; see Cloudflare's docs for a Pi/arm64 install) and the machine
 * running the booth to have outbound internet access.
 *
 * Quick tunnels are anonymous (no Cloudflare account needed) and get a
 * random `https://<words>.trycloudflare.com` address each time they start —
 * fine for a one-day event, not meant as a permanent URL.
 */
class CloudflareTunnel(private val localPort: Int) : AutoCloseable {

    private val log = LoggerFactory.getLogger(CloudflareTunnel::class.java)
    private val urlFound = CountDownLatch(1)
    private val url = AtomicReference<String>()
    private val reader = daemonExecutor("cloudflared-reader")
    private var process: Process? = null

    /** Starts `cloudflared` and blocks until it reports its public URL, or throws after [timeoutSeconds]. */
    fun start(timeoutSeconds: Long = 20): String {
        val proc = ProcessBuilder("cloudflared", "tunnel", "--url", "http://localhost:$localPort")
            .redirectErrorStream(true)
            .start()
        process = proc

        reader.execute {
            proc.inputStream.bufferedReader().use { input ->
                input.lineSequence().forEach { line ->
                    log.debug("cloudflared: {}", line)
                    URL_PATTERN.find(line)?.let { match ->
                        if (url.compareAndSet(null, match.value)) urlFound.countDown()
                    }
                }
            }
        }

        if (!urlFound.await(timeoutSeconds, TimeUnit.SECONDS)) {
            stop()
            error(
                "cloudflared didn't report a public URL within ${timeoutSeconds}s — " +
                    "is it installed, and does this machine have internet access?"
            )
        }
        return url.get()
    }

    fun stop() {
        // Killing the process closes the stdout pipe, which ends the reader task.
        process?.let { proc ->
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        reader.shutdownNow()
    }

    override fun close() = stop()

    private companion object {
        val URL_PATTERN = Regex("""https://[a-zA-Z0-9-]+\.trycloudflare\.com""")
    }
}
