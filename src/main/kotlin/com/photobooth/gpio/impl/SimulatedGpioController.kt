package com.photobooth.gpio.impl

import com.photobooth.gpio.Gpio
import org.slf4j.LoggerFactory

/**
 * Stand-in for [Pi4jGpioController] on a machine with no GPIO header (a
 * laptop during development, CI). There is no real arcade button to wire up,
 * so [onButtonPressed] never fires on its own — [BoothView][com.photobooth.ui.BoothView]'s
 * click-to-start already covers triggering a session by hand. LED state
 * changes are just logged so the countdown/"lighting ok" timing is still
 * visible while testing off the Pi.
 */
class SimulatedGpioController : Gpio {

    private val log = LoggerFactory.getLogger(SimulatedGpioController::class.java)

    override fun onButtonPressed(listener: () -> Unit) {
        log.info("No physical arcade button on this machine — click/tap the window to start a session instead.")
    }

    override fun setLed(on: Boolean) {
        log.info("LED -> {}", if (on) "ON" else "off")
    }

    override fun close() = Unit
}
