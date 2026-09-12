package com.photobooth.server.admin

import com.photobooth.config.AppConfig
import com.photobooth.config.EventStore
import com.photobooth.i18n.Strings
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PNG_BYTES =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

class AdminServerTest {

    private fun newCtx(vararg extraLines: String): AdminContext {
        val root = Files.createTempDirectory("pb-events").toFile()
        val dir = File(root, "spring-fair").apply { mkdirs() }
        File(dir, "photobooth.properties").writeText(
            (listOf(
                "countdown.seconds=4",
                "admin.enabled=true",
                "admin.password=s3cret",
                "# a decor layer the form doesn't manage",
                "decor.1.file=decor/flags.png",
            ) + extraLines).joinToString("\n") + "\n",
        )
        File(dir, "event.properties").writeText("name=Spring Fair\ndate=2026-04-01\n")
        File(dir, "overlays").mkdirs()
        File(dir, "overlays/classic.png").writeText("not really a png")
        File(root, "_active").writeText("spring-fair\n")

        val store = EventStore(root, Files.createTempDirectory("pb-nolegacy").toFile())
        val bundle = store.active()
        val config = AppConfig.load(bundle)
        return AdminContext(store, bundle, config, Strings.load(config.language, bundle.i18nDir))
    }

    private fun basic(user: String = "admin", pass: String = "s3cret") =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    private fun props(ctx: AdminContext) = SettingsFile.read(ctx.activeBundle.propertiesFile)

    // --- auth ---------------------------------------------------------------

