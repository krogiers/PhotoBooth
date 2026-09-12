package com.photobooth.server.admin

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * The LAN admin panel's HTTP server. Deliberately a **separate** Netty engine
 * on its own port ([AppConfig.adminPort][com.photobooth.config.AppConfig.adminPort]),
 * not a route group on the guest [com.photobooth.server.DownloadServer]: the
 * Cloudflare tunnel only ever fronts the guest port, so the admin panel is
 * reachable on the local network but never from the public internet.
 *
 * Every route sits behind HTTP Basic auth (`admin` + `admin.password`).
 */
internal class AdminServer(
    private val port: Int,
    private val password: String,
    private val ctx: AdminContext,
) {
    private val log = LoggerFactory.getLogger(AdminServer::class.java)
    private var server: NettyApplicationEngine? = null

    fun start() {
        server = embeddedServer(Netty, port = port) { adminModule(password, ctx) }.start(wait = false)
        log.info("Admin panel on http://<this-machine>:{}/ — LAN only, never via the tunnel (user 'admin')", port)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}

/** Basic-auth + the admin routes. Split out so the test host can mount the exact same thing. */
internal fun Application.adminModule(password: String, ctx: AdminContext) {
    install(Authentication) {
        basic("admin") {
            realm = "PhotoBooth admin"
            validate { creds ->
                if (creds.name == "admin" && constantTimeEquals(creds.password, password)) {
                    UserIdPrincipal(creds.name)
                } else null
            }
        }
    }
    routing {
        authenticate("admin") {
            adminRoutes(ctx)
            settingsRoutes(ctx)
            textAdminRoutes(ctx)
            decorAdminRoutes(ctx)
            overlayAdminRoutes(ctx)
        }
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
