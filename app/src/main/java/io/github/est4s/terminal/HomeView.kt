package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.HOME_MAIN
import io.github.est4s.terminal.core.HomeAction
import io.github.est4s.terminal.core.HomeItem
import io.github.est4s.terminal.core.HomeMenus
import kotlin.concurrent.thread

/**
 * Touch mode's Home: core's [HomeMenus] as lists to tap, in the pixel style.
 * Menus open here; every other item goes to [act]. The terminal is the chat
 * home drawer's, so there's no button for it here.
 */
@SuppressLint("ViewConstructor")
class HomeView(
    context: Context,
    private val menus: () -> HomeMenus,
    private val act: (HomeAction) -> Unit,
) : LinearLayout(context) {
    private class Level(val menu: String, val title: String)

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
        val level = levels.last()
        titleText.text = level.title
        val canGoBack = levels.size > 1 || leave != null
        backButton.visibility = if (canGoBack) View.VISIBLE else View.GONE
        titleText.setPadding(if (canGoBack) dp(4) else dp(12), dp(12), dp(8), dp(12))
        rows.removeAllViews()
        shown.forEach { item ->
            rows.addView(row(item), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    // As the drawer's rows: a box, the label in Pixelify Sans, › for a list; a row that does nothing is dimmed, no box.
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
            setTextColor(if (enabled) Pixel.TEXT else Pixel.DIM)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (item.action is HomeAction.Menu) {
            addView(ImageView(context).apply {
                setImageDrawable(Pixel.icon(context, PixelIcon.CHEVRON, Pixel.DIM))
            }, LayoutParams(dp(20), dp(20)))
        }
        if (enabled) {
            background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
            isFocusable = true
            setOnClickListener { open(item) }
        }
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}