    @Test
    fun `no credentials is rejected`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status)
    }

    @Test
    fun `a wrong password is rejected`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        val res = client.get("/") { header(HttpHeaders.Authorization, basic(pass = "nope")) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    // --- read-only views --------------------------------------------------

    @Test
    fun `the right credentials see the dashboard for the active event`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        val res = client.get("/") { header(HttpHeaders.Authorization, basic()) }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue("Spring Fair" in body, body)
        assertTrue("spring-fair" in body)
    }

    @Test
    fun `the events page marks the active bundle`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        val body = client.get("/events") { header(HttpHeaders.Authorization, basic()) }.bodyAsText()
        assertTrue("spring-fair" in body && "active" in body, body)
    }

    @Test
    fun `an overlay thumbnail streams from the bundle, unknown 404s`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        assertEquals(
            HttpStatusCode.OK,
            client.get("/overlays/thumb/classic.png") { header(HttpHeaders.Authorization, basic()) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/overlays/thumb/nope.png") { header(HttpHeaders.Authorization, basic()) }.status,
        )
    }

    // --- settings form --------------------------------------------------

    @Test
    fun `the settings form renders typed fields and never prints the password`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        val body = client.get("/settings") { header(HttpHeaders.Authorization, basic()) }.bodyAsText()

        assertTrue("<form method=\"post\"" in body, body)
        assertTrue("countdown.seconds" in body && "server.port" in body)
        assertTrue(">live<" in body && ">restart<" in body, "badges")
        assertFalse("s3cret" in body, "the password value must never be rendered")
        // an unmanaged key is still shown as preserved
        assertTrue("decor.1.file" in body)
    }

    @Test
    fun `an invalid value is rejected and the file is left untouched`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        val res = client.post("/settings") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("countdown.seconds" to "soon").formUrlEncode())
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("must be a whole number" in res.bodyAsText())
        assertEquals("4", props(ctx)["countdown.seconds"], "unchanged on disk")
    }

    @Test
    fun `a live change is written and fires the reload hook`() = testApplication {
        val ctx = newCtx()
        var reloads = 0
        ctx.reloadHook = { reloads++ }
        application { adminModule("s3cret", ctx) }

        val body = client.post("/settings") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("countdown.seconds" to "6").formUrlEncode())
        }.bodyAsText()

        assertEquals("6", props(ctx)["countdown.seconds"])
        assertEquals(1, reloads)
        assertTrue("kiosk" in body.lowercase(), body)
        // the decor layer the form doesn't manage survived the rewrite
        assertEquals("decor/flags.png", props(ctx)["decor.1.file"])
    }

    @Test
    fun `a restart-only change is written but the reload hook is not called`() = testApplication {
        val ctx = newCtx()
        var reloads = 0
        ctx.reloadHook = { reloads++ }
        application { adminModule("s3cret", ctx) }

        val body = client.post("/settings") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("server.port" to "9090").formUrlEncode())
        }.bodyAsText()

        assertEquals("9090", props(ctx)["server.port"])
        assertEquals(0, reloads)
        assertTrue("Restart the booth" in body, body)
    }

    @Test
    fun `a blank secret field keeps the current password`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        client.post("/settings") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("admin.password" to "", "countdown.seconds" to "7").formUrlEncode())
        }

        assertEquals("s3cret", props(ctx)["admin.password"])
        assertEquals("7", props(ctx)["countdown.seconds"])
    }

    // --- overlays -----------------------------------------------------

    private fun upload(name: String, bytes: ByteArray) = MultiPartFormDataContent(
        formData {
            append(
                "files", bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                },
            )
        },
    )

    private fun overlayDir(ctx: AdminContext) = File(ctx.activeBundle.dir, "overlays")

    @Test
    fun `the overlays page shows the upload form and the gallery`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        val body = client.get("/overlays") { header(HttpHeaders.Authorization, basic()) }.bodyAsText()

        assertTrue("enctype=\"multipart/form-data\"" in body, body)
        assertTrue("classic.png" in body)
    }

    @Test
    fun `uploading a PNG writes it into the bundle overlays dir`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        val body = client.post("/overlays/upload") {
            header(HttpHeaders.Authorization, basic())
            setBody(upload("party time.png", PNG_BYTES))
        }.bodyAsText()

        assertTrue(File(overlayDir(ctx), "party time.png").isFile)
        assertTrue("Uploaded" in body, body)
    }

    @Test
    fun `a non-PNG upload is rejected and nothing is written`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        val body = client.post("/overlays/upload") {
            header(HttpHeaders.Authorization, basic())
            setBody(upload("fake.png", "GIF89a not a png".toByteArray()))
        }.bodyAsText()

        assertFalse(File(overlayDir(ctx), "fake.png").exists())
        assertTrue("not a PNG" in body, body)
    }

    @Test
    fun `an upload filename with path parts is sanitised into the overlays dir`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        client.post("/overlays/upload") {
            header(HttpHeaders.Authorization, basic())
            setBody(upload("../../etc/evil.png", PNG_BYTES))
        }

        assertTrue(File(overlayDir(ctx), "evil.png").isFile)
        assertFalse(File(ctx.activeBundle.dir.parentFile, "etc/evil.png").exists())
    }

    @Test
    fun `deleting an overlay removes the file`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        client.post("/overlays/delete") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("name" to "classic.png").formUrlEncode())
        }

        assertFalse(File(overlayDir(ctx), "classic.png").exists())
    }

    @Test
    fun `setting a default writes overlay_file and fires the reload hook`() = testApplication {
        val ctx = newCtx()
        var reloads = 0
        ctx.reloadHook = { reloads++ }
        application { adminModule("s3cret", ctx) }

        val body = client.post("/overlays/default") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("name" to "classic.png").formUrlEncode())
        }.bodyAsText()

        assertEquals("overlays/classic.png", props(ctx)["overlay.file"])
        assertEquals(1, reloads)
        assertEquals("classic.png", ctx.defaultOverlayName(), "config was refreshed")
        assertTrue("default overlay now" in body)
    }

    @Test
    fun `setting an unknown overlay as default is refused`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        client.post("/overlays/default") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("name" to "ghost.png").formUrlEncode())
        }

        assertNull(props(ctx)["overlay.file"])
    }

    // --- text editor -------------------------------------------------

    @Test
    fun `the text page has an editable form and a language switcher`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        val body = client.get("/text") { header(HttpHeaders.Authorization, basic()) }.bodyAsText()

        assertTrue("<form method=\"post\" action=\"/text\"" in body, body)
        assertTrue("name=\"t.starting\"" in body || "name=\"t.tap_to_start\"" in body, "a key field")
        assertTrue("/text/language" in body, "add-language form")
    }

    @Test
    fun `saving a changed string writes an override and fires the reload hook`() = testApplication {
        val ctx = newCtx() // active language is en (default)
        var reloads = 0
        ctx.reloadHook = { reloads++ }
        application { adminModule("s3cret", ctx) }

        val body = client.post("/text") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("lang" to "en", "t.starting" to "Warming up the booth…").formUrlEncode())
        }.bodyAsText()

        assertEquals("Warming up the booth…", PropsIo.read(ctx.i18nOverrideFile("en"))["starting"])
        assertEquals(1, reloads)
        assertTrue("updated" in body.lowercase(), body)
    }

    @Test
    fun `a value equal to the bundled default is not written as an override`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }
        val default = Strings.bundledDefaults("en").getValue("starting")

        client.post("/text") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("lang" to "en", "t.starting" to default).formUrlEncode())
        }

        assertFalse(ctx.i18nOverrideFile("en").exists(), "no override file for an unchanged value")
    }

    @Test
    fun `adding a language creates its override file`() = testApplication {
        val ctx = newCtx()
        application { adminModule("s3cret", ctx) }

        client.post("/text/language") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("code" to "de").formUrlEncode())
        }

        assertTrue(ctx.i18nOverrideFile("de").isFile)
    }

    // --- decor editor ---------------------------------------------

    @Test
    fun `the decor page shows the add-layer form`() = testApplication {
        application { adminModule("s3cret", newCtx()) }
        val body = client.get("/decor") { header(HttpHeaders.Authorization, basic()) }.bodyAsText()
        assertTrue("action=\"/decor/add\"" in body && "enctype=\"multipart/form-data\"" in body, body)
    }

    @Test
    fun `adding a decor layer writes a renumbered block and leaves the title alone`() = testApplication {
        val ctx = newCtx("decor.title.text=Tibo's feestje")
        var reloads = 0
        ctx.reloadHook = { reloads++ }
        application { adminModule("s3cret", ctx) }

        val form = MultiPartFormDataContent(
            formData {
                append("position", "top-left")
                append("width", "45%")
                append("rotate", "-10")
                append(
                    "image", PNG_BYTES,
                    Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"flags.png\"")
                    },
                )
            },
        )
        client.post("/decor/add") { header(HttpHeaders.Authorization, basic()); setBody(form) }

        val p = props(ctx)
        assertEquals("decor/flags.png", p["decor.1.file"])
        assertEquals("top-left", p["decor.1.position"])
        assertEquals("45%", p["decor.1.width"])
        assertEquals("-10", p["decor.1.rotate"])
        assertEquals("Tibo's feestje", p["decor.title.text"], "the text title is untouched")
        assertEquals(1, reloads)
        assertTrue(File(ctx.decorDir(), "flags.png").isFile)
    }

    @Test
    fun `editing decor layers deletes one and renumbers the rest`() = testApplication {
        val ctx = newCtx(
            "decor.1.file=decor/a.png", "decor.1.position=top-left",
            "decor.2.file=decor/b.png", "decor.2.position=center",
        )
        application { adminModule("s3cret", ctx) }

        client.post("/decor") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "del.0" to "on",
                    "pos.1" to "bottom-right", "w.1" to "", "h.1" to "", "rot.1" to "",
                ).formUrlEncode(),
            )
        }

        val p = props(ctx)
        assertEquals("decor/b.png", p["decor.1.file"])
        assertEquals("bottom-right", p["decor.1.position"])
        assertNull(p["decor.2.file"], "the deleted layer's slot is gone")
    }

    @Test
    fun `a bad decor width is rejected and the file is not rewritten`() = testApplication {
        val ctx = newCtx("decor.1.file=decor/a.png", "decor.1.position=top-left")
        application { adminModule("s3cret", ctx) }

        val body = client.post("/decor") {
            header(HttpHeaders.Authorization, basic())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("pos.0" to "top-left", "w.0" to "huge%", "h.0" to "", "rot.0" to "").formUrlEncode())
        }.bodyAsText()

        assertTrue("width must be a number" in body, body)
        assertNull(props(ctx)["decor.1.width"], "nothing written")
    }
}
