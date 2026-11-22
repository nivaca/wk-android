// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of Wiktionary Reader. See the LICENSE file for details.

// Los documentos que muestra el WebView: la entrada de Wiktionary con la
// hoja de estilo y el script de la app dentro, y las páginas propias de
// la app (palabra sin entrada, error de red).

package io.github.nivaca.wk

import android.content.Context
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject

/** What the page script needs to know (see assets/wk.js). */
data class PageConfig(
    val languages: List<String>,
    val only: Boolean,
    val target: String?,
    val scroll: Int,
    val allLabel: String,
    val quotes: Boolean = true,
    val tables: Boolean = true,
    val closeTranslations: Boolean = true,
    val anchor: String? = null,
    val anchorOffset: Int = 0,
) {
    fun toJson(): String = JSONObject()
        .put("languages", JSONArray(languages))
        .put("only", only)
        .put("target", target ?: JSONObject.NULL)
        .put("scroll", scroll)
        .put("anchor", anchor ?: JSONObject.NULL)
        .put("anchorOffset", anchorOffset)
        .put("allLabel", allLabel)
        .put("quotes", quotes)
        .put("tables", tables)
        .put("collapsed", JSONArray(if (closeTranslations) listOf("translations") else emptyList()))
        .toString()
        // Inside a <script>, "</" could end it early.
        .replace("</", "<\\/")
}

class Pages(context: Context) {
    private val css = context.assets.open("wk.css").use { String(it.readBytes()) }
    private val js = context.assets.open("wk.js").use { String(it.readBytes()) }
    private val res = context.resources

    companion object {
        // Los colores de las tablas de Wiktionary (el gadget Palette), en
        // claro y en oscuro; el WebView guarda la hoja en su caché HTTP.
        private const val PALETTE_CSS =
            "${Wiktionary.BASE}/w/load.php?lang=en&modules=ext.gadget.Palette&only=styles&skin=vector"
        private const val THEME_CLASS = "skin-theme-clientpref-os"
    }

    private fun esc(s: String): String = TextUtils.htmlEncode(s)

    /** A Wiktionary page (Parsoid HTML) with the app's style and script in it. */
    fun entry(html: String, config: PageConfig): String {
        val head = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\">" +
            "<link rel=\"stylesheet\" href=\"$PALETTE_CSS\"><style>$css</style>"
        val tail = "<script>window.WK_CONFIG = ${config.toJson()};</script><script>$js</script>"
        // Con esta clase en <html>, la paleta sigue el tema del sistema
        // (claro u oscuro), como en el sitio con el tema "automático".
        val themed = if (html.contains("<html ")) html.replaceFirst("<html ", "<html class=\"$THEME_CLASS\" ")
                     else html
        return themed.insertBefore("</head>", head, atStart = true).insertBefore("</body>", tail, atStart = false)
    }

    /** The page for a word with no entry, with the titles that resemble it. */
    fun missing(word: String, suggestions: List<String>): String {
        val body = StringBuilder()
        body.append("<h1>").append(esc(res.getString(R.string.not_found, word))).append("</h1>")
        if (suggestions.isNotEmpty()) {
            body.append("<p>").append(esc(res.getString(R.string.did_you_mean))).append("</p><ul>")
            for (s in suggestions) {
                body.append("<li><a href=\"").append(esc(Wiktionary.pageUrl(s))).append("\">")
                    .append(esc(s)).append("</a></li>")
            }
            body.append("</ul>")
        }
        return simple(body.toString())
    }

    /** The page shown when Wiktionary could not be reached. */
    fun error(message: String): String = simple(
        "<h1>" + esc(res.getString(R.string.network_error)) + "</h1>" +
            "<p>" + esc(message) + "</p>" +
            "<p><a href=\"wk:retry\">" + esc(res.getString(R.string.retry)) + "</a></p>"
    )

    /** The page shown before the first search, with the words looked up lately. */
    fun welcome(recent: List<String>): String {
        val body = StringBuilder()
        body.append("<h1>").append(esc(res.getString(R.string.app_name))).append("</h1>")
        val (intro, note) = res.getString(R.string.welcome).split("\n\n", limit = 2)
            .let { it[0] to it.getOrNull(1) }
        body.append("<p>").append(esc(intro)).append("</p>")
        if (recent.isNotEmpty()) {
            body.append("<h2>").append(esc(res.getString(R.string.menu_recent))).append("</h2>")
            body.append("<ul class=\"wk-recent\">")
            for (w in recent.take(15)) {
                body.append("<li><a href=\"").append(esc(Wiktionary.pageUrl(w))).append("\">")
                    .append(esc(w)).append("</a></li>")
            }
            body.append("</ul>")
        }
        if (note != null) body.append("<p class=\"wk-note\">").append(esc(note)).append("</p>")
        return simple(body.toString())
    }

    private fun simple(body: String) =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\">" +
            "<style>$css</style></head><body><div class=\"wk-message\">$body</div></body></html>"

    // Inserta [text] antes de la etiqueta [tag]; si no la hay, al principio
    // o al final del documento.
    private fun String.insertBefore(tag: String, text: String, atStart: Boolean): String {
        val i = indexOf(tag, ignoreCase = true)
        return when {
            i >= 0 -> substring(0, i) + text + substring(i)
            atStart -> text + this
            else -> this + text
        }
    }
}
