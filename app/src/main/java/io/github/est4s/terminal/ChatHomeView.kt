package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.ChatHistoryRow
import io.github.est4s.terminal.core.ChatHome
import io.github.est4s.terminal.core.ChatRowState
import io.github.est4s.terminal.core.HomeAction
import io.github.est4s.terminal.core.servicesMark

/** What the chat home's taps do; MainActivity implements it. */
interface ChatHomeActions {
    /** A new chat with this first prompt, shown. */
    fun send(text: String)
    /** +: a new chat with [draft] in its box and its Add context sheet open. */
    fun addContext(draft: String)
    /** The mic: a new chat with [draft] in its box, dictation started. */
    fun dictate(draft: String)
    /** A chat's row; null is New chat (empty). */
    fun openChat(sessionId: String?)
    /** The history screen. */
    fun allChats()
    /** A Linux item that isn't a list, or the Install button. */
    fun act(action: HomeAction)
    /** A Linux item with [HomeAction.Menu]: Home's list screen at [menu]. */
    fun openList(menu: String, title: String)
    /** The top row's ● N. */
    fun openServices()
    /** The drawer's Terminal. */
    fun showTerminal()
}

/**
 * Touch view's start screen: core's [ChatHome] in the pixel style. The prompt
 * box starts a new chat; Continue lists the live ones; ☰ opens [DrawerView]
 * over it. Only the Send button sends: Enter is a new line.
 */
