# Decorating the idle screen per event

## The need

Each event wants the kiosk's idle screen dressed to match its invitation — a
bunting garland, a landmark or mascot graphic, the party's name in its own
font. That has to be swappable per event **without a rebuild**, the same idea
as `config/web/` (phone pages) and `config/i18n/` (wording).

With nothing configured the idle screen looks exactly as before.

## How it works

[`DecorConfig`](../src/main/kotlin/com/photobooth/config/DecorConfig.kt) is
read from `config/photobooth.properties` at startup and handed to
[`BoothView`](../src/main/kotlin/com/photobooth/ui/BoothView.kt), which draws
a mouse-transparent decoration layer *behind* the QRs and "Tap to start", on
the **idle screen only** (it's hidden during the countdown and result
screens). A missing or unreadable file is logged and skipped — it never
crashes the kiosk.

It's a **numbered list of image layers** plus an optional text title.

### Image layers — `decor.<n>.*`

Number them `decor.1.*`, `decor.2.*`, … (a gap is fine); they're drawn
back-to-front.

| Key | Meaning |
| --- | --- |
| `decor.<n>.file` | path to the image, e.g. `config/decor/wimpels.png` |
| `decor.<n>.position` | `top-left` … `bottom-right` (9-way, see below) |
| `decor.<n>.width` | `NN` px, or `NN%` of the screen width |
| `decor.<n>.height` | `NN` px, or `NN%` of the screen height |
| `decor.<n>.rotate` | degrees clockwise about the centre (`-12` tilts up-left) |

Give a `width` **or** a `height` and the other scales proportionally; give
both to stretch; give neither for the image's natural size.

### Text title — `decor.title.*`

`decor.title.text`, `.font` (a `.ttf`/`.otf`, omit for the default UI font),
`.size`, `.color` (`#rrggbb`), `.position`. Leave `decor.title.text` blank to
use an image layer as the title instead. Its **drop shadow** (and anything
else CSS-expressible — rotation, opacity, a plate behind it) is the
`.decor-title` rule in `config/kiosk/kiosk.css`, not a `decor.*` key.

### Positions

`top-left`, `top-center`, `top-right`, `center-left`, `center`,
`center-right`, `bottom-left`, `bottom-center`, `bottom-right`.

## Where the QRs and "tap to start" sit

Each QR can be switched on/off, and all three are placed by `kiosk.*` keys
in `config/photobooth.properties` — same position vocabulary as above:

```properties
kiosk.qr.layout.enabled=true             # the "choose your layout" QR
kiosk.qr.layout.position=center-left
kiosk.qr.recovery.enabled=true           # the "missed your QR?" recovery QR
kiosk.qr.recovery.position=center-right
kiosk.taphint.position=bottom-center
```

(The layout QR also needs `overlay.selection.enabled` to have a page to
point at.) Their *styling* (font, colour, shadow) is `.qr-tile` /
`.qr-caption` / `.tap-hint` in `config/kiosk/kiosk.css`; these keys are only
whether/where they go.

## Assets

Put the files in `config/decor/` (git-ignored). Names are up to you — the
keys point at them. **Use transparent PNGs**: the idle screen shows the live
camera self-view behind the decoration, so a layer with an opaque or
gradient background shows up as a rectangle. Export each element as its own
cut-out PNG from the invitation's source file (Canva / Illustrator / …).

## Example (`config/photobooth.properties`)

```properties
# flags tilted into the top-left corner
decor.1.file=config/decor/wimpels.png
decor.1.position=top-left
decor.1.width=48%
decor.1.rotate=-10

# the party name where a title would go
decor.2.file=config/decor/uitzwaaifeest.png
decor.2.position=top-center
decor.2.width=42%

# a signpost, top-right; the recovery QR sits under it
decor.3.file=config/decor/wegwijzer.png
decor.3.position=top-right
decor.3.height=58%

kiosk.qr.recovery.position=bottom-right
```

## Styling every kiosk screen (`config/kiosk/kiosk.css`)

**Nothing visual is hard-coded in the kiosk anymore.** Every colour, font,
weight, size, shadow and background is a rule in a stylesheet — the same
override model as the phone pages' `config/web/`:

| Layer | Location |
| --- | --- |
| Bundled default | [`src/main/resources/kiosk/kiosk.css`](../src/main/resources/kiosk/kiosk.css) |
| Per-event override | `config/kiosk/kiosk.css` (git-ignored), layered on top |

JavaFX CSS — properties are `-fx-` prefixed (`-fx-background-color`,
`-fx-font-size`, `-fx-font-weight`, `-fx-text-fill`, `-fx-effect: dropshadow(...)`,
…). For a custom font: `@font-face { src: url("config/kiosk/yourfont.ttf"); }`
then `-fx-font-family: "Your Font";` on the classes you want. Copy the rules
you want to change from the bundled file; a missing/empty override just
leaves the defaults.

| Class | Element |
| --- | --- |
| `.kiosk-root` / `.kiosk-message` | the "starting…" splash and startup-error screen |
| `.countdown-number` / `.countdown-badge` | the big countdown digit / "Smile!" and the disc behind it |
| `.tap-hint` | "Tap to start" |
| `.qr-tile` / `.qr-caption` | an idle-screen QR block and its caption |
| `.decor-title` | shadow on the per-event decor title (font / colour / position stay `decor.*`) |
| `.result-scrim` / `.result-content` / `.result-caption` | the result screen's dark backdrop, layout box and "scan to download" line |

The only visual values *not* in CSS are three ImageView widths (the QR tiles
and the result photo/QR) — JavaFX can't size an `ImageView` from CSS. They're
named constants at the bottom of
[`BoothView.kt`](../src/main/kotlin/com/photobooth/ui/BoothView.kt).
