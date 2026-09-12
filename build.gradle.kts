plugins {
    kotlin("jvm") version "1.9.24"
    id("org.openjfx.javafxplugin") version "0.1.0"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.photobooth"
version = "0.1.0"

repositories {
    mavenCentral()
}

val pi4jVersion = "2.6.0"
val ktorVersion = "2.3.12"
val zxingVersion = "3.5.3"

dependencies {
    // GPIO: arcade start button (input) + "ready to shoot" LEDs (output)
    implementation("com.pi4j:pi4j-core:$pi4jVersion")
    implementation("com.pi4j:pi4j-plugin-raspberrypi:$pi4jVersion")
    implementation("com.pi4j:pi4j-plugin-linuxfs:$pi4jVersion")

    // Embedded HTTP server so the QR code has something to point at
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    // Basic-auth for the LAN admin panel (separate port, never tunnelled)
    implementation("io.ktor:ktor-server-auth:$ktorVersion")

    // Logic-less templates for the phone-facing pages, so their markup lives in
    // editable .mustache files (bundled defaults + per-event config/web/ overrides)
    // instead of Kotlin string literals.
    implementation("com.github.spullara.mustache.java:compiler:0.9.14")

    // QR code generation for the download link
    implementation("com.google.zxing:core:$zxingVersion")
    implementation("com.google.zxing:javase:$zxingVersion")

    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

javafx {
    version = "21.0.3"
    modules = listOf("javafx.controls", "javafx.graphics")
}

application {
    mainClass.set("com.photobooth.MainKt")
    // Give the process a real name + a visible Dock / ⌘-Tab entry on macOS, so it's
    // obvious the booth is running (an undecorated kiosk window is otherwise easy to
    // miss). The -Xdock: flags are simply ignored on Linux/Windows.
    applicationDefaultJvmArgs = listOf(
        "-Xdock:name=PhotoBooth",
        "-Dapple.awt.application.name=PhotoBooth",
        "-Dapple.awt.UIElement=false",
    )
}

tasks.test {
    useJUnitPlatform()
}

// Two ways to run without touching events/_active:
//   ./gradlew run            → the active event, full-screen (the real booth / Pi)
//   ./gradlew run -Pdev      → the events/dev bundle, windowed (Mac dev)
//   ./gradlew run -Pevent=x  → the events/x bundle
//   ./gradlew run -Pwindowed → the active event, but windowed
tasks.named<JavaExec>("run") {
    if (project.hasProperty("dev")) {
        environment("PHOTOBOOTH_EVENT", "dev")
        environment("PHOTOBOOTH_WINDOWED", "1")
    }
    (project.findProperty("event") as String?)?.let { environment("PHOTOBOOTH_EVENT", it) }
    if (project.hasProperty("windowed")) environment("PHOTOBOOTH_WINDOWED", "1")
}

// Render the phone-facing pages with sample data to build/phone-pages-preview/
// so their styling can be checked in a browser without running the booth.
tasks.register<JavaExec>("renderPhonePages") {
    group = "photobooth"
    description = "Render the phone pages (sample data) to build/phone-pages-preview/ for previewing the styling."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.photobooth.server.PhonePagesPreviewKt")
}

// Copy the bundled overridable resources (i18n, phone-page templates, kiosk CSS) into the
// default event bundle as an editable starting point. Existing files are left untouched.
tasks.register("seedConfig") {
    group = "photobooth"
    description = "Write the bundled i18n / web / kiosk defaults into events/default/ so they can be edited without a rebuild."
    val projectDir = layout.projectDirectory
    doLast {
        val event = "events/default"
        val seeds = listOf(
            "src/main/resources/i18n/en.properties" to "$event/i18n/en.properties",
            "src/main/resources/i18n/nl.properties" to "$event/i18n/nl.properties",
            "src/main/resources/web/photo.mustache" to "$event/web/photo.mustache",
            "src/main/resources/web/choose.mustache" to "$event/web/choose.mustache",
            "src/main/resources/web/message.mustache" to "$event/web/message.mustache",
            "src/main/resources/web/style.css" to "$event/web/style.css",
            "src/main/resources/kiosk/kiosk.css" to "$event/kiosk/kiosk.css",
        )
        var written = 0
        seeds.forEach { (from, to) ->
            val dst = projectDir.file(to).asFile
            if (dst.exists()) {
                logger.lifecycle("  keep  $to (already there)")
            } else {
                dst.parentFile.mkdirs()
                projectDir.file(from).asFile.copyTo(dst)
                logger.lifecycle("  seed  $to")
                written++
            }
        }
        logger.lifecycle("seedConfig: $written written, ${seeds.size - written} left as-is. Edit these, then restart the app — no rebuild.")
    }
}

kotlin {
    jvmToolchain(17)
}

// A single self-contained jar — every dependency (including the platform's
// JavaFX natives) bundled in, Main-Class set from `application.mainClass`.
// `java -jar build/libs/photobooth.jar` runs it directly, no classpath/module
// flags needed.
//
// JavaFX's natives are resolved for whatever machine runs `shadowJar` — the
// org.openjfx Gradle plugin picks them by the *build* machine's OS/arch and
// (as of this plugin version) can't be told to target a different one, so
// this must be run ON the Pi to get a Pi-runnable jar. Once built there,
// that one jar file is what you copy around (to another Pi, as a backup,
// …) — see docs/pi-deployment.md.
tasks.shadowJar {
    archiveBaseName.set("photobooth")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
