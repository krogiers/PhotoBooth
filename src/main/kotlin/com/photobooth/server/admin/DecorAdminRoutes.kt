package com.photobooth.server.admin

import com.photobooth.config.DecorPosition
import com.photobooth.config.DecorSize
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The Decor page: the numbered `decor.<n>.*` image layers on the idle screen
 * (the *text* title stays on the Settings page). Add a layer (upload a PNG +
 * position / size / rotation), edit a layer's placement, replace its image,
 * or delete it. Every save renumbers the block `1..M`, leaves the rest of
 * `photobooth.properties` (including `decor.title.*`) alone, and reloads the
 * kiosk — decor is a live layer.
 */
internal fun Route.decorAdminRoutes(ctx: AdminContext) {

    get("/decor") {
        call.respondText(renderDecor(ctx), ContentType.Text.Html)
    }

    get("/decor/thumb/{name}") {
        val file = call.parameters["name"]?.let(ctx::decorImageFile)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(file.readBytes(), ContentType.Image.PNG)
    }

    // Edit placement of the existing layers (+ delete). No uploads → plain form post.
    post("/decor") {
        val p = call.receiveParameters()
        val existing = DecorFile.read(ctx.activeBundle.propertiesFile)
        val errors = mutableListOf<String>()
        val layers = mutableListOf<DecorFile.Layer>()

        existing.forEachIndexed { i, layer ->
            if (p["del.$i"] != null) return@forEachIndexed
            validateLayer(
                file = layer.file,
                position = p["pos.$i"].orEmpty(),
                width = p["w.$i"].orEmpty(),
                height = p["h.$i"].orEmpty(),
                rotate = p["rot.$i"].orEmpty(),
                label = "Layer ${i + 1}",
                errors = errors,
            )?.let(layers::add)
        }

        if (errors.isNotEmpty()) {
            call.respondText(renderDecor(ctx, Banner("error", errors.joinToString("; "))), ContentType.Text.Html)
            return@post
        }
        val banner = applyLayers(ctx, layers, "Saved ${layers.size} decor layer${if (layers.size == 1) "" else "s"}.")
        call.respondText(renderDecor(ctx, banner), ContentType.Text.Html)
    }

    // Add one new layer — needs the image upload, so multipart.
    post("/decor/add") {
        val (fields, image) = readMultipart(call.receiveMultipart())
        val errors = mutableListOf<String>()

        if (image == null) {
            call.respondText(renderDecor(ctx, Banner("error", "Choose a PNG for the new layer.")), ContentType.Text.Html)
            return@post
        }
        val saved = ctx.saveDecorImage(image.first, image.second)
        if (saved is AdminContext.ImageSave.Rejected) {
            call.respondText(renderDecor(ctx, Banner("error", saved.reason + ".")), ContentType.Text.Html)
            return@post
        }
        val name = (saved as AdminContext.ImageSave.Saved).name

        val layers = DecorFile.read(ctx.activeBundle.propertiesFile).toMutableList()
        validateLayer(
            file = "decor/$name",
            position = fields["position"].orEmpty(),
            width = fields["width"].orEmpty(),
            height = fields["height"].orEmpty(),
            rotate = fields["rotate"].orEmpty(),
            label = "New layer",
            errors = errors,
        )?.let(layers::add)

        if (errors.isNotEmpty()) {
            call.respondText(renderDecor(ctx, Banner("error", errors.joinToString("; "))), ContentType.Text.Html)
            return@post
        }
        val banner = applyLayers(ctx, layers, "Added a decor layer ($name).")
        call.respondText(renderDecor(ctx, banner), ContentType.Text.Html)
    }

    // Replace one existing layer's image.
    post("/decor/{i}/image") {
        val i = call.parameters["i"]?.toIntOrNull()
        val layers = DecorFile.read(ctx.activeBundle.propertiesFile).toMutableList()
        if (i == null || i !in layers.indices) {
            call.respondText(renderDecor(ctx, Banner("info", "No such layer.")), ContentType.Text.Html)
            return@post
        }
        val (_, image) = readMultipart(call.receiveMultipart())
        if (image == null) {
            call.respondText(renderDecor(ctx, Banner("error", "Choose a PNG.")), ContentType.Text.Html)
            return@post
        }
        when (val saved = ctx.saveDecorImage(image.first, image.second)) {
            is AdminContext.ImageSave.Rejected ->
                call.respondText(renderDecor(ctx, Banner("error", saved.reason + ".")), ContentType.Text.Html)
            is AdminContext.ImageSave.Saved -> {
                layers[i] = layers[i].copy(file = "decor/${saved.name}")
                val banner = applyLayers(ctx, layers, "Layer ${i + 1} now uses ${saved.name}.")
                call.respondText(renderDecor(ctx, banner), ContentType.Text.Html)
            }
        }
    }
}

private fun applyLayers(ctx: AdminContext, layers: List<DecorFile.Layer>, message: String): Banner {
    DecorFile.write(ctx.activeBundle.propertiesFile, layers)
    ctx.refresh()
    ctx.reloadHook?.invoke()
    return Banner("success", "$message Kiosk reloaded.")
}

