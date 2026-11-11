package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.ChatHistoryRow
import io.github.est4s.terminal.core.ChatHub
import io.github.est4s.terminal.core.chatRelativeTime

private const val HISTORY_TITLE = "Chats"

/**
 * The agent's home in touch mode, in the pixel style: New chat, then its
 * chats ([ChatHub.rows]: the live ones with their marks, and the folder's
 * past ones), newest first. A row goes to [openChat] with its session id,
 * New chat with null. [back] is the header's ←; the terminal is the chat
 * home drawer's.
 */
@SuppressLint("ViewConstructor")
class ChatHistoryView(
    context: Context,
    private val hub: () -> ChatHub?,
    private val back: () -> Unit,
    private val openChat: (String?) -> Unit,
) : LinearLayout(context) {
    private val rows = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(16))
    }
    private val scroll = ScrollView(context).apply {
        addView(rows)
        Pixel.scrollbar(this)
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(4), 0, dp(16), 0)
        addView(iconButton(PixelIcon.BACK, Pixel.CYAN, "Back") { back() }, LayoutParams(dp(52), dp(52)))
        addView(TextView(context).apply {
            text = HISTORY_TITLE
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Pixel.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(8), 0)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    // The screen's main action: filled cyan, with the logo's shadow.
    private val newButton = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(52) + dp(Pixel.SHADOW_DP)
        background = Pixel.box(context, fill = Pixel.CYAN, border = 0, shadow = true, ripple = RIPPLE_ON_CYAN)
        setPadding(dp(16), 0, dp(16) + dp(Pixel.SHADOW_DP), dp(Pixel.SHADOW_DP))
        isFocusable = true
        contentDescription = "New chat"
        setOnClickListener { openChat(null) }
        addView(ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, PixelIcon.PLUS, Pixel.BACKGROUND))
        }, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(TextView(context).apply {
            text = "New chat"
            typeface = Pixel.label(context, 600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Pixel.BACKGROUND)
        })
    }
    // Null until the first read: "Reading…".
    private var shown: List<ChatHistoryRow>? = null
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
        addView(newButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            // The shadow sticks out 4 dp: keep the button's own edge 16 dp from the screen's.
            setMargins(dp(16), dp(16), dp(16) - dp(Pixel.SHADOW_DP), dp(8))
        })
        addView(sectionLabel(context, "Recent").apply {
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        render()
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
        rows.removeAllViews()
        val list = shown
        when {
            list == null -> rows.addView(note("Reading…"))
            list.isEmpty() -> rows.addView(note("No chats here yet."))
            else -> {
                val now = System.currentTimeMillis()
                list.forEach {
                    rows.addView(row(it, now), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    })
                }
            }
        }
    }

    // As the chat home's Continue rows: the mark, the title, and when, with what it's doing.
    private fun row(chat: ChatHistoryRow, now: Long) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isFocusable = true
        addView(chatMark(context, chat.state), LayoutParams(dp(8), dp(18)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = chat.title
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Pixel.TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(smallText(chatRelativeTime(now, chat.modifiedMs), Pixel.DIM))
                chatStateWord(chat.state)?.let { (word, color) ->
                    addView(smallText(" · ", Pixel.DIM))
                    addView(smallText(word, color))
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { openChat(chat.sessionId) }
    }

    private fun smallText(text: String, color: Int) = TextView(context).apply {
        this.text = text
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(color)
        maxLines = 1
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Pixel.DIM)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun iconButton(icon: PixelIcon, color: Int, description: String, onTap: () -> Unit) = ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, icon, color))
        scaleType = ImageView.ScaleType.CENTER
        background = pixelRipple()
        contentDescription = description
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun dp(value: Int) = Pixel.dp(context, value)

    private companion object {
        val RIPPLE_ON_CYAN = (Pixel.BACKGROUND and 0x00FFFFFF) or 0x40000000
    }
}
