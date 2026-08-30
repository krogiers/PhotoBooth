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

Three independent slots, all optional:

| Slot | Keys | Notes |
| --- | --- | --- |
| **Bunting** | `decor.bunting.file`, `decor.bunting.height` | Stretched across the top edge, full width. |
| **Graphic** | `decor.graphic.file`, `decor.graphic.position`, `decor.graphic.height` | One free-placed image, scaled proportionally to `height` px. |
| **Title** | `decor.title.text`, `decor.title.font`, `decor.title.size`, `decor.title.color`, `decor.title.position` | `font` is a `.ttf`/`.otf`; omit it for the default UI font. `color` is `#rrggbb`. |

`position` is one of `top-left`, `top-center`, `top-right`, `center`,
`bottom-left`, `bottom-center`, `bottom-right`.

## Assets

Put the files in `config/decor/` (git-ignored). Names are up to you — the
keys point at them. **Use transparent PNGs**: the idle screen shows the live
camera self-view behind the decoration, so a white-background crop shows up
as a white box.

- Export the bunting and the graphic as their own transparent PNGs from the
  invitation's source file (Canva / Illustrator / …), sized roughly to how
  large they'll appear (e.g. bunting ~1600 px wide, graphic ~600 px tall).
- Drop the title font in as `.ttf`/`.otf`.

## Example (`config/photobooth.properties`)

```properties
decor.bunting.file=config/decor/bunting.png
decor.bunting.height=150
decor.graphic.file=config/decor/sweden.png
decor.graphic.position=bottom-right
decor.graphic.height=280
decor.title.text=Tibo's uitzwaai feestje
decor.title.font=config/decor/title.ttf
decor.title.size=60
decor.title.color=#1f3b63
decor.title.position=top-center
```

## The message screens (`config/kiosk/kiosk.css`)

The "starting…" splash and the startup-error screen are plain JavaFX styled
by a stylesheet — the same override model as the phone pages' `config/web/`:

| Layer | Location |
| --- | --- |
| Bundled default | [`src/main/resources/kiosk/kiosk.css`](../src/main/resources/kiosk/kiosk.css) |
| Per-event override | `config/kiosk/kiosk.css` (git-ignored), layered on top |

Two style classes: `.kiosk-root` (the full-screen background) and
`.kiosk-message` (the centred text). JavaFX CSS — properties are `-fx-`
prefixed (`-fx-background-color`, `-fx-font-size`, `-fx-text-fill`, …), and
`@font-face { src: url("config/kiosk/yourfont.ttf"); }` works for a custom
font. Copy the two rules from the bundled file and change what you need; a
missing/empty `config/kiosk/kiosk.css` just leaves the defaults.
