package com.photobooth.server.admin

/**
 * Tiny server-rendered HTML for the admin panel — no template engine, no
 * external assets (the CSS is inlined), so it works on a bare LAN with no
 * internet. Read-only in this phase; later phases add forms that POST back.
 */
internal object AdminPages {

    private val NAV = listOf(
        "/" to "Dashboard",
        "/events" to "Events",
        "/settings" to "Settings",
        "/text" to "Text",
        "/decor" to "Decor",
        "/overlays" to "Overlays",
    )

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun page(title: String, activePath: String, body: String): String {
        val nav = NAV.joinToString("") { (path, label) ->
            val cls = if (path == activePath) " class=\"active\"" else ""
            "<a href=\"${esc(path)}\"$cls>${esc(label)}</a>"
        }
        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} · PhotoBooth admin</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font: 15px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
         background: #f5f5f7; color: #1d1d1f; }
  @media (prefers-color-scheme: dark) { body { background: #16161a; color: #e8e8ea; } }
  header { background: #1d1d1f; color: #fff; padding: 0 20px; display: flex; align-items: center; gap: 22px; flex-wrap: wrap; }
  header .brand { font-weight: 700; padding: 14px 0; }
  header nav { display: flex; gap: 4px; flex-wrap: wrap; }
  header nav a { color: #c7c7cc; text-decoration: none; padding: 14px 12px; font-size: 14px; }
  header nav a:hover { color: #fff; }
  header nav a.active { color: #fff; box-shadow: inset 0 -3px 0 #4a9eff; }
  main { max-width: 960px; margin: 0 auto; padding: 24px 20px 60px; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  p.sub { margin: 0 0 24px; color: #86868b; }
  table { border-collapse: collapse; width: 100%; background: #fff; border-radius: 10px; overflow: hidden; }
  @media (prefers-color-scheme: dark) { table { background: #1f1f24; } }
  th, td { text-align: left; padding: 9px 14px; border-bottom: 1px solid rgba(128,128,128,.18); vertical-align: top; }
  th { font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: #86868b; }
  tr:last-child td { border-bottom: 0; }
  td.key { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; white-space: nowrap; }
  td.val { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-word; }
  .cards { display: grid; gap: 14px; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
  .card { background: #fff; border-radius: 10px; padding: 16px; }
  @media (prefers-color-scheme: dark) { .card { background: #1f1f24; } }
  .card h2 { font-size: 13px; text-transform: uppercase; letter-spacing: .04em; color: #86868b; margin: 0 0 6px; }
  .card .big { font-size: 20px; font-weight: 600; }
  .pill { display: inline-block; font-size: 12px; padding: 2px 8px; border-radius: 999px; background: #e6f0ff; color: #0a58ca; }
  @media (prefers-color-scheme: dark) { .pill { background: #103057; color: #7fb5ff; } }
  .grid-overlays { display: grid; gap: 14px; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); }
  .grid-overlays figure { margin: 0; background: #fff; border-radius: 10px; padding: 10px; text-align: center; }
  @media (prefers-color-scheme: dark) { .grid-overlays figure { background: #1f1f24; } }
  .grid-overlays img { max-width: 100%; height: 110px; object-fit: contain;
                       background: repeating-conic-gradient(#0000 0 25%, #8883 0 50%) 0 0 / 16px 16px; }
  .grid-overlays figcaption { font-size: 12px; margin-top: 8px; word-break: break-all; }
  .note { background: #fff8e1; border: 1px solid #ffe08a; border-radius: 8px; padding: 10px 14px; font-size: 14px; margin: 0 0 20px; }
  @media (prefers-color-scheme: dark) { .note { background: #2a2410; border-color: #5a4a12; } }
  .pill.warn { background: #ffe3d6; color: #b23c17; }
  @media (prefers-color-scheme: dark) { .pill.warn { background: #4a2416; color: #ff9d76; } }
  .banner { border-radius: 8px; padding: 11px 15px; font-size: 14px; margin: 0 0 22px; }
  .banner.success { background: #e5f6e8; border: 1px solid #9ad9a6; color: #1c6b2c; }
  .banner.info { background: #eef1f5; border: 1px solid #c8ccd4; }
  .banner.error { background: #fdecea; border: 1px solid #f1b0ab; color: #a3271f; }
  @media (prefers-color-scheme: dark) {
    .banner.success { background: #143020; border-color: #2f6f42; color: #86e0a0; }
    .banner.info { background: #23232a; border-color: #3a3a44; }
    .banner.error { background: #331714; border-color: #7a2f28; color: #ff9a90; }
  }
  .toolbar { display: flex; gap: 22px; flex-wrap: wrap; align-items: end; margin: 0 0 20px; }
  .toolbar form { display: flex; gap: 8px; align-items: end; margin: 0; }
  .toolbar label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: #86868b; }
  .toolbar select, .toolbar input { padding: 7px 9px; font: inherit; border: 1px solid rgba(128,128,128,.4);
    border-radius: 7px; background: #fff; color: inherit; }
  @media (prefers-color-scheme: dark) { .toolbar select, .toolbar input { background: #17171b; } }
  .toolbar button { font: inherit; padding: 8px 14px; border: 0; border-radius: 7px; background: #0a58ca; color: #fff; cursor: pointer; }
  table input[type=text] { width: 100%; padding: 6px 8px; font: inherit; border: 1px solid rgba(128,128,128,.35);
    border-radius: 6px; background: #fff; color: inherit; }
  @media (prefers-color-scheme: dark) { table input[type=text] { background: #17171b; } }
  tr.is-override td.key { font-weight: 700; }
  tr.is-override td.key::after { content: " ·"; color: #0a58ca; }
  table input:not([type]), table input[type=text] { max-width: 100%; }
  .decor-thumb { width: 90px; height: 60px; object-fit: contain;
    background: repeating-conic-gradient(#0000 0 25%, #8883 0 50%) 0 0 / 12px 12px; }
  .decor-add, .decor-replace form { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; margin: 0 0 10px; }
  .decor-add label, .decor-replace label { display: flex; flex-direction: column; gap: 3px; font-size: 13px; color: #86868b; }
  .decor-add input, .decor-add select, .decor-replace input { padding: 6px 8px; font: inherit;
    border: 1px solid rgba(128,128,128,.4); border-radius: 6px; background: #fff; color: inherit; }
  @media (prefers-color-scheme: dark) { .decor-add input, .decor-add select, .decor-replace input { background: #17171b; } }
  .decor-add button, .decor-replace button { font: inherit; padding: 8px 16px; border: 0; border-radius: 7px;
    background: #0a58ca; color: #fff; cursor: pointer; }
  form.upload { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 0 0 22px; }
  .tile-actions { display: flex; gap: 6px; justify-content: center; margin-top: 8px; }
  .tile-actions form { margin: 0; }
  .tile-actions button { font: inherit; font-size: 12px; padding: 5px 10px; border: 1px solid rgba(128,128,128,.4);
    border-radius: 6px; background: #fff; color: inherit; cursor: pointer; }
  @media (prefers-color-scheme: dark) { .tile-actions button { background: #17171b; } }
  .tile-actions button.danger { color: #c0362c; border-color: #e0a49e; }
  form fieldset { border: 1px solid rgba(128,128,128,.25); border-radius: 10px; margin: 0 0 18px; padding: 8px 16px 4px; }
  form legend { font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: #86868b; padding: 0 6px; }
  form .row { padding: 12px 0; border-bottom: 1px solid rgba(128,128,128,.14); }
  form fieldset .row:last-child { border-bottom: 0; }
  form label { display: block; margin-bottom: 6px; }
  form label code { font-size: 13px; }
  form input, form select { width: 100%; max-width: 420px; padding: 7px 9px; font: inherit;
    border: 1px solid rgba(128,128,128,.4); border-radius: 7px; background: #fff; color: inherit; }
  @media (prefers-color-scheme: dark) { form input, form select { background: #17171b; } }
  form .row.has-error input, form .row.has-error select { border-color: #d9534f; }
  form .help { color: #86868b; font-size: 13px; margin: 6px 0 0; }
  form .err { color: #d9534f; font-size: 13px; margin: 6px 0 0; }
  form .actions { position: sticky; bottom: 0; padding: 14px 0; background: linear-gradient(transparent, #f5f5f7 40%); }
  @media (prefers-color-scheme: dark) { form .actions { background: linear-gradient(transparent, #16161a 40%); } }
  form button { font: inherit; font-weight: 600; padding: 10px 22px; border: 0; border-radius: 8px;
    background: #0a58ca; color: #fff; cursor: pointer; }
  form button:hover { background: #0949a8; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
</head>
<body>
<header>
  <span class="brand">PhotoBooth admin</span>
  <nav>$nav</nav>
</header>
<main>
$body
</main>
</body>
</html>"""
    }

    fun table(headers: List<String>, rows: List<List<String>>, rawCols: Set<Int> = setOf(1)): String = buildString {
        append("<table><thead><tr>")
        headers.forEach { append("<th>").append(esc(it)).append("</th>") }
        append("</tr></thead><tbody>")
        rows.forEach { row ->
            append("<tr>")
            row.forEachIndexed { i, cell ->
                val cls = when (i) { 0 -> " class=\"key\""; in rawCols -> " class=\"val\""; else -> "" }
                append("<td").append(cls).append(">").append(esc(cell)).append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table>")
    }
}
