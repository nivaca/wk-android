// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of Wiktionary Reader. See the LICENSE file for details.

// Las páginas ya descargadas: unas pocas en memoria y, durante una semana,
// en la carpeta de caché de la app (que Android puede vaciar cuando le
// falte espacio), comprimidas con gzip.

package io.github.nivaca.wk

import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class PageCache(cacheDir: File, private val maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
    private val dir = File(cacheDir, "pages")
    private val memory = LruCache<String, String>(16)

    // Atributos que Parsoid deja para los editores y que la app no usa
    // (data-mw, about, id="mw…"): son casi un tercio de la página. typeof se
    // queda, porque las hojas de estilo del sitio lo usan con las imágenes.
    private val editorAttrs = Regex(
        """\s(?:data-mw|data-mw-[a-z-]+|about)=(?:"[^"]*"|'[^']*')|\sid="mw[\w-]{1,8}""""
    )

    private fun key(title: String) = Wiktionary.pageTitle(title)

    private fun file(key: String): File {
        val sha = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, sha.joinToString("") { "%02x".format(it) } + ".html.gz")
    }

    /**
     * The (trimmed) HTML of a page: from memory, from disk or downloaded.
     * Throws [NotFound] or an IOException.  Call it off the main thread.
     */
    @Synchronized
    fun get(title: String): String {
        val key = key(title)
        memory.get(key)?.let { return it }
        val html = readDisk(key) ?: Wiktionary.pageHtml(title).replace(editorAttrs, "").also {
            writeDisk(key, it)
        }
        memory.put(key, html)
        return html
    }

    /** Only what is already in memory (no disk, no network), or null. */
    fun peek(title: String): String? = memory.get(key(title))

    /** Drops a page, so that the next request downloads it again. */
    @Synchronized
    fun forget(title: String) {
        val key = key(title)
        memory.remove(key)
        file(key).delete()
    }

    /** Deletes every saved page; returns how many there were. */
    @Synchronized
    fun clear(): Int {
        memory.evictAll()
        return dir.listFiles()?.count { it.delete() } ?: 0
    }

    private fun readDisk(key: String): String? {
        val f = file(key)
        if (!f.isFile || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null
        return try {
            GZIPInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: IOException) {
            null
        }
    }

    private fun writeDisk(key: String, html: String) {
        try {
            dir.mkdirs()
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            GZIPOutputStream(tmp.outputStream()).use { it.write(html.toByteArray(Charsets.UTF_8)) }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (_: IOException) {
            // The disk cache is only an optimisation.
        }
    }
}
