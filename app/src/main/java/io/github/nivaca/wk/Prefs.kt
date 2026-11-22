// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of Wiktionary Reader. See the LICENSE file for details.

// Los ajustes de la app, en las preferencias compartidas de Android, con
// sus valores por omisión en un solo lugar; también las palabras recientes.

package io.github.nivaca.wk

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Languages to show first, as written by the user ("la, es"). */
    var languages: String
        get() = sp.getString("languages", "").orEmpty()
        set(v) = sp.edit().putString("languages", v.trim()).apply()

    /** The same, as the page script wants them: lower case, one per item. */
    val languageList: List<String>
        get() = languages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    var hideOthers: Boolean
        get() = sp.getBoolean("only", false)
        set(v) = sp.edit().putBoolean("only", v).apply()

    var quotations: Boolean
        get() = sp.getBoolean("quotations", true)
        set(v) = sp.edit().putBoolean("quotations", v).apply()

    var tables: Boolean
        get() = sp.getBoolean("tables", true)
        set(v) = sp.edit().putBoolean("tables", v).apply()

    /** Whether Translations start closed. */
    var closeTranslations: Boolean
        get() = sp.getBoolean("close_translations", true)
        set(v) = sp.edit().putBoolean("close_translations", v).apply()

    var suggestions: Boolean
        get() = sp.getBoolean("suggestions", true)
        set(v) = sp.edit().putBoolean("suggestions", v).apply()

    var textZoom: Int
        get() = sp.getInt("text_zoom", 100)
        set(v) = sp.edit().putInt("text_zoom", v).apply()

    /** The app's own theme (a UiModeManager.MODE_NIGHT_… value; AUTO follows the system). */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    // Palabras recientes: la más nueva primero, sin repetir, hasta RECENT_MAX.
    val recent: List<String>
        get() = sp.getString("recent", "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun addRecent(title: String) {
        val list = listOf(title) + recent.filter { it != title }
        sp.edit().putString("recent", list.take(RECENT_MAX).joinToString("\n")).apply()
    }

    fun clearRecent() = sp.edit().remove("recent").apply()

    companion object {
        const val RECENT_MAX = 30
        val TEXT_SIZES = intArrayOf(80, 90, 100, 115, 130, 150, 175)
    }
}
