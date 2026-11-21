// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of wk. See the LICENSE file for details.

// La conversación con en.wiktionary.org: las páginas en HTML (las de
// Parsoid, por la API REST), la búsqueda por prefijo y los enlaces.

package io.github.nivaca.wk

import android.net.Uri
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The requested page does not exist. */
class NotFound(title: String) : Exception(title)

/** (page title, language) of a link to an entry: "rosa#Latin" -> ("rosa", "Latin"). */
data class EntryTarget(val title: String, val language: String?)

object Wiktionary {
    const val BASE = "https://en.wiktionary.org"
    const val WIKI = "$BASE/wiki/"
    private const val HTML_URL = "$BASE/api/rest_v1/page/html/"
    private const val API_URL = "$BASE/w/api.php"
    private const val TIMEOUT_MS = 20_000
    private val USER_AGENT =
        "wk-android/${BuildConfig.VERSION_NAME} (Android Wiktionary client; HttpURLConnection)"

    // Espacios de nombres cuyas páginas son entradas, que la app muestra
    // ella misma; los demás (Appendix:, Wiktionary:, File: …) van al navegador.
    private val ENTRY_NAMESPACES = setOf("", "Reconstruction")

    fun pageTitle(word: String): String = word.trim().replace(' ', '_')

    /** URL-encodes [s], keeping "/" and ":" readable when [path] is true. */
    private fun encode(s: String, path: Boolean): String {
        val e = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return if (path) e.replace("%2F", "/").replace("%3A", ":") else e
    }

    fun pageUrl(word: String): String = WIKI + encode(pageTitle(word), path = true)

    private fun get(url: String, accept: String): ByteArray {
        var attempt = 0
        while (true) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", accept)
            try {
                val code = conn.responseCode
                when {
                    code == HttpURLConnection.HTTP_OK ->
                        return conn.inputStream.use { it.readBytes() }
                    code == HttpURLConnection.HTTP_NOT_FOUND ->
                        throw NotFound(url)
                    // Wikimedia pide paciencia con estos códigos: se espera
                    // lo que diga Retry-After (entre 1 y 10 s) y se reintenta.
                    code in setOf(429, 502, 503, 504) && attempt < 3 -> {
                        val delay = conn.getHeaderField("Retry-After")?.toDoubleOrNull()
                            ?: (1 shl attempt).toDouble()
                        Thread.sleep((delay.coerceIn(1.0, 10.0) * 1000).toLong())
                        attempt++
                    }
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** The Parsoid HTML of a page; throws [NotFound] or an IOException. */
    fun pageHtml(title: String): String {
        val url = HTML_URL + encode(pageTitle(title), path = false)
        return String(get(url, "text/html"), Charsets.UTF_8)
    }

    /** Page titles starting with [query] (the search box of the site). */
    fun search(query: String, limit: Int = 10): List<String> {
        val url = "$API_URL?action=opensearch&format=json&namespace=0" +
            "&limit=$limit&search=" + encode(query, path = false)
        val data = JSONArray(String(get(url, "application/json"), Charsets.UTF_8))
        val titles = data.optJSONArray(1) ?: return emptyList()
        return List(titles.length()) { titles.getString(it) }
    }

    /** Alternatives for a word that has no page; empty on network errors. */
    fun suggestions(word: String, limit: Int = 8): List<String> =
        try {
            search(word, limit).filter { it != word }
        } catch (_: IOException) {
            emptyList()
        } catch (_: NotFound) {
            emptyList()
        }

    /**
     * The entry a link points to, or null for any other link (to open in
     * the browser).  Links to pages that do not exist yet ("redlinks") give
     * an entry too, with [isRedLink] telling them apart.
     */
    fun entryTarget(url: String): EntryTarget? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (uri.host != "en.wiktionary.org" && uri.host != "en.m.wiktionary.org") return null
        val path = uri.path ?: return null
        if (!path.startsWith("/wiki/")) return null
        val query = uri.query
        if (query != null && !isRedLink(url)) return null
        val title = path.removePrefix("/wiki/").replace('_', ' ').trim()
        val namespace = if (':' in title) title.substringBefore(':') else ""
        if (title.isEmpty() || namespace !in ENTRY_NAMESPACES) return null
        // "#Latin", "#Etymology_2" o "#Latin:_flower": solo vale la lengua
        val language = uri.fragment?.replace('_', ' ')?.substringBefore(':')?.trim()
        return EntryTarget(title, language?.ifEmpty { null })
    }

    /** Whether [url] is a link to a page that does not exist yet. */
    fun isRedLink(url: String): Boolean =
        Uri.parse(url).getQueryParameter("redlink") == "1"
}
