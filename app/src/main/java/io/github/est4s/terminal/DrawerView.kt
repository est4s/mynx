package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.ChatHistoryRow
import io.github.est4s.terminal.core.ChatHome
import io.github.est4s.terminal.core.DrawerItem
import io.github.est4s.terminal.core.HomeAction

/**
 * The chat home's drawer: New chat, the latest chats, All chats, Home's main
 * list (Linux) and, outside the scroll, Terminal. Every tap that leaves the
 * home runs [close] first.
 */
@SuppressLint("ViewConstructor")
class DrawerView(
    context: Context,
    titleArt: (Context) -> View,
    private val actions: ChatHomeActions,
    private val close: () -> Unit,
) : LinearLayout(context) {
    private val chats = LinearLayout(context).apply { orientation = VERTICAL }
    private val linux = LinearLayout(context).apply { orientation = VERTICAL }
    private val column = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(16), dp(12), dp(8))
        addView(titleArt(context), LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply {
            marginStart = dp(4)
            bottomMargin = dp(16)
        })
        addView(newChatButton(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(sectionLabel(context, "Chats"), labelParams())
        addView(chats, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(textRow("All chats ›", Pixel.DIM, Pixel.label(context, 500), 15f) { leave { actions.allChats() } },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(12)
        })
        addView(sectionLabel(context, "Linux"), labelParams())
        addView(linux, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val scroll = ScrollView(context).apply { addView(column) }

    init {
        orientation = VERTICAL
        // Over the home: touches on its empty parts stay here.
        isClickable = true
        // The 2 dp line down its right edge.
        background = LayerDrawable(arrayOf<Drawable>(ColorDrawable(Pixel.LINE), ColorDrawable(Pixel.BACKGROUND))).apply {
            setLayerInset(1, 0, 0, dp(2), 0)
        }
        setPadding(0, 0, dp(2), 0)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(terminalButton(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(8), dp(12), dp(12))
        })
    }

    /** Draws the drawer's part of [model]. */
    fun show(model: ChatHome) {
        chats.removeAllViews()
        if (model.drawerChats.isEmpty()) {
            chats.addView(textRow("No chats yet.", Pixel.DIM, Pixel.text(context), 14f, null))
        }
        model.drawerChats.forEach { chats.addView(chatRow(it), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
        linux.removeAllViews()
        model.linux.forEach { linux.addView(linuxRow(it), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    private fun leave(then: () -> Unit) {
        close()
        then()
    }

    private fun newChatButton() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48) + dp(Pixel.SHADOW_DP)
        background = Pixel.box(context, fill = Pixel.CYAN, border = 0, shadow = true,
            ripple = (Pixel.BACKGROUND and 0x00FFFFFF) or 0x40000000)
        setPadding(dp(12), dp(8), dp(12) + dp(Pixel.SHADOW_DP), dp(8) + dp(Pixel.SHADOW_DP))
        isFocusable = true
        addView(ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, PixelIcon.CHAT, Pixel.BACKGROUND))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        addView(TextView(context).apply {
            text = "New chat"
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Pixel.BACKGROUND)
        })
        setOnClickListener { leave { actions.openChat(null) } }
    }

    private fun chatRow(row: ChatHistoryRow) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = pixelRipple()
        isFocusable = true
        addView(chatMark(context, row.state), LayoutParams(dp(8), dp(16)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = row.title
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Pixel.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { leave { actions.openChat(row.sessionId) } }
    }

    private fun linuxRow(item: DrawerItem) = LinearLayout(context).apply {
        val enabled = item.action != HomeAction.None
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        alpha = if (enabled) 1f else 0.6f
        addView(TextView(context).apply {
            text = item.label
            typeface = Pixel.label(context, 500)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Pixel.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (item.opensList) {
            addView(ImageView(context).apply {
                setImageDrawable(Pixel.icon(context, PixelIcon.CHEVRON, Pixel.DIM, 16))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        if (enabled) {
            background = pixelRipple()
            isFocusable = true
            val action = item.action
            setOnClickListener {
                leave {
                    if (action is HomeAction.Menu) actions.openList(action.name, item.label) else actions.act(action)
                }
            }
        }
    }

    private fun terminalButton() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        background = Pixel.box(context, fill = Pixel.BACKGROUND, border = Pixel.LINE, ripple = Pixel.LINE)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
        addView(TextView(context).apply {
            text = "Terminal"
            typeface = Pixel.label(context, 500)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Pixel.TEXT)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, PixelIcon.TERMINAL, Pixel.CYAN))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { leave { actions.showTerminal() } }
    }

    private fun textRow(text: String, color: Int, face: android.graphics.Typeface, sp: Float, onClick: (() -> Unit)?) =
        TextView(context).apply {
            this.text = text
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(24), dp(4), dp(4), dp(4))
            if (onClick != null) {
                background = pixelRipple()
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    private fun labelParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(18)
        bottomMargin = dp(6)
        marginStart = dp(4)
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}
