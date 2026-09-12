package com.photobooth.server.admin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * `GET /settings` renders a typed form for the scalar keys
 * ([SettingsSchema]); `POST /settings` validates it, writes only the real
 * deltas into `<event>/photobooth.properties` (leaving comments, key order
 * and the `decor.<n>.*` block alone — see [SettingsFile]), refreshes the
 * admin panel's view of config, and if any changed key is *live* fires the
 * booth-UI reload hook. Keys that need a full restart are named back to the
 * admin instead.
 */
internal fun Route.settingsRoutes(ctx: AdminContext) {

    get("/settings") {
        call.respondText(renderSettings(ctx), ContentType.Text.Html)
    }

    post("/settings") {
        val params = call.receiveParameters()
        val file = ctx.activeBundle.propertiesFile
        val onDisk = SettingsFile.read(file)

        val submitted = LinkedHashMap<String, String>()
        val errors = LinkedHashMap<String, String>()
        for (spec in SettingsSchema.SPECS) {
            val raw = params[spec.key]?.trim() ?: continue
            if (spec.secret && raw.isEmpty()) continue // keep the current secret
            submitted[spec.key] = raw
            spec.validate(raw)?.let { errors[spec.key] = it }
        }

        if (errors.isNotEmpty()) {
            call.respondText(
                renderSettings(ctx, values = submitted, errors = errors),
                ContentType.Text.Html, HttpStatusCode.OK,
            )
            return@post
        }

        val changes = submitted.filter { (k, v) ->
            (onDisk[k] ?: SettingsSchema.BY_KEY[k]?.default ?: "") != v
        }
        if (changes.isEmpty()) {
            call.respondText(renderSettings(ctx, banner = Banner("info", "No changes to save.")), ContentType.Text.Html)
            return@post
        }

        SettingsFile.write(file, changes)
        ctx.refresh()

        val live = changes.keys.filter { SettingsSchema.BY_KEY[it]?.live == true }.sorted()
        val restart = changes.keys.filter { SettingsSchema.BY_KEY[it]?.live != true }.sorted()
        if (live.isNotEmpty()) ctx.reloadHook?.invoke()

        val msg = buildString {
            append("Saved ").append(changes.size).append(if (changes.size == 1) " change. " else " changes. ")
            if (live.isNotEmpty()) {
                append("Applied to the kiosk now: ").append(live.joinToString(", ")).append(". ")
            }
            if (restart.isNotEmpty()) {
                append("Restart the booth to apply: ").append(restart.joinToString(", ")).append(".")
            }
        }
        call.respondText(renderSettings(ctx, banner = Banner("success", msg)), ContentType.Text.Html)
    }
}

internal data class Banner(val kind: String, val text: String)

private fun renderSettings(
    ctx: AdminContext,
    values: Map<String, String> = emptyMap(),
    errors: Map<String, String> = emptyMap(),
    banner: Banner? = null,
): String {
    val e = AdminPages::esc
    val onDisk = SettingsFile.read(ctx.activeBundle.propertiesFile)
    fun current(key: String): String = values[key] ?: onDisk[key] ?: ""

    val body = buildString {
        append("<h1>Settings</h1>")
        append("<p class=\"sub\"><code>events/").append(e(ctx.activeBundle.id))
        append("/photobooth.properties</code> — only the fields you change are written. ")
        append("<span class=\"pill\">live</span> takes effect on save; <span class=\"pill warn\">restart</span> needs a booth restart.</p>")

        banner?.let { append("<p class=\"banner ").append(it.kind).append("\">").append(e(it.text)).append("</p>") }

        append("<form method=\"post\" action=\"/settings\">")
        for (group in SettingsSchema.GROUPS) {
            append("<fieldset><legend>").append(e(group)).append("</legend>")
            for (spec in SettingsSchema.forGroup(group)) {
                val err = errors[spec.key]
                append("<div class=\"row").append(if (err != null) " has-error" else "").append("\">")
                append("<label for=\"f_").append(spec.key).append("\"><code>").append(e(spec.key)).append("</code> ")
                append(if (spec.live) "<span class=\"pill\">live</span>" else "<span class=\"pill warn\">restart</span>")
                append("</label>")
                append(control(spec, current(spec.key), e))
                if (spec.help.isNotEmpty()) append("<p class=\"help\">").append(e(spec.help)).append("</p>")
                if (err != null) append("<p class=\"err\">").append(e(err)).append("</p>")
                append("</div>")
            }
            append("</fieldset>")
        }
        append("<div class=\"actions\"><button type=\"submit\">Save changes</button></div>")
        append("</form>")

        val unknown = onDisk.keys.filter { it !in SettingsSchema.BY_KEY }
        if (unknown.isNotEmpty()) {
            append("<h2 style=\"margin-top:32px\">Also in this file (kept as-is)</h2>")
            append("<p class=\"sub\">Keys the form doesn't cover — decor layers and anything hand-added. Edited via the file or a later panel.</p>")
            append(AdminPages.table(listOf("Key", "Value"), unknown.map { listOf(it, onDisk.getValue(it)) }))
        }
    }
    return AdminPages.page("Settings", "/settings", body)
}

private fun control(spec: SettingSpec, value: String, e: (String) -> String): String {
    val id = "f_${spec.key}"
    val name = e(spec.key)
    return when (spec.type) {
        SettingType.BOOL -> {
            val on = value.equals("true", true)
            """<select id="$id" name="$name">
               <option value="true"${if (on) " selected" else ""}>true</option>
               <option value="false"${if (!on) " selected" else ""}>false</option>
               </select>"""
        }
        SettingType.ENUM -> buildString {
            append("<select id=\"").append(id).append("\" name=\"").append(name).append("\">")
            spec.enumValues.forEach { opt ->
                append("<option").append(if (opt.equals(value, true)) " selected" else "").append(">")
                append(e(opt)).append("</option>")
            }
            append("</select>")
        }
        SettingType.INT, SettingType.LONG ->
            """<input id="$id" name="$name" type="number" step="1" value="${e(value)}" placeholder="${e(spec.default)}">"""
        SettingType.STRING -> if (spec.secret)
            """<input id="$id" name="$name" type="password" value="" placeholder="leave blank to keep current" autocomplete="new-password">"""
        else
            """<input id="$id" name="$name" type="text" value="${e(value)}" placeholder="${e(spec.default.ifEmpty { "—" })}">"""
    }
}