private fun validateLayer(
    file: String,
    position: String,
    width: String,
    height: String,
    rotate: String,
    label: String,
    errors: MutableList<String>,
): DecorFile.Layer? {
    var ok = true
    if (position.isNotBlank()) {
        runCatching { DecorPosition.parse(position) }.onFailure { errors += "$label: bad position"; ok = false }
    }
    if (width.isNotBlank()) {
        runCatching { DecorSize.parse(width) }.onFailure { errors += "$label: width must be a number or NN%"; ok = false }
    }
    if (height.isNotBlank()) {
        runCatching { DecorSize.parse(height) }.onFailure { errors += "$label: height must be a number or NN%"; ok = false }
    }
    if (rotate.isNotBlank() && rotate.toDoubleOrNull() == null) {
        errors += "$label: rotate must be a number"; ok = false
    }
    return if (ok) {
        DecorFile.Layer(file, position.trim().ifBlank { "center" }, width.trim(), height.trim(), rotate.trim())
    } else null
}

private suspend fun readMultipart(data: MultiPartData): Pair<Map<String, String>, Pair<String, ByteArray>?> {
    val fields = HashMap<String, String>()
    var image: Pair<String, ByteArray>? = null
    data.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> fields[part.name.orEmpty()] = part.value
            is PartData.FileItem -> {
                val bytes = part.streamProvider().use { it.readBytes() }
                if (bytes.isNotEmpty() && !part.originalFileName.isNullOrBlank()) {
                    image = (part.originalFileName ?: "decor.png") to bytes
                }
            }
            else -> {}
        }
        part.dispose()
    }
    return fields to image
}

private val POSITIONS = listOf(
    "top-left", "top-center", "top-right",
    "center-left", "center", "center-right",
    "bottom-left", "bottom-center", "bottom-right",
)

private fun renderDecor(ctx: AdminContext, banner: Banner? = null): String {
    val e = AdminPages::esc
    val layers = DecorFile.read(ctx.activeBundle.propertiesFile)

    fun posSelect(name: String, selected: String) = buildString {
        append("<select name=\"").append(name).append("\">")
        POSITIONS.forEach { p ->
            append("<option").append(if (p == selected) " selected" else "").append(">").append(p).append("</option>")
        }
        append("</select>")
    }

    val body = buildString {
        append("<h1>Decor layers</h1>")
        append("<p class=\"sub\">Images layered on the idle screen, behind the QRs. Use transparent PNGs. ")
        append("The <em>text</em> title is on the <a href=\"/settings\">Settings</a> page. Changes reload the kiosk.</p>")

        banner?.let {
            append("<p class=\"banner ").append(it.kind).append("\">").append(e(it.text)).append("</p>")
        }

        if (layers.isEmpty()) {
            append("<p class=\"note\">No decor layers yet.</p>")
        } else {
            append("<form method=\"post\" action=\"/decor\">")
            append("<table><thead><tr><th></th><th>Image</th><th>Position</th><th>Width</th><th>Height</th><th>Rotate</th><th>Delete</th></tr></thead><tbody>")
            layers.forEachIndexed { i, l ->
                val nm = l.file.substringAfterLast('/')
                append("<tr>")
                append("<td>").append(i + 1).append("</td>")
                append("<td><img class=\"decor-thumb\" src=\"/decor/thumb/").append(e(nm)).append("\" alt=\"\"><br><code>")
                append(e(l.file)).append("</code></td>")
                append("<td>").append(posSelect("pos.$i", l.position)).append("</td>")
                append("<td><input name=\"w.$i\" value=\"").append(e(l.width)).append("\" placeholder=\"— / 48%\" size=\"7\"></td>")
                append("<td><input name=\"h.$i\" value=\"").append(e(l.height)).append("\" placeholder=\"—\" size=\"7\"></td>")
                append("<td><input name=\"rot.$i\" value=\"").append(e(l.rotate)).append("\" placeholder=\"0\" size=\"5\"></td>")
                append("<td><input type=\"checkbox\" name=\"del.$i\"></td>")
                append("</tr>")
            }
            append("</tbody></table>")
            append("<div class=\"actions\"><button type=\"submit\">Save layer changes</button></div>")
            append("</form>")

            append("<h2 style=\"margin-top:26px\">Replace a layer's image</h2>")
            append("<div class=\"decor-replace\">")
            layers.forEachIndexed { i, _ ->
                append("<form method=\"post\" action=\"/decor/").append(i).append("/image\" enctype=\"multipart/form-data\">")
                append("<label>Layer ").append(i + 1).append(" ")
                append("<input type=\"file\" name=\"image\" accept=\"image/png\" required></label> ")
                append("<button type=\"submit\">Replace</button></form>")
            }
            append("</div>")
        }

        append("<h2 style=\"margin-top:26px\">Add a layer</h2>")
        append("<form method=\"post\" action=\"/decor/add\" enctype=\"multipart/form-data\" class=\"decor-add\">")
        append("<label>PNG <input type=\"file\" name=\"image\" accept=\"image/png\" required></label>")
        append("<label>Position ").append(posSelect("position", "center")).append("</label>")
        append("<label>Width <input name=\"width\" placeholder=\"— / 48%\" size=\"7\"></label>")
        append("<label>Height <input name=\"height\" placeholder=\"—\" size=\"7\"></label>")
        append("<label>Rotate <input name=\"rotate\" placeholder=\"0\" size=\"5\"></label>")
        append("<button type=\"submit\">Add layer</button>")
        append("</form>")
    }
    return AdminPages.page("Decor", "/decor", body)
}
