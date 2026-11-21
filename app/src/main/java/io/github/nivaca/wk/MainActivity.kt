// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of Wiktionary Reader. See the LICENSE file for details.

// La única pantalla de la app: la casilla de búsqueda, con sus
// sugerencias, y la entrada en un WebView.  Guarda el historial de las
// palabras consultadas para volver atrás.

package io.github.nivaca.wk

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var input: AutoCompleteTextView
    private lateinit var progress: ProgressBar
    private lateinit var suggestionsAdapter: SuggestionAdapter
    private lateinit var cache: PageCache
    private lateinit var pages: Pages

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val searcher = Executors.newSingleThreadExecutor()

    /** A word already seen, and how far down the page it was. */
    private data class Visit(val title: String, val scroll: Int)

    private val history = ArrayDeque<Visit>()
    private var current: String? = null
    private var loadSeq = 0          // only the last load is shown
    private var searchSeq = 0
    private var typing = true        // false while the app itself sets the text
    private var backCallback: OnBackInvokedCallback? = null

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cache = PageCache(cacheDir)
        pages = Pages(this)
        web = findViewById(R.id.web)
        input = findViewById(R.id.input)
        progress = findViewById(R.id.progress)

        // Desde Android 15 la ventana ocupa toda la pantalla: el contenido
        // se aparta de las barras del sistema y del teclado.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        setUpWebView()
        setUpInput()
        findViewById<ImageButton>(R.id.menu).setOnClickListener { showMenu(it) }

        val saved = savedInstanceState?.getStringArray(STATE_HISTORY)
        val scrolls = savedInstanceState?.getIntArray(STATE_SCROLLS)
        if (saved != null && scrolls != null) {
            saved.indices.forEach { history.addLast(Visit(saved[it], scrolls[it])) }
        }
        val title = savedInstanceState?.getString(STATE_CURRENT)
        if (title != null) {
            open(title, scroll = savedInstanceState.getInt(STATE_SCROLL), remember = false)
        } else if (!handleIntent(intent)) {
            web.loadDataWithBaseURL(Wiktionary.WIKI, pages.welcome(), "text/html", "utf-8", null)
            input.requestFocus()
        }
        updateBack()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT, current)
        outState.putInt(STATE_SCROLL, web.scrollY)
        outState.putStringArray(STATE_HISTORY, history.map { it.title }.toTypedArray())
        outState.putIntArray(STATE_SCROLLS, history.map { it.scroll }.toIntArray())
    }

    override fun onDestroy() {
        loader.shutdownNow()
        searcher.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    /** A word from another app (selected text, shared text or a link); false if none. */
    private fun handleIntent(intent: Intent?): Boolean {
        val word = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString?.let { url ->
                Wiktionary.entryTarget(url)?.let { open(it.title, it.language); return true }
                null
            }
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() && it.length < 200 } ?: return false
        open(word)
        return true
    }

    // ---- the page ---------------------------------------------------------- //

    private fun setUpWebView() {
        web.settings.apply {
            javaScriptEnabled = true          // only the app's own script runs (see Pages)
            domStorageEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = prefs.getInt(PREF_ZOOM, 100)
        }
        // Las páginas traen sus propios colores oscuros (wk.css y la paleta
        // del sitio), así que el WebView no las oscurece por su cuenta; el
        // fondo transparente deja ver el de la app.
        web.setBackgroundColor(Color.TRANSPARENT)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                followLink(request.url.toString())
                return true
            }
        }
    }

    private fun followLink(url: String) {
        if (url == "wk:retry") {
            current?.let { open(it, remember = false) }
            return
        }
        val target = Wiktionary.entryTarget(url)
        when {
            target == null -> openInBrowser(url)
            Wiktionary.isRedLink(url) -> toast(getString(R.string.no_entry_yet, target.title))
            target.title == current -> target.language?.let { showInPage(it) }
            else -> open(target.title, target.language)
        }
    }

    private fun showInPage(fragment: String) {
        web.evaluateJavascript("window.wk && wk.show(${org.json.JSONObject.quote(fragment)})", null)
    }

    /**
     * Shows the entry for [word], downloading it if needed; [language] (or
     * any anchor of the page) is where to go in it.  With [remember], the
     * word shown before goes into the history.
     */
    private fun open(word: String, language: String? = null, scroll: Int = 0, remember: Boolean = true) {
        val title = word.trim().replace('_', ' ')
        if (title.isEmpty()) return
        val before = current
        if (remember && before != null && before != title) {
            history.addLast(Visit(before, web.scrollY))
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
        current = title
        updateBack()
        setInput(title)
        hideKeyboard()

        val seq = ++loadSeq
        val config = PageConfig(
            languages = languagePrefs(),
            only = prefs.getBoolean(PREF_ONLY, false),
            target = language,
            scroll = scroll,
            allLabel = getString(R.string.all_languages),
        )
        val cached = cache.peek(title)
        if (cached != null) {
            show(pages.entry(cached, config))
            return
        }
        progress.visibility = View.VISIBLE
        loader.execute {
            val doc = try {
                pages.entry(cache.get(title), config)
            } catch (_: NotFound) {
                pages.missing(title, Wiktionary.suggestions(title))
            } catch (e: IOException) {
                pages.error(e.message ?: e.javaClass.simpleName)
            }
            main.post {
                if (seq == loadSeq && !isDestroyed) {
                    progress.visibility = View.GONE
                    show(doc)
                }
            }
        }
    }

    private fun show(doc: String) {
        web.loadDataWithBaseURL(Wiktionary.WIKI, doc, "text/html", "utf-8", null)
    }

    /** Shows the current word again, with the settings just changed. */
    private fun refresh() {
        current?.let { open(it, scroll = 0, remember = false) }
    }

    private fun languagePrefs(): List<String> =
        prefs.getString(PREF_LANGUAGES, "").orEmpty()
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    // ---- back -------------------------------------------------------------- //

    private fun goBack(): Boolean {
        val visit = history.removeLastOrNull() ?: return false
        open(visit.title, scroll = visit.scroll, remember = false)
        updateBack()
        return true
    }

    // Atrás vuelve a la palabra anterior mientras haya historial; sin él, el
    // sistema cierra la app.  Desde Android 13 eso se avisa por adelantado
    // (con el gesto predictivo), registrando o quitando el callback.
    private fun updateBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val want = history.isNotEmpty()
        val cb = backCallback
        if (want && cb == null) {
            val newCb = OnBackInvokedCallback { goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, newCb)
            backCallback = newCb
        } else if (!want && cb != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backCallback = null
        }
    }

    @Deprecated("Only used before Android 13")
    override fun onBackPressed() {
        if (!goBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- the search box ---------------------------------------------------- //

    private fun setUpInput() {
        suggestionsAdapter = SuggestionAdapter(this)
        input.setAdapter(suggestionsAdapter)
        input.threshold = 1
        input.setOnItemClickListener { _, _, position, _ ->
            suggestionsAdapter.getItem(position)?.let { open(it) }
        }
        input.setOnEditorActionListener { v, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                open(v.text.toString())
                true
            } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (typing) suggest(s?.toString().orEmpty())
            }
        })
    }

    // Las sugerencias se piden un cuarto de segundo después de la última
    // tecla, para no consultar el sitio con cada letra.
    private fun suggest(text: String) {
        val seq = ++searchSeq
        val query = text.trim()
        if (query.isEmpty()) {
            suggestionsAdapter.setItems(emptyList())
            return
        }
        main.postDelayed({
            if (seq != searchSeq) return@postDelayed
            searcher.execute {
                val found = try { Wiktionary.search(query, 10) } catch (_: Exception) { emptyList() }
                main.post {
                    if (seq == searchSeq && !isDestroyed) {
                        suggestionsAdapter.setItems(found)
                        if (found.isNotEmpty() && input.hasFocus()) input.showDropDown()
                    }
                }
            }
        }, 250)
    }

    private fun setInput(text: String) {
        typing = false
        input.setText(text, false)
        input.setSelection(text.length)
        typing = true
        searchSeq++                       // drop suggestions still on their way
        input.dismissDropDown()
    }

    private fun hideKeyboard() {
        input.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** An adapter that shows the titles as they come, without filtering them again. */
    private class SuggestionAdapter(context: Context) :
        ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, ArrayList()) {
        private var items: List<String> = emptyList()

        fun setItems(list: List<String>) {
            items = list
            clear()
            addAll(list)
            notifyDataSetChanged()
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = items; count = items.size }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }
    }

    // ---- the menu ---------------------------------------------------------- //

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main, popup.menu)
        val hasWord = current != null
        popup.menu.findItem(R.id.browser).isEnabled = hasWord
        popup.menu.findItem(R.id.share).isEnabled = hasWord
        popup.menu.findItem(R.id.reload).isEnabled = hasWord
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.browser -> current?.let { openInBrowser(Wiktionary.pageUrl(it)) }
                R.id.share -> current?.let { share(Wiktionary.pageUrl(it)) }
                R.id.reload -> current?.let { cache.forget(it); open(it, remember = false) }
                R.id.languages -> languagesDialog()
                R.id.text_size -> textSizeDialog()
                R.id.clear_cache -> toast(getString(R.string.cache_cleared, cache.clear()))
                R.id.about -> aboutDialog()
            }
            true
        }
        popup.show()
    }

    private fun languagesDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val help = TextView(this).apply { setText(R.string.languages_help) }
        val field = EditText(this).apply {
            setSingleLine()
            hint = "la, es, Ancient Greek"
            setText(prefs.getString(PREF_LANGUAGES, ""))
        }
        val only = CheckBox(this).apply {
            setText(R.string.languages_only)
            isChecked = prefs.getBoolean(PREF_ONLY, false)
        }
        box.addView(help)
        box.addView(field)
        box.addView(only)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_languages)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit()
                    .putString(PREF_LANGUAGES, field.text.toString().trim())
                    .putBoolean(PREF_ONLY, only.isChecked)
                    .apply()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun textSizeDialog() {
        val sizes = intArrayOf(80, 90, 100, 115, 130, 150, 175)
        val labels = sizes.map { "$it %" }.toTypedArray()
        val now = sizes.indexOf(web.settings.textZoom).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_text_size)
            .setSingleChoiceItems(labels, now) { dialog, which ->
                web.settings.textZoom = sizes[which]
                prefs.edit().putInt(PREF_ZOOM, sizes[which]).apply()
                dialog.dismiss()
            }
            .show()
    }

    private fun aboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME)
            .setMessage(R.string.about_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- other apps -------------------------------------------------------- //

    // La app misma abre los enlaces de en.wiktionary.org, así que, para que
    // el enlace no vuelva a ella, se pide expresamente un navegador.
    private fun openInBrowser(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        view.selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            toast(url)
        }
    }

    private fun share(url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MAX_HISTORY = 100
        private const val STATE_CURRENT = "current"
        private const val STATE_SCROLL = "scroll"
        private const val STATE_HISTORY = "history"
        private const val STATE_SCROLLS = "scrolls"
        private const val PREF_LANGUAGES = "languages"
        private const val PREF_ONLY = "only"
        private const val PREF_ZOOM = "text_zoom"
    }
}
