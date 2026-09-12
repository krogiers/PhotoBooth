# Deploying to the Raspberry Pi — build once, then just `java -jar`

End-to-end checklist: get one runnable jar onto the Pi, then have it start
itself on boot with no keyboard/monitor interaction.

## 1. Get the source onto the Pi — no `git` needed

Copy the project folder over (`scp`, `rsync`, a USB stick — whatever; no
GitHub access from the Pi required). Skip `build/`, `.gradle/` and
`events/*/data/` — they're regenerated:

```bash
rsync -av --exclude=build --exclude=.gradle --exclude='events/*/data' \
  /path/to/PhotoBooth/ pi@<pi-ip>:PhotoBooth/
```

Prerequisites on the Pi (one-time): JDK 17 — `kotlin { jvmToolchain(17) }` in
`build.gradle.kts` makes Gradle fetch one automatically if none is installed
— `cloudflared`, and for the real camera `rpicam-still` / `rpicam-vid`
(bundled with Raspberry Pi OS Bookworm). See [`camera-and-preview.md`](camera-and-preview.md)
and [`download-server-and-tunnel.md`](download-server-and-tunnel.md).

## 2. Build the runnable jar — **on the Pi**

```bash
cd ~/PhotoBooth
./gradlew shadowJar
```

This produces **one file**, `build/libs/photobooth.jar`, with every
dependency — including JavaFX — bundled in. This step needs to happen on the
Pi itself: JavaFX ships OS/architecture-specific native libraries
(`javafx-graphics-...-mac.jar` vs. `...-linux-aarch64.jar`), and the Gradle
plugin that manages them picks the one matching **whatever machine runs the
build** — there's no reliable way to tell it to target a different platform.
A jar built on your Mac will not run on the Pi; a jar built on the Pi will.

You only pay for this once. After this, `build/libs/photobooth.jar` is a
plain, self-contained file — copy it to another Pi of the same OS/architecture
and it runs there with no rebuild, no Gradle, no source tree.

## 3. Run it

```bash
java -jar build/libs/photobooth.jar
```

From a terminal **in the Pi's desktop session** (not over SSH — JavaFX needs
the local display). Confirm the self-view, the arcade button, and a full
shoot work before wiring up autostart, so "is it the app or the autostart
plumbing" stays a one-variable question if something's off.

## 4. Set up the real event

- Point `events/_active` at the event you want live (or set
  `PHOTOBOOTH_EVENT` in the systemd unit below), or edit
  `events/default/photobooth.properties` directly — see [`events.md`](events.md).
- Set a real `admin.password` if you want the admin panel reachable on the
  Pi's IP during the event.
- `gpio.button.pin` / `gpio.led.pin` match your wiring — [`gpio.md`](gpio.md).

## 5. Desktop autologin

```
sudo raspi-config
→ System Options → S5 Boot / Auto Login → B4 Desktop Autologin
```

The Pi now boots straight into a logged-in graphical session — no keyboard
needed.

## 6. Autostart the booth

**Recommended: a systemd `--user` service** — restarts the booth if it
crashes, and gives you `journalctl` logs.
[`packaging/pi/photobooth.service`](../packaging/pi/photobooth.service) is
ready to use as-is if the repo lives at `~/PhotoBooth`:

```bash
mkdir -p ~/.config/systemd/user
cp packaging/pi/photobooth.service ~/.config/systemd/user/
loginctl enable-linger "$USER"        # let the user's systemd instance run at boot
systemctl --user daemon-reload
systemctl --user enable --now photobooth.service
```

Check it came up: `systemctl --user status photobooth.service`. Logs:
`journalctl --user -u photobooth -f`.

**If the service never goes `active` after a reboot** (some Raspberry Pi OS
desktop sessions don't reliably reach `graphical-session.target`), fall back
to a plain XDG autostart entry instead — less robust (no auto-restart on
crash) but it only depends on the desktop starting *anything* on login, which
always works:

```bash
mkdir -p ~/.config/autostart
cat > ~/.config/autostart/photobooth.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=PhotoBooth
Exec=/usr/bin/java -jar /home/pi/PhotoBooth/build/libs/photobooth.jar
Terminal=false
EOF
```
(Adjust the path if the repo isn't at `/home/pi/PhotoBooth`.)

## 7. Reboot and verify

Power-cycle the Pi. It should: boot → autologin → the booth's loading screen
→ full-screen idle self-view, with no interaction. If it doesn't:

- `journalctl --user -u photobooth -e` (systemd variant) for the failure.
- Confirm step 3 (manual `java -jar`) still works — rules out an
  autostart-specific problem (missing `DISPLAY`, wrong working directory) vs.
  an app problem.
- A blank/black screen with the process running usually means `DISPLAY`
  wasn't set the same way as your interactive session — check
  `echo $DISPLAY` while logged into the desktop and match it in the service
  file if it's not `:0`.

## Updating

After changing code on the Pi: `./gradlew shadowJar` again, then
`systemctl --user restart photobooth.service` (or reboot). Only changed
Kotlin/resource code needs this — `events/*/photobooth.properties` changes
don't need a rebuild at all; see [`events.md`](events.md) for which keys
apply live via the admin panel vs. need a restart.
