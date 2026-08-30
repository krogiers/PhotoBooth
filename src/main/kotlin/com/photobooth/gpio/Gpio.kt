package com.photobooth.gpio

/**
 * The two pieces of physical hardware besides the camera and screen: the
 * arcade start button (digital input) and the "lighting is OK" LED(s)
 * (digital output). [com.photobooth.gpio.impl.Pi4jGpioController] is the
 * real implementation for a Pi; [com.photobooth.gpio.impl.SimulatedGpioController]
 * stands in for it on a dev machine that has no GPIO header.
 */
interface Gpio : AutoCloseable {
    /** Registers a listener fired when the arcade button is pressed. */
    fun onButtonPressed(listener: () -> Unit)

    fun setLed(on: Boolean)
}
