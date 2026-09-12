# The pages a guest's phone opens

## The need

Everything a guest sees on their own phone — the layout picker, the "photo
on its way" wait page, the download page — should look good, and be
**rebrandable per event without a rebuild**. At a client's event the client
should see their own colours and logo, not a generic booth page.

## How it's built

The markup is in [Mustache](https://mustache.github.io/) templates, not
Kotlin strings, rendered by
[`PhonePages`](../src/main/kotlin/com/photobooth/server/PhonePages.kt) and
served by [`DownloadServer`](../src/main/kotlin/com/photobooth/server/DownloadServer.kt).

| Template | Used for |
| --- | --- |
| `photo.mustache` | `/p/{name}` — photo preview + Download button |
| `choose.mustache` | `/choose` — the layout picker grid + the **📸 Take the photo** button |
| `message.mustache` | `/shoot/wait` (photo on its way, with a `metaRefresh`), `/last` (no photo yet), error pages — one heading + optional emoji / subtext / link |

All three are self-contained HTML documents that link one shared stylesheet,
`/web/style.css`. There are no partials to chase — each page is one file.
The `/choose` → pick → **Take the photo** → auto-shown photo flow is described
in [`remote-start.md`](remote-start.md).

## Two layers (same idea as [i18n](i18n.md))

| Layer | Location | Purpose |
| --- | --- | --- |
| Bundled defaults | [`src/main/resources/web/`](../src/main/resources/web/) | Ship in the jar. The starting point. |
| Per-event override | `events/<id>/web/` (git-ignored) | Drop a file here and it replaces the bundled one. No rebuild. |

Templates and assets are re-read from disk on every request, so an edit
under `events/<id>/web/` shows on the next refresh — no restart.

## Rebranding for an event

Cheapest to most involved:

1. **`web.brand.name`** in `events/<id>/photobooth.properties` — a wordmark at the
   top of every page.
2. **`events/<id>/web/logo.png`** (or `.svg`/`.jpg`) — shown next to the wordmark.
3. **`events/<id>/web/style.css`** — replaces the bundled stylesheet. The bundled
   one defines everything through `:root` custom properties (`--accent`,
   `--bg`, `--surface`, `--fg`, `--radius`); copying just that block into
   your override and changing a few values is usually enough.
4. **`events/<id>/web/*.mustache`** — replace a whole page's markup if the layout
   itself needs to change. The model fields each template gets are listed at
   the top of `PhonePages` and visible in the bundled templates.
5. **`events/<id>/i18n/<lang>.properties`** — the wording (see [i18n.md](i18n.md)).

## Previewing

Two ways, both with sample data (no real photo or overlay needed):

- **Offline, no booth running:**

  ```bash
  ./gradlew renderPhonePages
  open build/phone-pages-preview/index.html
  ```

  Renders every page (styled, self-contained) to `build/phone-pages-preview/`.
  It reads `events/<id>/web/` overrides too, so: edit `events/<id>/web/style.css`,
  re-run, refresh.

- **On a phone, booth running:** open **`<tunnel-url>/preview`**. Same sample
  renders, served live. `/preview` only ever shows fake data, so it's
  harmless to leave reachable.
