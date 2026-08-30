package com.photobooth.gpio.impl

import com.photobooth.gpio.Gpio
import com.pi4j.Pi4J
import com.pi4j.context.Context
import com.pi4j.io.gpio.digital.DigitalInput
import com.pi4j.io.gpio.digital.DigitalOutput
import com.pi4j.io.gpio.digital.DigitalState
import com.pi4j.io.gpio.digital.DigitalStateChangeListener
import com.pi4j.io.gpio.digital.PullResistance

/**
 * Real GPIO access via Pi4J v2. Requires actually running on a Raspberry Pi
 * (or at least a Linux box with a compatible GPIO chip) — constructing this
 * anywhere else throws, so callers should fall back to
 * [SimulatedGpioController] when that happens. BCM pin numbering throughout.
 */
class Pi4jGpioController(
    buttonPin: Int,
    ledPin: Int,
) : Gpio {

    private val pi4j: Context = Pi4J.newAutoContext()

    private val button: DigitalInput = pi4j.create(
        DigitalInput.newConfigBuilder(pi4j)
            .id("start-button")
            .address(buttonPin)
            .pull(PullResistance.PULL_UP)
            .debounce(3000L)
    )

    private val led: DigitalOutput = pi4j.create(
        DigitalOutput.newConfigBuilder(pi4j)
            .id("ready-led")
            .address(ledPin)
            .shutdown(DigitalState.LOW)
            .initial(DigitalState.LOW)
    )

    /** Fires when the (normally-open, pulled-up) arcade button is pressed. */
    override fun onButtonPressed(listener: () -> Unit) {
        button.addListener(DigitalStateChangeListener { event ->
            if (event.state() == DigitalState.LOW) {
                listener()
            }
        })
    }

    override fun setLed(on: Boolean) {
        if (on) led.high() else led.low()
    }

    override fun close() {
        pi4j.shutdown()
    }
}
