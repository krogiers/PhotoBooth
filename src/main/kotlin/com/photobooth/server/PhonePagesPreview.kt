package com.photobooth.server

import java.io.File

/**
 * Renders each phone page with sample data to `build/phone-pages-preview/`,
 * fully styled and offline, so the look can be checked and tweaked in a
 * browser without running the whole booth (no JavaFX, camera or tunnel).
 *
 * ```
 * ./gradlew renderPhonePages
 * open build/phone-pages-preview/index.html
 * ```
 *
 * It reads `config/web/` overrides just like the running server does, so
 * this is the fast edit loop: change `config/web/style.css`, re-run, refresh.
 */
fun main() {
    val outDir = File("build/phone-pages-preview").apply { mkdirs() }
    val pages = PhonePages(brandName = "Sample Event")

    // The rendered HTML links /web/style.css (an absolute server path); rewrite it
    // to a sibling file and drop the stylesheet next to the pages so file:// works.
    pages.asset("style.css")?.let { File(outDir, "style.css").writeBytes(it.bytes) }
    fun localise(html: String) = html.replace("\"/web/style.css\"", "\"style.css\"")

    val sampleOverlays = listOf("Classic", "Party", "Flags & Bunting", "Sweden", "IKEA Style", "Dalarna")
        .mapIndexed { i, label ->
            mapOf("label" to label, "thumbUrl" to SAMPLE_IMAGE, "chooseUrl" to "#", "selected" to (i == 1))
        }

    val samples = linkedMapOf(
        "photo.html" to pages.render(
            "photo.mustache",
            mapOf(
                "title" to "Your photo",
                "photoUrl" to SAMPLE_IMAGE,
                "downloadUrl" to "#",
                "downloadLabel" to "⬇ Download",
                "againUrl" to "#",
                "againLabel" to "📸 Another photo",
            ),
        ),
        "choose.html" to pages.render(
            "choose.mustache",
            mapOf(
                "title" to "Choose your layout",
                "heading" to "Choose your layout, then take the photo",
                "overlays" to sampleOverlays,
                "canShoot" to true,
                "shootUrl" to "#",
                "shootLabel" to "📸 Take the photo",
            ),
        ),
        "wait.html" to pages.render(
            "message.mustache",
            mapOf("heading" to "Your photo is on its way…", "subtext" to "Look over at the booth!", "emoji" to "⏳"),
        ),
        "message.html" to pages.render(
            "message.mustache",
            mapOf("heading" to "No photo has been taken yet.", "emoji" to "📷"),
        ),
    )
    samples.forEach { (name, html) -> File(outDir, name).writeText(localise(html)) }

    File(outDir, "index.html").writeText(
        localise(
            pages.render(
                "choose.mustache",
                mapOf(
                    "title" to "Phone page preview",
                    "heading" to "Phone page preview",
                    "overlays" to samples.keys.map {
                        mapOf("label" to it.removeSuffix(".html"), "thumbUrl" to SAMPLE_IMAGE, "chooseUrl" to it)
                    },
                ),
            ),
        ),
    )

    println("Wrote ${samples.size + 1} pages to ${outDir.absolutePath}")
}
