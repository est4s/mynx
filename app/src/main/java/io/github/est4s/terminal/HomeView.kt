package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.HOME_MAIN
import io.github.est4s.terminal.core.HomeAction
import io.github.est4s.terminal.core.HomeItem
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.StripColors
import kotlin.concurrent.thread

/**
 * Touch mode's Home: core's [HomeMenus] as lists to tap. Menus open here;
 * every other item goes to [act]. [showTerminal] is its Terminal button.
 */
@SuppressLint("ViewConstructor")
class HomeView(
    context: Context,
    private val menus: () -> HomeMenus,
    private val act: (HomeAction) -> Unit,
    showTerminal: () -> Unit,
) : LinearLayout(context) {
    private class Level(val menu: String, val title: String)

    private val levels = ArrayList<Level>().apply { add(Level(HOME_MAIN, "mynx")) }
    private val main = Handler(Looper.getMainLooper())
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { addView(rows) }
    private val backButton = headerText("‹").apply {
        setOnClickListener { back() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val titleText = headerText("").apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) }
    private val terminalButton = headerText("Terminal").apply {
        setOnClickListener { showTerminal() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        addView(backButton)
        addView(titleText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(terminalButton)
    }
    private var shown = emptyList<HomeItem>()
    private var drawn: Level? = null
    private var asked = 0

    var colors: StripColors? = null
        set(value) {
            field = value
            value ?: return
            setBackgroundColor(value.background)
            listOf(backButton, titleText, terminalButton).forEach { it.setTextColor(value.accent) }
            render()
        }

    init {
        orientation = VERTICAL
        // Over the terminal: a touch on an empty part mustn't reach it.
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** One list up; false at the top. */
    fun back(): Boolean {
        if (levels.size == 1) return false
        levels.removeAt(levels.lastIndex)
        refresh()
        return true
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
        val action = item.action
        if (action is HomeAction.Menu) {
            // A service's row says its state too; its page is called by its name.
            levels.add(Level(action.name, action.name.substringAfter("service:", item.label)))
            scroll.scrollTo(0, 0)
            refresh()
        } else {
            act(action)
        }
    }

    private fun render() {
        val colors = colors ?: return
        val level = levels.last()
        titleText.text = level.title
        backButton.visibility = if (levels.size > 1) View.VISIBLE else View.GONE
        titleText.setPadding(if (levels.size > 1) 0 else dp(16), dp(12), dp(8), dp(12))
        rows.removeAllViews()
        shown.forEach { item -> rows.addView(row(item, colors)) }
    }

    private fun row(item: HomeItem, colors: StripColors) = TextView(context).apply {
        val enabled = item.action != HomeAction.None
        text = if (item.action is HomeAction.Menu) "${item.label}  ›" else item.label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(colors.text)
        alpha = if (enabled) 1f else 0.6f
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(56)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (enabled) {
            background = RippleDrawable(ColorStateList.valueOf(colors.selectedBackground), null, ColorDrawable(colors.text))
            isFocusable = true
            setOnClickListener { open(item) }
        }
    }

    private fun headerText(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
