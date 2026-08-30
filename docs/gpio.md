# GPIO: start button and "lighting ok" LED

## The need

Two pieces of physical hardware besides the camera and screen:

- an **arcade start button** that begins a shoot, and
- one or more **"lighting ok" LEDs** that come on for the capture so guests
  are lit predictably.

Both sit behind the [`Gpio`](../src/main/kotlin/com/photobooth/gpio/Gpio.kt)
interface (`onButtonPressed(listener)`, `setLed(on)`, `close()`) so the rest
of the app never touches Pi4J directly. The interface is the whole of the
`com.photobooth.gpio` package; the two implementations sit under
`com.photobooth.gpio.impl`.

## On the Pi — [`Pi4jGpioController`](../src/main/kotlin/com/photobooth/gpio/impl/Pi4jGpioController.kt)

Pi4J v2, BCM pin numbering throughout. Defaults: **17** for the button,
**27** for the LED (`gpio.button.pin` / `gpio.led.pin`).

- Context: `Pi4J.newAutoContext()`.
- Button: `DigitalInput.newConfigBuilder(pi4j)` with `PullResistance.PULL_UP`
  and `debounce(3000L)`. The button is normally-open and wired active-low,
  so a press reads `DigitalState.LOW`. The listener is attached
  with an explicit SAM constructor — `addListener(DigitalStateChangeListener { … })` —
  because a trailing lambda doesn't bind to its vararg parameter.
- LED: `DigitalOutput.newConfigBuilder(pi4j)` with `initial` and `shutdown`
  both `DigitalState.LOW`, so it's dark at startup and on exit.
- `close()` calls `pi4j.shutdown()`.

Wire the button between its pin and ground; wire each LED through a suitable
resistor/driver.

## Off the Pi — [`SimulatedGpioController`](../src/main/kotlin/com/photobooth/gpio/impl/SimulatedGpioController.kt)

Selected automatically when not on Linux, or if Pi4J fails to initialise.
There's no physical button, so `onButtonPressed` never fires — click or tap
the kiosk window to start a shoot instead (the same handler a touchscreen
uses). LED changes are logged so the countdown / "lighting ok" timing is
still visible while testing.

## Dependencies

`pi4j-core`, `pi4j-plugin-raspberrypi`, `pi4j-plugin-linuxfs` (see
[`build.gradle.kts`](../build.gradle.kts)). They're always on the classpath;
only the Linux code path actually loads them.