@SuppressLint("ViewConstructor")
class ChatHomeView(
    context: Context,
    /** Builds the model off the main thread or not; calls done on the main thread. */
    private val load: (done: (ChatHome) -> Unit) -> Unit,
    private val actions: ChatHomeActions,
    /** The title art: MainActivity passes R.drawable.mynx_title (a vector); a View factory keeps tests simple. */
    private val titleArt: (Context) -> View,
) : FrameLayout(context) {
    private var shown: ChatHome? = null
    private var asked = 0

    private val menuButton = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, PixelIcon.MENU, Pixel.CYAN))
        scaleType = ImageView.ScaleType.CENTER
        background = Pixel.box(context, fill = Pixel.BACKGROUND, border = Pixel.LINE, ripple = Pixel.LINE)
        contentDescription = "Menu"
        isFocusable = true
        setOnClickListener { openDrawer() }
    }
    private val servicesText = TextView(context).apply {
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.CYAN)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minWidth = dp(44)
        setPadding(dp(8), 0, dp(8), 0)
        background = rowRipple()
        isFocusable = true
        visibility = View.GONE
        setOnClickListener { actions.openServices() }
    }
    private val topRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(4))
        addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(titleArt(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            marginStart = dp(12)
        })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(servicesText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private val continueLabel = sectionLabel(context, "Continue")
    private val continueRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val middle = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(8))
        // Pushes the heading to the bottom half, where the thumb reaches it.
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(TextView(context).apply {
            text = "What do you want to make?"
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Pixel.TEXT)
        })
        addView(TextView(context).apply {
            text = "Claude Code builds apps, scripts and games here, in your Debian."
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Pixel.DIM)
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(continueLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
            bottomMargin = dp(8)
        })
        addView(continueRows, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        addView(middle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The keyboard shrinks it: keep the bottom (the last Continue row) in sight.
        addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) post { scrollTo(0, Int.MAX_VALUE) }
        }
    }

    private val input = EditText(context).apply {
        background = null
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Pixel.TEXT)
        setHintTextColor(Pixel.DIM)
        highlightColor = (Pixel.CYAN and 0x00FFFFFF) or 0x55000000
        hint = "Ask Claude Code…"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 1
        maxLines = 5
        isVerticalScrollBarEnabled = true
        gravity = Gravity.TOP or Gravity.START
        setPadding(dp(4), dp(6), dp(4), dp(6))
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(Pixel.CYAN)
                setSize(dp(2), 0)
            }
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSend()
        })
    }
    private val plusButton = iconButton(PixelIcon.PLUS, "Add context") { actions.addContext(takeDraft()) }
    private val micButton = iconButton(PixelIcon.MIC, "Dictate") { actions.dictate(takeDraft()) }
    private val sendButton = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Send"
        isFocusable = true
        setOnClickListener { sendDraft() }
    }
    private val promptBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.CYAN, shadow = true)
        // The border inside, and the shadow's room right and bottom.
        setPadding(dp(10), dp(8), dp(10) + dp(Pixel.SHADOW_DP), dp(8) + dp(Pixel.SHADOW_DP))
        isClickable = true
        setOnClickListener { focusInput() }
        addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(plusButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(micButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private val installButton = TextView(context).apply {
        text = "Install Claude Code"
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Pixel.BACKGROUND)
        gravity = Gravity.CENTER
        minHeight = dp(52) + dp(Pixel.SHADOW_DP)
        background = Pixel.box(context, fill = Pixel.CYAN, border = 0, shadow = true, ripple = RIPPLE_ON_CYAN)
        setPadding(dp(16), dp(8), dp(16) + dp(Pixel.SHADOW_DP), dp(8) + dp(Pixel.SHADOW_DP))
        isFocusable = true
        visibility = View.GONE
        setOnClickListener { shown?.let { actions.act(it.installAction) } }
    }
    private val footer = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Pixel.DIM)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(dp(16), dp(10), dp(16), dp(12))
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val side = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16)
            // The shadow sticks out 4 dp: keep the box's own edge 16 dp from the screen's.
            marginEnd = dp(16) - dp(Pixel.SHADOW_DP)
            topMargin = dp(4)
        }
        addView(promptBox, side)
        addView(installButton, LinearLayout.LayoutParams(side))
        addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private val scrim = View(context).apply {
        setBackgroundColor(Pixel.SCRIM)
        isClickable = true
        visibility = View.GONE
        setOnClickListener { closeDrawer() }
    }
    private val drawer = DrawerView(context, titleArt, actions) { closeDrawer() }.apply {
        visibility = View.GONE
    }

    /** Whether the drawer shows. */
    val drawerOpen: Boolean
        get() = drawer.visibility == View.VISIBLE

    init {
        setBackgroundColor(Pixel.BACKGROUND)
        // Over the terminal: a touch on an empty part mustn't reach it.
        isClickable = true
        // The view takes focus first, so the keyboard waits for a tap on the box.
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(drawer, LayoutParams(dp(DRAWER_MAX_DP), LayoutParams.MATCH_PARENT, Gravity.START))
        updateSend()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0) {
            (drawer.layoutParams as LayoutParams).width = minOf((width * DRAWER_SHARE).toInt(), dp(DRAWER_MAX_DP))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Asks [load] for the model and draws it; an answer to an older ask is dropped. */
    fun refresh() {
        val own = ++asked
        load { model -> if (own == asked) show(model) }
    }

    /** Draws [model] (the screenshots give it directly). */
    fun show(model: ChatHome) {
        // Nothing new: a press on a row isn't lost to a rebuild.
        if (model == shown) return
        shown = model
        val mark = servicesMark(model.servicesActive)
        servicesText.text = mark ?: ""
        servicesText.visibility = if (mark != null) View.VISIBLE else View.GONE
        continueLabel.visibility = if (model.continueRows.isEmpty()) View.GONE else View.VISIBLE
        continueRows.removeAllViews()
        model.continueRows.forEach { row ->
            continueRows.addView(continueRow(row), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        promptBox.visibility = if (model.agentInstalled) View.VISIBLE else View.GONE
        installButton.visibility = if (model.agentInstalled) View.GONE else View.VISIBLE
        footer.text = model.footer
        drawer.show(model)
    }

    fun openDrawer() {
        hideKeyboard()
        scrim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
    }

    fun closeDrawer() {
        scrim.visibility = View.GONE
        drawer.visibility = View.GONE
    }

    /** Back: closes the drawer if it shows (true), else false. */
    fun back(): Boolean {
        if (!drawerOpen) return false
        closeDrawer()
        return true
    }

    /** Puts the cursor in the box and shows the keyboard. */
    fun focusInput() {
        if (!input.isShown) return
        input.requestFocus()
        input.setSelection(input.text.length)
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun clearInput() {
        input.setText("")
    }

    private fun hideKeyboard() {
        if (!input.hasFocus()) return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        requestFocus()
    }

    private fun sendDraft() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        clearInput()
        actions.send(text)
    }

    // The draft moves to the new chat's box, so it doesn't wait here too.
    private fun takeDraft(): String {
        val draft = input.text.toString()
        hideKeyboard()
        clearInput()
        return draft
    }

    private fun updateSend() {
        val ready = input.text.isNotBlank()
        sendButton.isEnabled = ready
        sendButton.setImageDrawable(Pixel.icon(context, PixelIcon.SEND, if (ready) Pixel.BACKGROUND else Pixel.DIM))
        sendButton.background = if (ready) {
            Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = RIPPLE_ON_CYAN)
        } else {
            Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE)
        }
    }

    private fun continueRow(row: ChatHistoryRow) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isFocusable = true
        addView(chatMark(context, row.state), LinearLayout.LayoutParams(dp(8), dp(18)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = row.title
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Pixel.TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            chatStateWord(row.state)?.let { (word, color) ->
                addView(TextView(context).apply {
                    text = word
                    typeface = Pixel.text(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(color)
                })
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { actions.openChat(row.sessionId) }
    }

    private fun iconButton(icon: PixelIcon, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, icon, Pixel.TEXT))
        scaleType = ImageView.ScaleType.CENTER
        background = rowRipple()
        contentDescription = description
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun rowRipple() = pixelRipple()

    private fun dp(value: Int) = Pixel.dp(context, value)

    private companion object {
        const val DRAWER_SHARE = 0.82f
        const val DRAWER_MAX_DP = 360
        val RIPPLE_ON_CYAN = (Pixel.BACKGROUND and 0x00FFFFFF) or 0x40000000
    }
}

/** A pressed look for a row with no box of its own. */
internal fun pixelRipple() = RippleDrawable(ColorStateList.valueOf(Pixel.LINE), null, ColorDrawable(Pixel.TEXT))

/** A section's heading: "CONTINUE", "CHATS", "LINUX". */
internal fun sectionLabel(context: Context, text: String) = TextView(context).apply {
    this.text = text.uppercase()
    typeface = Pixel.label(context, 600)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(Pixel.PINK_TEXT)
    letterSpacing = 0.12f
}

/** A live chat's square mark (cyan working, pink waiting); an empty one keeps past chats' titles in line. */
internal fun chatMark(context: Context, state: ChatRowState?) = View(context).apply {
    when (state) {
        ChatRowState.WORKING -> setBackgroundColor(Pixel.CYAN)
        ChatRowState.WAITING -> setBackgroundColor(Pixel.PINK)
        ChatRowState.IDLE, null -> {}
    }
}

/** What a live chat's mark says, in its colour; null for idle and past chats. */
internal fun chatStateWord(state: ChatRowState?): Pair<String, Int>? = when (state) {
    ChatRowState.WORKING -> "working" to Pixel.CYAN
    ChatRowState.WAITING -> "waiting for you" to Pixel.PINK_TEXT
    ChatRowState.IDLE, null -> null
}
