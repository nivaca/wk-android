// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Nicolas Vaughan <nicolasvaughan@proton.me>
// This file is part of Wiktionary Reader. See the LICENSE file for details.

// La única pantalla de la app: la casilla de búsqueda, con sus
// sugerencias; la entrada en un WebView; abajo, atrás, adelante, el índice
// y la búsqueda en la página.  Guarda el historial en las dos direcciones
// y las palabras recientes.

package io.github.nivaca.wk

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var input: AutoCompleteTextView
    private lateinit var progress: ProgressBar
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var bottomBar: View
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var suggestionsAdapter: SuggestionAdapter
    private lateinit var cache: PageCache
    private lateinit var pages: Pages
    private lateinit var prefs: Prefs

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val searcher = Executors.newSingleThreadExecutor()

    /**
     * A word already seen, and where the reading was: the last heading above
     * the top of the screen and the distance to it (see wk.js), with the
     * scroll in pixels for pages without headings.
     */
    private data class Visit(val title: String, val scroll: Int, val anchor: String? = null, val offset: Int = 0)

    // El punto de lectura de la página que se ve, que el script avisa al
    // desplazarse (desde el hilo del WebView, de ahí @Volatile).
    @Volatile private var anchor: String? = null
    @Volatile private var anchorOffset = 0

    /** What the page script may call: only to report the reading point. */
    inner class PageBridge {
        @JavascriptInterface
        fun anchor(id: String, offset: Int) {
            anchor = id.takeIf { it.isNotEmpty() && it.length < 8 && it.all(Char::isDigit) }
            anchorOffset = offset
        }
    }

    private fun here(title: String) = Visit(title, web.scrollY, anchor, anchorOffset)

    // Como en un navegador: lo de atrás y lo de adelante.  Abrir una palabra
    // nueva vacía lo de adelante.
    private val backStack = ArrayDeque<Visit>()
    private val forwardStack = ArrayDeque<Visit>()
    private var current: String? = null      // null: the start page
    private var loadSeq = 0                  // only the last load is shown
    private var searchSeq = 0
    private var typing = true                // false while the app itself sets the text
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        cache = PageCache(cacheDir)
        pages = Pages(this)
        web = findViewById(R.id.web)
        input = findViewById(R.id.input)
        progress = findViewById(R.id.progress)
        backButton = findViewById(R.id.back)
        forwardButton = findViewById(R.id.forward)
        bottomBar = findViewById(R.id.bottom_bar)
        findBar = findViewById(R.id.find_bar)
        findInput = findViewById(R.id.find_input)
        findCount = findViewById(R.id.find_count)

        // Desde Android 15 la ventana ocupa toda la pantalla: el contenido
        // se aparta de las barras del sistema y del teclado.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        setUpWebView()
        setUpInput()
        setUpBars()
        findViewById<ImageButton>(R.id.menu).setOnClickListener { showMenu(it) }

        if (savedInstanceState != null) restoreState(savedInstanceState)
        val title = savedInstanceState?.getString(STATE_CURRENT)
        if (title != null) {
            open(Visit(title, savedInstanceState.getInt(STATE_SCROLL), savedInstanceState.getString(STATE_ANCHOR),
                       savedInstanceState.getInt(STATE_ANCHOR_OFFSET)), remember = false)
        } else if (!handleIntent(intent)) {
            showWelcome()
            input.requestFocus()
        }
        updateNav()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT, current)
        outState.putInt(STATE_SCROLL, web.scrollY)
        outState.putString(STATE_ANCHOR, anchor)
        outState.putInt(STATE_ANCHOR_OFFSET, anchorOffset)
        outState.putStringArray(STATE_BACK, backStack.map { it.title }.toTypedArray())
        outState.putIntArray(STATE_BACK_SCROLLS, backStack.map { it.scroll }.toIntArray())
        outState.putStringArray(STATE_BACK_ANCHORS, backStack.map { it.anchor ?: "" }.toTypedArray())
        outState.putIntArray(STATE_BACK_OFFSETS, backStack.map { it.offset }.toIntArray())
        outState.putStringArray(STATE_FORWARD, forwardStack.map { it.title }.toTypedArray())
        outState.putIntArray(STATE_FORWARD_SCROLLS, forwardStack.map { it.scroll }.toIntArray())
        outState.putStringArray(STATE_FORWARD_ANCHORS, forwardStack.map { it.anchor ?: "" }.toTypedArray())
        outState.putIntArray(STATE_FORWARD_OFFSETS, forwardStack.map { it.offset }.toIntArray())
    }

    private fun restoreState(state: Bundle) {
        fun fill(stack: ArrayDeque<Visit>, prefix: String) {
            val titles = state.getStringArray(prefix) ?: return
            val scrolls = state.getIntArray(prefix + "_scrolls") ?: return
            val anchors = state.getStringArray(prefix + "_anchors")
            val offsets = state.getIntArray(prefix + "_offsets")
            titles.indices.forEach {
                stack.addLast(Visit(titles[it], scrolls[it], anchors?.getOrNull(it)?.ifEmpty { null },
                                    offsets?.getOrNull(it) ?: 0))
            }
        }
        fill(backStack, STATE_BACK)
        fill(forwardStack, STATE_FORWARD)
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
            textZoom = prefs.textZoom
        }
        // Las páginas traen sus propios colores oscuros (wk.css y la paleta
        // del sitio), así que el WebView no las oscurece por su cuenta; el
        // fondo transparente deja ver el de la app.
        web.setBackgroundColor(Color.TRANSPARENT)
        web.addJavascriptInterface(PageBridge(), "WkBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                followLink(request.url.toString())
                return true
            }
        }
        web.setFindListener { active, total, _ ->
            findCount.text = if (findInput.text.isEmpty()) "" else
                "${if (total == 0) 0 else active + 1}/$total"
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
            target.title == current -> target.language?.let { js("wk.show(${JSONObject.quote(it)})") }
            else -> open(target.title, target.language)
        }
    }

    /** Runs [code] in the page, if the page is an entry (it has the app's script). */
    private fun js(code: String, result: ((String?) -> Unit)? = null) {
        web.evaluateJavascript("window.wk ? $code : null") { r -> result?.invoke(r) }
    }

    /**
     * Shows the entry for [word], downloading it if needed; [language] (or
     * any anchor of the page) is where to go in it.  With [remember], the
     * word shown before goes into the history and the forward history is
     * emptied, as in a browser.
     */
    private fun open(visit: Visit, remember: Boolean) =
        open(visit.title, scroll = visit.scroll, remember = remember, at = visit.anchor, atOffset = visit.offset)

    private fun open(word: String, language: String? = null, scroll: Int = 0, remember: Boolean = true,
                     at: String? = null, atOffset: Int = 0) {
        val title = word.trim().replace('_', ' ')
        if (title.isEmpty()) return
        val before = current
        if (remember && before != title) {
            if (before != null) {
                backStack.addLast(here(before))
                while (backStack.size > MAX_HISTORY) backStack.removeFirst()
            }
            forwardStack.clear()
        }
        current = title
        updateNav()
        setInput(title)
        hideKeyboard()
        closeFind()

        val seq = ++loadSeq
        val config = PageConfig(
            languages = prefs.languageList,
            only = prefs.hideOthers,
            target = language,
            scroll = scroll,
            allLabel = getString(R.string.all_languages),
            quotes = prefs.quotations,
            tables = prefs.tables,
            closeTranslations = prefs.closeTranslations,
            anchor = at,
            anchorOffset = atOffset,
        )
        val cached = cache.peek(title)
        if (cached != null) {
            prefs.addRecent(title)
            show(pages.entry(cached, config))
            return
        }
        progress.visibility = View.VISIBLE
        loader.execute {
            var found = false
            val doc = try {
                pages.entry(cache.get(title), config).also { found = true }
            } catch (_: NotFound) {
                pages.missing(title, Wiktionary.suggestions(title))
            } catch (e: IOException) {
                pages.error(e.message ?: e.javaClass.simpleName)
            }
            main.post {
                if (seq == loadSeq && !isDestroyed) {
                    progress.visibility = View.GONE
                    if (found) prefs.addRecent(title)
                    show(doc)
                }
            }
        }
    }

    private fun show(doc: String) {
        anchor = null
        anchorOffset = 0
        web.loadDataWithBaseURL(Wiktionary.WIKI, doc, "text/html", "utf-8", null)
    }

    private fun showWelcome() {
        current = null
        loadSeq++
        setInput("")
        show(pages.welcome(prefs.recent))
        updateNav()
    }

    /** Shows the current word again, with the settings just changed. */
    private fun refresh() {
        val title = current
        if (title == null) showWelcome() else open(here(title), remember = false)
    }

    // ---- back and forward -------------------------------------------------- //

    private fun goBack(): Boolean {
        val visit = backStack.removeLastOrNull() ?: return false
        current?.let { forwardStack.addLast(here(it)) }
        open(visit, remember = false)
        return true
    }

    private fun goForward() {
        val visit = forwardStack.removeLastOrNull() ?: return
        current?.let { backStack.addLast(here(it)) }
        open(visit, remember = false)
    }

    // Los botones de atrás y adelante se apagan cuando no hay adónde ir.
    // Atrás (el del sistema) cierra antes la barra de búsqueda y vuelve a
    // la palabra anterior; sin nada de eso, el sistema cierra la app.  Desde
    // Android 13 hay que decirlo por adelantado (por el gesto predictivo),
    // registrando o quitando el callback.
    private fun updateNav() {
        // Atrás sigue activo aunque no haya historial, para que el toque
        // largo (las palabras recientes) funcione siempre; solo se atenúa.
        backButton.alpha = if (backStack.isNotEmpty()) 1f else 0.35f
        forwardButton.isEnabled = forwardStack.isNotEmpty()
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val want = backStack.isNotEmpty() || findBar.visibility == View.VISIBLE
        val cb = backCallback
        if (want && cb == null) {
            val newCb = OnBackInvokedCallback { systemBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, newCb)
            backCallback = newCb
        } else if (!want && cb != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backCallback = null
        }
    }

    private fun systemBack(): Boolean {
        if (findBar.visibility == View.VISIBLE) {
            closeFind()
            return true
        }
        return goBack()
    }

    @Deprecated("Only used before Android 13")
    override fun onBackPressed() {
        if (!systemBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- the bottom bar: contents and find --------------------------------- //

    private fun setUpBars() {
        backButton.setOnClickListener { goBack() }
        backButton.setOnLongClickListener { recentDialog(); true }
        forwardButton.setOnClickListener { goForward() }
        findViewById<ImageButton>(R.id.contents).setOnClickListener { contentsDialog() }
        findViewById<ImageButton>(R.id.find).setOnClickListener { openFind() }
        for ((id, text) in listOf(R.id.back to R.string.back, R.id.forward to R.string.forward,
                                  R.id.contents to R.string.contents, R.id.find to R.string.find)) {
            findViewById<View>(id).tooltipText = getString(text)
        }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isEmpty()) {
                    web.clearMatches()
                    findCount.text = ""
                } else {
                    web.findAllAsync(text)
                }
            }
        })
        findInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                web.findNext(true)
                true
            } else false
        }
        findViewById<ImageButton>(R.id.find_next).setOnClickListener { web.findNext(true) }
        findViewById<ImageButton>(R.id.find_prev).setOnClickListener { web.findNext(false) }
        findViewById<ImageButton>(R.id.find_close).setOnClickListener { closeFind() }
    }

    private fun openFind() {
        bottomBar.visibility = View.GONE
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(findInput, 0)
        if (findInput.text.isNotEmpty()) web.findAllAsync(findInput.text.toString())
        updateNav()
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        web.clearMatches()
        findBar.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(findInput.windowToken, 0)
        findInput.clearFocus()
        updateNav()
    }

    // El índice: las lenguas en negrita y, debajo, sus secciones, sangradas
    // según el nivel.  Tocar una lleva a ella, abriéndola si hacía falta.
    private fun contentsDialog() {
        js("wk.outline()") { result ->
            val rows = try {
                JSONArray(JSONTokener(result ?: "null").nextValue() as? String ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
            if (rows.length() == 0) {
                toast(getString(R.string.no_contents))
                return@js
            }
            val ids = ArrayList<String>()
            val labels = ArrayList<CharSequence>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                val level = row.getInt(0)
                var text = row.getString(1)
                if (level == 2 && row.optBoolean(3)) text += " " + getString(R.string.hidden_language)
                val label = SpannableString(" ".repeat(level - 2) + text)
                if (level == 2) label.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ids.add(row.getString(2))
                labels.add(label)
            }
            AlertDialog.Builder(this)
                .setTitle(current ?: getString(R.string.contents))
                .setItems(labels.toTypedArray()) { _, which -> js("wk.go(${JSONObject.quote(ids[which])})") }
                .show()
        }
    }

    // ---- looking up selected text ------------------------------------------ //

    // Al seleccionar texto en la entrada, el menú de la selección lleva
    // primero "Buscar", que abre la selección como palabra nueva.  La
    // entrada que la propia app pone en ese menú para las demás apps
    // ("Buscar en Wiktionary") sobra aquí, y se quita.
    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        // Solo para el texto de la página: no para la casilla de búsqueda ni
        // la de buscar en la página, que tienen su propio menú.
        if (mode.type != ActionMode.TYPE_FLOATING || input.hasFocus() || findInput.hasFocus()) return
        val menu = mode.menu
        if (menu.findItem(LOOKUP_ID) != null) return
        // El WebView puede añadir las entradas de las otras apps después de
        // este momento: la propia se quita también un instante más tarde.
        fun dropOwn() {
            for (i in menu.size() - 1 downTo 0) {
                val item = menu.getItem(i)
                if (item.intent?.`package` == packageName || item.intent?.component?.packageName == packageName) {
                    menu.removeItem(item.itemId)
                }
            }
        }
        dropOwn()
        main.post { dropOwn(); mode.invalidateContentRect() }
        menu.add(Menu.NONE, LOOKUP_ID, 0, R.string.look_up).setOnMenuItemClickListener {
            js("wk.selection()") { result ->
                val word = (JSONTokener(result ?: "null").nextValue() as? String)?.trim()
                mode.finish()
                if (!word.isNullOrEmpty() && word.length < 200) open(word)
            }
            true
        }
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
                if (typing && prefs.suggestions) suggest(s?.toString().orEmpty())
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
        popup.menu.findItem(R.id.quotations).isChecked = prefs.quotations
        popup.menu.findItem(R.id.tables).isChecked = prefs.tables
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.recent -> recentDialog()
                R.id.quotations -> {
                    prefs.quotations = !prefs.quotations
                    js("wk.set('quotes', ${prefs.quotations})")
                }
                R.id.tables -> {
                    prefs.tables = !prefs.tables
                    js("wk.set('tables', ${prefs.tables})")
                }
                R.id.browser -> current?.let { openInBrowser(Wiktionary.pageUrl(it)) }
                R.id.share -> current?.let { share(Wiktionary.pageUrl(it)) }
                R.id.reload -> current?.let { cache.forget(it); open(here(it), remember = false) }
                R.id.settings -> settingsDialog()
                R.id.about -> aboutDialog()
            }
            true
        }
        popup.show()
    }

    private fun recentDialog() {
        val words = prefs.recent
        if (words.isEmpty()) {
            toast(getString(R.string.no_recent))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_recent)
            .setItems(words.toTypedArray()) { _, which -> open(words[which]) }
            .show()
    }

    private fun settingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val languages = view.findViewById<EditText>(R.id.set_languages)
        val only = view.findViewById<CheckBox>(R.id.set_only)
        val quotations = view.findViewById<CheckBox>(R.id.set_quotations)
        val tables = view.findViewById<CheckBox>(R.id.set_tables)
        val translations = view.findViewById<CheckBox>(R.id.set_translations)
        val suggestions = view.findViewById<CheckBox>(R.id.set_suggestions)
        val size = view.findViewById<Spinner>(R.id.set_text_size)
        val theme = view.findViewById<Spinner>(R.id.set_theme)

        languages.setText(prefs.languages)
        only.isChecked = prefs.hideOthers
        quotations.isChecked = prefs.quotations
        tables.isChecked = prefs.tables
        translations.isChecked = prefs.closeTranslations
        suggestions.isChecked = prefs.suggestions

        val sizes = Prefs.TEXT_SIZES
        size.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                                    sizes.map { "$it %" })
        size.setSelection(sizes.indexOf(prefs.textZoom).coerceAtLeast(0))

        // El tema propio de la app solo existe desde Android 12; antes, el
        // de la app es siempre el del sistema.
        val themes = intArrayOf(UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_YES)
        val uiMode = getSystemService(UiModeManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && uiMode != null) {
            theme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)))
            theme.setSelection(themes.indexOf(prefs.theme).coerceAtLeast(0))
        } else {
            view.findViewById<View>(R.id.set_theme_row).visibility = View.GONE
        }

        view.findViewById<View>(R.id.set_clear_cache).setOnClickListener {
            toast(getString(R.string.cache_cleared, cache.clear()))
        }
        view.findViewById<View>(R.id.set_clear_recent).setOnClickListener {
            prefs.clearRecent()
            toast(getString(R.string.recent_cleared))
            if (current == null) showWelcome()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.languages = languages.text.toString()
                prefs.hideOthers = only.isChecked
                prefs.quotations = quotations.isChecked
                prefs.tables = tables.isChecked
                prefs.closeTranslations = translations.isChecked
                prefs.suggestions = suggestions.isChecked
                prefs.textZoom = sizes[size.selectedItemPosition]
                web.settings.textZoom = prefs.textZoom
                val newTheme = themes.getOrNull(theme.selectedItemPosition)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && uiMode != null &&
                    newTheme != null && newTheme != prefs.theme) {
                    prefs.theme = newTheme
                    uiMode.setApplicationNightMode(newTheme)    // la pantalla se rehace sola
                } else {
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
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
        private const val LOOKUP_ID = 0x77_6b
        private const val STATE_CURRENT = "current"
        private const val STATE_SCROLL = "scroll"
        private const val STATE_BACK = "back"
        private const val STATE_BACK_SCROLLS = "back_scrolls"
        private const val STATE_BACK_ANCHORS = "back_anchors"
        private const val STATE_BACK_OFFSETS = "back_offsets"
        private const val STATE_ANCHOR = "anchor"
        private const val STATE_ANCHOR_OFFSET = "anchor_offset"
        private const val STATE_FORWARD = "forward"
        private const val STATE_FORWARD_SCROLLS = "forward_scrolls"
        private const val STATE_FORWARD_ANCHORS = "forward_anchors"
        private const val STATE_FORWARD_OFFSETS = "forward_offsets"
    }
}
