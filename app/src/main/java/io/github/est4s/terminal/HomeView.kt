package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.est4s.terminal.core.HOME_MAIN
import io.github.est4s.terminal.core.HomeAction
import io.github.est4s.terminal.core.HomeItem
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.HomeRow
import io.github.est4s.terminal.core.homeInputAction
import kotlin.concurrent.thread

/**
 * Touch mode's Home: core's [HomeMenus] as lists to tap, in the pixel style,
 * Settings, System and Help too. Menus and text inputs open here, and
 * [config] runs a [HomeAction.Config] (the `mynx` request, in the app;
 * its refusal or null); every other item goes to [act]. The terminal is
 * the chat home drawer's, so there's no button for it here.
 */
@SuppressLint("ViewConstructor")
class HomeView(
    context: Context,
    private val menus: () -> HomeMenus,
    private val act: (HomeAction) -> Unit,
    private val config: (String, List<String>) -> String?,
) : LinearLayout(context) {
    private class Level(val menu: String, val title: String, val input: HomeAction.Input? = null)

    private val levels = ArrayList<Level>().apply { add(Level(HOME_MAIN, "mynx")) }
    private val main = Handler(Looper.getMainLooper())
    private val rows = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    private val scroll = ScrollView(context).apply {
        addView(rows)
        Pixel.scrollbar(this)
    }
    private val backButton = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, PixelIcon.BACK, Pixel.CYAN))
        scaleType = ImageView.ScaleType.CENTER
        background = pixelRipple()
        contentDescription = "Back"
        isFocusable = true
        setOnClickListener { back() }
    }
    private val titleText = TextView(context).apply {
        typeface = Pixel.label(context, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(Pixel.TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(4), 0, dp(16), 0)
        addView(backButton, LayoutParams(dp(52), dp(52)))
        addView(titleText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    private var shown = emptyList<HomeItem>()
    private var drawn: Level? = null
    private var asked = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        // Over the terminal: a touch on an empty part mustn't reach it.
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Set: Back (and ‹) from the list [showMenu] opened goes here instead of
     * up to the main list (touch view's chat home, whose drawer lists it).
     */
    var leave: (() -> Unit)? = null

    /** One list up; false at the top. */
    fun back(): Boolean {
        hideKeyboard()
        val leave = leave
        if (leave != null && levels.size <= 2) {
            leave()
            return true
        }
        if (levels.size == 1) return false
        levels.removeAt(levels.lastIndex)
        refresh()
        return true
    }

    /** The lists open, main first, as menu and title (a text input left out): for [restore]. */
    fun saveLevels(): List<Pair<String, String>> = levels.filter { it.input == null }.map { it.menu to it.title }

    /** Opens the lists [saveLevels] gave, for the next [refresh]. */
    fun restore(saved: List<Pair<String, String>>) {
        if (saved.isEmpty()) return
        levels.clear()
        saved.forEach { (menu, title) -> levels.add(Level(menu, title)) }
    }

    /** Makes [menu] the list on show, directly above the main one, for the next [refresh]. */
    fun showMenu(menu: String, title: String) {
        levels.subList(1, levels.size).clear()
        levels.add(Level(menu, title))
        scroll.scrollTo(0, 0)
    }

    /** Reads the current list again, off the main thread (it reads Debian's files). */
    fun refresh() {
        val request = ++asked
        val level = levels.last()
        if (level.input != null) {
            // A text input isn't a list: drawn once, so what's typed stays.
            if (level !== drawn) {
                drawn = level
                shown = emptyList()
                renderInput(level, level.input)
            }
            return
        }
        val source = menus()
        thread(name = "home-list") {
            val items = source.items(level.menu)
            main.post {
                if (request != asked) return@post
                // Nothing new (a live update elsewhere): a press on a row isn't lost to a rebuild.
                if (level === drawn && items == shown) return@post
                drawn = level
                shown = items
                render()
            }
        }
    }

    private fun open(item: HomeItem) {
        when (val action = item.action) {
            is HomeAction.Menu -> {
                // A service's row says its state too; its page is called by its name.
                levels.add(Level(action.name, action.name.substringAfter("service:", item.label)))
                scroll.scrollTo(0, 0)
                refresh()
            }
            is HomeAction.Input -> {
                levels.add(Level(levels.last().menu, action.title, action))
                scroll.scrollTo(0, 0)
                refresh()
            }
            is HomeAction.Config -> {
                val refusal = config(action.request, action.args)
                when {
                    refusal != null -> showRefusal(refusal)
                    action.back -> back()
                    else -> refresh()
                }
            }
            else -> act(action)
        }
    }

    private fun showRefusal(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    private fun startRender(title: String) {
        titleText.text = title
        val canGoBack = levels.size > 1 || leave != null
        backButton.visibility = if (canGoBack) View.VISIBLE else View.GONE
        titleText.setPadding(if (canGoBack) dp(4) else dp(12), dp(12), dp(8), dp(12))
        rows.removeAllViews()
    }

    private fun render() {
        startRender(levels.last().title)
        shown.forEachIndexed { i, item ->
            val view = when (item.kind) {
                HomeRow.SECTION -> sectionLabel(context, item.label).apply { setPadding(0, if (i == 0) 0 else dp(16), 0, dp(4)) }
                HomeRow.TEXT -> paragraph(item.label)
                HomeRow.INFO -> infoRow(item)
                HomeRow.BUTTON -> row(item)
            }
            rows.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    // As the drawer's rows: a box, the label in Pixelify Sans, then the value, a tick, a theme's colours or › for a list;
    // a row that does nothing is dimmed, no box.
    private fun row(item: HomeItem) = LinearLayout(context).apply {
        val enabled = item.action != HomeAction.None
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(16), dp(8), dp(12), dp(8))
        addView(TextView(context).apply {
            text = item.label
            typeface = Pixel.label(context, 500)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(if (!enabled) Pixel.DIM else if (item.checked) Pixel.CYAN else Pixel.TEXT)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (item.swatch.isNotEmpty()) addView(swatch(item.swatch), LayoutParams(LayoutParams.WRAP_CONTENT, dp(20)).apply { marginStart = dp(8) })
        item.detail?.let { detail ->
            addView(TextView(context).apply {
                text = detail
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (detail == "On") Pixel.CYAN else Pixel.DIM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                gravity = Gravity.END
                maxWidth = dp(180)
                setPadding(dp(12), 0, dp(4), 0)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        val end = when {
            item.checked -> PixelIcon.CHECK
            item.action is HomeAction.Menu || item.action is HomeAction.Input -> PixelIcon.CHEVRON
            else -> null
        }
        end?.let {
            addView(ImageView(context).apply {
                setImageDrawable(Pixel.icon(context, it, if (item.checked) Pixel.CYAN else Pixel.DIM))
            }, LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })
        }
        if (enabled) {
            background = Pixel.box(context, fill = Pixel.CARD, border = if (item.checked) Pixel.CYAN else Pixel.LINE, ripple = Pixel.LINE)
            isFocusable = true
            setOnClickListener { open(item) }
        }
    }

    // A theme at a glance: its background and text, then colours 1 to 6.
    private fun swatch(colors: List<Int>) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        background = Pixel.box(context, fill = colors[0], border = Pixel.LINE)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        colors.drop(1).forEach { color ->
            addView(View(context).apply { setBackgroundColor(color) }, LayoutParams(dp(8), dp(12)).apply { marginEnd = dp(2) })
        }
    }

    private fun paragraph(text: String) = TextView(context).apply {
        this.text = text
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.DIM)
        setLineSpacing(0f, 1.15f)
        setTextIsSelectable(true)
    }

    // A fact: its name, and its value in the text font; nothing to tap.
    private fun infoRow(item: HomeItem) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        background = Pixel.box(context, fill = Pixel.BACKGROUND, border = Pixel.LINE)
        addView(TextView(context).apply {
            text = item.label
            typeface = Pixel.label(context, 500)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Pixel.TEXT)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        item.detail?.let { detail ->
            addView(TextView(context).apply {
                text = detail
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Pixel.DIM)
                gravity = Gravity.END
                setTextIsSelectable(true)
                setPadding(dp(12), 0, 0, 0)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.4f))
        }
    }

    // A line of text: the setting's description, the field (cyan, as the chat's box) and Save.
    private fun renderInput(level: Level, input: HomeAction.Input) {
        startRender(level.title)
        input.note?.let { rows.addView(paragraph(it), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }) }
        val error = TextView(context).apply {
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Pixel.PINK_TEXT)
            visibility = View.GONE
        }
        val report = input.target == "report"
        val field = EditText(context).apply {
            setText(input.value)
            setSelection(text.length)
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Pixel.TEXT)
            setHintTextColor(Pixel.DIM)
            hint = if (report) "What happened, and what you expected" else input.title
            background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.CYAN)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (report) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
        }
        fun save() {
            when (val action = homeInputAction(input.target, field.text.toString())) {
                is HomeAction.Config -> {
                    val refusal = config(action.request, action.args)
                    if (refusal == null) back() else {
                        error.text = refusal
                        error.visibility = View.VISIBLE
                    }
                }
                HomeAction.None -> {
                    error.text = "Type something first."
                    error.visibility = View.VISIBLE
                }
                else -> {
                    hideKeyboard()
                    levels.removeAt(levels.lastIndex)
                    act(action)
                }
            }
        }
        field.setOnEditorActionListener { _, id, event ->
            if (id == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) { save(); true } else false
        }
        rows.addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rows.addView(error, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        rows.addView(saveButton(if (report) "Next" else "Save") { save() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        field.requestFocus()
        field.post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0) }
    }

    // The page's main action: filled cyan with the pink shadow, as the chat history's New chat.
    private fun saveButton(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Pixel.BACKGROUND)
        gravity = Gravity.CENTER
        minWidth = dp(120) + dp(Pixel.SHADOW_DP)
        minHeight = dp(48) + dp(Pixel.SHADOW_DP)
        background = Pixel.box(context, fill = Pixel.CYAN, border = 0, shadow = true, ripple = Pixel.RIPPLE_ON_CYAN)
        setPadding(dp(20), 0, dp(20) + dp(Pixel.SHADOW_DP), dp(Pixel.SHADOW_DP))
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}
