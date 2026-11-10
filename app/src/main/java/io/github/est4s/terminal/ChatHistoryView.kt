package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.ChatHistoryRow
import io.github.est4s.terminal.core.ChatHub
import io.github.est4s.terminal.core.ChatRowState
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.chatRelativeTime

private const val HISTORY_TITLE = "Claude Code"

/**
 * The agent's home in touch mode: New chat, then its chats ([ChatHub.rows]:
 * the live ones with their marks, and the folder's past ones), newest first.
 * A row goes to [openChat] with its session id, New chat with null. [back] is
 * the header's ‹, [showTerminal] its Terminal.
 */
@SuppressLint("ViewConstructor")
class ChatHistoryView(
    context: Context,
    private val hub: () -> ChatHub?,
    private val back: () -> Unit,
    showTerminal: () -> Unit,
    private val openChat: (String?) -> Unit,
) : LinearLayout(context) {
    private val rows = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(8))
    }
    private val scroll = ScrollView(context).apply { addView(rows) }
    private val backButton = headerText("‹").apply {
        setOnClickListener { back() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val titleText = headerText(HISTORY_TITLE).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(0, dp(12), dp(8), dp(12))
        ellipsize = TextUtils.TruncateAt.END
    }
    private val terminalButton = headerText("Terminal").apply {
        setOnClickListener { showTerminal() }
        setPadding(dp(8), dp(12), dp(16), dp(12))
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        addView(backButton)
        addView(titleText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(terminalButton)
    }
    private val newButton = TextView(context).apply {
        text = "New chat"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isFocusable = true
        setOnClickListener { openChat(null) }
    }
    // Null until the first read: "Reading…".
    private var shown: List<ChatHistoryRow>? = null
    private var asked = 0

    var colors: StripColors? = null
        set(value) {
            if (value == field) return
            field = value
            value ?: return
            setBackgroundColor(value.background)
            listOf(backButton, titleText, terminalButton).forEach { it.setTextColor(value.accent) }
            newButton.setTextColor(value.background)
            val radius = dp(8).toFloat()
            val shape = GradientDrawable().apply {
                cornerRadius = radius
                setColor(value.accent)
            }
            newButton.background = RippleDrawable(ColorStateList.valueOf((value.background and 0x00FFFFFF) or 0x40000000), shape, null)
            render()
        }

    init {
        orientation = VERTICAL
        // Over the terminal: a touch on an empty part mustn't reach it.
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(newButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(4), dp(16), dp(8))
        })
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Reads the past chats again (off the main thread), with the live ones' marks. */
    fun refresh() {
        val hub = hub() ?: return
        val own = ++asked
        hub.history { list -> if (own == asked) show(list) }
    }

    /** A live chat changed: its mark, without reading the folder again. */
    fun update() {
        if (visibility != View.VISIBLE || shown == null) return
        show(hub()?.rows() ?: return)
    }

    /** Draws [list] (the screenshots give it directly). */
    fun show(list: List<ChatHistoryRow>) {
        if (list == shown) return
        shown = list
        render()
    }

    private fun render() {
        val colors = colors ?: return
        rows.removeAllViews()
        val list = shown
        when {
            list == null -> rows.addView(note("Reading…", colors))
            list.isEmpty() -> rows.addView(note("No chats here yet.", colors))
            else -> {
                val now = System.currentTimeMillis()
                list.forEach { rows.addView(row(it, now, colors), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
            }
        }
    }

    private fun row(chat: ChatHistoryRow, now: Long, colors: StripColors) = LinearLayout(context).apply {
        orientation = VERTICAL
        minimumHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(colors.selectedBackground), null, ColorDrawable(colors.background))
        addView(TextView(context).apply {
            text = chat.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colors.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = chatRelativeTime(now, chat.modifiedMs)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(colors.accent)
            })
            mark(chat.state)?.let { (label, tone) ->
                addView(TextView(context).apply {
                    text = "  ●  $label"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(if (tone) colors.mark else colors.text)
                })
            }
        })
        setOnClickListener { openChat(chat.sessionId) }
    }

    // Waiting for you stands out: the chat can't go on till it's answered.
    private fun mark(state: ChatRowState?): Pair<String, Boolean>? = when (state) {
        ChatRowState.WORKING -> "Working" to false
        ChatRowState.WAITING -> "Waiting for you" to true
        ChatRowState.IDLE, null -> null
    }

    private fun note(text: String, colors: StripColors) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(colors.accent)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun headerText(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        maxLines = 1
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
