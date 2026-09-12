package com.photobooth.server.admin

import com.photobooth.i18n.Strings
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The Text page: edit any guest-facing string for a language, or add a
 * language. Only values that differ from the bundled default are written to
 * `<event>/i18n/<lang>.properties` (a blank field reverts a key to its
 * default). Editing the **active** language reloads the kiosk and repoints
 * the phone pages at the new wording; editing another language changes
 * nothing until `ui.language` is switched (a restart).
 */
internal fun Route.textAdminRoutes(ctx: AdminContext) {

    get("/text") {
        val lang = call.request.queryParameters["lang"]?.trim()?.ifBlank { null } ?: ctx.config.language
        call.respondText(renderText(ctx, lang), ContentType.Text.Html)
    }

    post("/text") {
        val params = call.receiveParameters()
        val lang = params["lang"]?.trim()?.ifBlank { null } ?: ctx.config.language
        val base = Strings.bundledDefaults(lang)
        val existing = PropsIo.read(ctx.i18nOverrideFile(lang))

        val override = LinkedHashMap<String, String>()
        for (key in (base.keys + existing.keys)) {
            val submitted = params["t.$key"] ?: continue
            val value = submitted.trim()
            val default = base[key]
            when {
                value.isEmpty() -> {}                        // revert to the bundled default
                value == default -> {}                       // same as default → no override needed
                else -> override[key] = value
            }
        }

        val file = ctx.i18nOverrideFile(lang)
        if (override.isEmpty()) file.delete() else PropsIo.write(file, override)
        ctx.refresh()

        val banner = if (lang == ctx.config.language) {
            ctx.reloadHook?.invoke()
            Banner("success", "Saved. Kiosk and phone text updated (${override.size} override${if (override.size == 1) "" else "s"}).")
        } else {
            Banner("info", "Saved ${override.size} override(s) for \"$lang\". It's not the active language — switch ui.language to it (a restart) to see it.")
        }
        call.respondText(renderText(ctx, lang, banner), ContentType.Text.Html)
    }

    post("/text/language") {
        val code = call.receiveParameters()["code"]?.trim()?.lowercase().orEmpty()
        if (!Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?").matches(code)) {
            call.respondText(
                renderText(ctx, ctx.config.language, Banner("error", "\"$code\" isn't a valid language code (e.g. de, fr, pt-br).")),
                ContentType.Text.Html,
            )
            return@post
        }
        val file = ctx.i18nOverrideFile(code)
        if (!file.isFile) PropsIo.write(file, emptyMap())
        call.respondRedirect("/text?lang=$code")
    }
}

private fun renderText(ctx: AdminContext, lang: String, banner: Banner? = null): String {
    val e = AdminPages::esc
    val base = Strings.bundledDefaults(lang)
    val override = PropsIo.read(ctx.i18nOverrideFile(lang))
    val keys = (base.keys + override.keys).toSortedSet()
    val codes = (ctx.languageCodes() + lang).distinct().sorted()

    val body = buildString {
        append("<h1>Text</h1>")
        append("<p class=\"sub\">Guest-facing strings for <code>").append(e(lang)).append("</code>")
        if (lang == ctx.config.language) append(" <span class=\"pill\">active language</span>")
        append(". A blank field reverts that key to the bundled default. Overrides go to <code>events/")
        append(e(ctx.activeBundle.id)).append("/i18n/").append(e(lang)).append(".properties</code>.</p>")

        banner?.let { append("<p class=\"banner ").append(it.kind).append("\">").append(e(it.text)).append("</p>") }

        // language switch + add
        append("<div class=\"toolbar\">")
        append("<form method=\"get\" action=\"/text\"><label>Language <select name=\"lang\" onchange=\"this.form.submit()\">")
        codes.forEach { c ->
            append("<option").append(if (c == lang) " selected" else "").append(">").append(e(c)).append("</option>")
        }
        append("</select></label></form>")
        append("<form method=\"post\" action=\"/text/language\"><label>Add language ")
        append("<input name=\"code\" placeholder=\"e.g. de\" pattern=\"[a-z]{2,3}(-[a-z0-9]{2,8})?\" required></label> ")
        append("<button type=\"submit\">Add</button></form>")
        append("</div>")

        append("<form method=\"post\" action=\"/text\">")
        append("<input type=\"hidden\" name=\"lang\" value=\"").append(e(lang)).append("\">")
        append("<table><thead><tr><th>Key</th><th>Text</th><th>Bundled default</th></tr></thead><tbody>")
        for (key in keys) {
            val effective = override[key] ?: base[key] ?: ""
            val isOverride = key in override
            append("<tr").append(if (isOverride) " class=\"is-override\"" else "").append(">")
            append("<td class=\"key\">").append(e(key)).append("</td>")
            append("<td><input type=\"text\" name=\"t.").append(e(key)).append("\" value=\"").append(e(effective)).append("\"></td>")
            append("<td class=\"val\">").append(e(base[key] ?: "—")).append("</td>")
            append("</tr>")
        }
        append("</tbody></table>")
        append("<div class=\"actions\"><button type=\"submit\">Save text</button></div>")
        append("</form>")
    }
    return AdminPages.page("Text", "/text", body)
}
