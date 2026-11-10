package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.CHAT_MODES
import io.github.est4s.terminal.core.ChatController
import io.github.est4s.terminal.core.ChatState
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.chatModeName
import io.github.est4s.terminal.core.usageRows
import java.time.ZoneId

/** What the + sheet's tiles add to the next prompt. */
enum class ChatAttachSource { CAMERA, PHOTOS, FILES }

/** The bottom sheets over Chat; a sheet's sub page (Permission, Model) goes back to the one it came from. */
enum class ChatSheetPage { ADD, PERMISSION, DEFAULT_MODE, MODEL, USAGE }

// A theme colour with another opacity: the only colours not straight from the theme.
internal fun fadedColor(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

// Halfway between two theme colours: the sheet stands out from the chat under it.
private fun mixColor(a: Int, b: Int) = Color.rgb(
    (Color.red(a) + Color.red(b)) / 2,
    (Color.green(a) + Color.green(b)) / 2,
    (Color.blue(a) + Color.blue(b)) / 2,
)

/** One of Chat's icons (`res/drawable/chat_*.xml`), tinted. */
internal fun chatIcon(context: Context, res: Int, color: Int, sizeDp: Int) = ImageView(context).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    scaleType = ImageView.ScaleType.FIT_CENTER
    val size = (sizeDp * resources.displayMetrics.density).toInt()
    layoutParams = LinearLayout.LayoutParams(size, size)
}

/**
 * The header's context use: a thin ring, filled as far as [percent]
 * (an empty ring when it isn't known yet).
 */
class UsageRing(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    var percent: Int? = null
        set(value) {
            if (value == field) return
            field = value
            contentDescription = value?.let { "Context $it% used" } ?: "Usage"
            invalidate()
        }

    fun setColors(colors: StripColors) {
        track.color = fadedColor(colors.text, 0x40)
        arc.color = colors.accent
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = 20f * density
        val left = (width - size) / 2
        val top = (height - size) / 2
        val inset = track.strokeWidth / 2
        box.set(left + inset, top + inset, left + size - inset, top + size - inset)
        canvas.drawOval(box, track)
        val shown = percent ?: return
        // A sliver even at 0%, so a known value looks different from an unknown one.
        val sweep = (shown.coerceIn(0, 100) * 3.6f).coerceAtLeast(6f)
        canvas.drawArc(box, -90f, sweep, false, arc)
    }
}

// The sheet's own height stops short of the top: the chat stays in sight above it.
private class SheetColumn(context: Context) : LinearLayout(context) {
    var maxWidthPx = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentH = MeasureSpec.getSize(heightMeasureSpec)
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.makeMeasureSpec((parentH * 0.9f).toInt(), MeasureSpec.AT_MOST)
        val w = MeasureSpec.makeMeasureSpec(minOf(parentW, maxWidthPx), MeasureSpec.EXACTLY)
        super.onMeasure(w, h)
    }
}

/**
 * The layer over Chat that shows its bottom sheets: + (Add context, with
 * Permission and Model), and Usage from the header's ring. Drawn from
 * [state] each time a page opens or the chat changes; picks go to [chat].
 * [attach] is null when the screen can't add files (nothing to pick with).
 */
@SuppressLint("ViewConstructor")
class ChatSheetLayer(
    context: Context,
    private val state: () -> ChatState?,
    private val chat: () -> ChatController?,
    private val attach: ((ChatAttachSource) -> Unit)?,
    private val clock: () -> Long,
) : FrameLayout(context) {
    private val stack = ArrayList<ChatSheetPage>()
    private var drawnState: ChatState? = null
    private val scrim = View(context).apply {
        setBackgroundColor(0x99000000.toInt())
        setOnClickListener { close() }
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
    }
    private val sheet = SheetColumn(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        maxWidthPx = dp(640)
    }

    var colors: StripColors? = null
        set(value) {
            if (value == field) return
            field = value
            if (isOpen) draw()
        }

    val isOpen get() = stack.isNotEmpty()

    init {
        visibility = View.GONE
        addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    /** Opens [page] over whatever sheet shows (a sub page), or on its own. */
    fun open(page: ChatSheetPage) {
        stack.add(page)
        visibility = View.VISIBLE
        draw()
    }

    /** Back: a sub page goes to the page before; true if a sheet was open. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) visibility = View.GONE else draw()
        return true
    }

    fun close() {
        stack.clear()
        visibility = View.GONE
    }

    /** The chat changed: the open page shows it. */
    fun update() {
        if (isOpen && state() != drawnState) draw()
    }

    private fun draw() {
        val colors = colors ?: return
        val page = stack.lastOrNull() ?: return
        val now = state()
        drawnState = now
        sheet.background = GradientDrawable().apply {
            setColor(mixColor(colors.background, colors.selectedBackground))
            val r = dp(24).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        sheet.removeAllViews()
        sheet.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(fadedColor(colors.text, 0x50))
                cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        content.removeAllViews()
        when (page) {
            ChatSheetPage.ADD -> addPage(now, colors)
            ChatSheetPage.PERMISSION -> permissionPage(now, colors)
            ChatSheetPage.DEFAULT_MODE -> defaultModePage(now, colors)
            ChatSheetPage.MODEL -> modelPage(now, colors)
            ChatSheetPage.USAGE -> usagePage(now, colors)
        }
        sheet.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // × on a sheet of its own, ← on a sub page; the title centred over the whole width.
    private fun header(title: String, colors: StripColors, end: View? = null) {
        val bar = FrameLayout(context).apply { minimumHeight = dp(56) }
        val sub = stack.size > 1
        bar.addView(iconButton(if (sub) R.drawable.chat_back else R.drawable.chat_close, if (sub) "Back" else "Close", colors.text) {
            if (sub) back() else close()
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(TextView(context).apply {
            text = title
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(56), 0, dp(56), 0)
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        end?.let { bar.addView(it, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL)) }
        sheet.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(4), 0, dp(4), 0) })
    }

    private fun addPage(state: ChatState?, colors: StripColors) {
        header("Add context", colors)
        attach?.let { pick ->
            val tiles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                Triple(ChatAttachSource.CAMERA, R.drawable.chat_camera, "Camera"),
                Triple(ChatAttachSource.PHOTOS, R.drawable.chat_photo, "Photos"),
                Triple(ChatAttachSource.FILES, R.drawable.chat_file, "Files"),
            ).forEachIndexed { i, (source, icon, label) ->
                tiles.addView(tile(icon, label, colors) {
                    close()
                    pick(source)
                }, LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            content.addView(tiles, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        content.addView(navRow(R.drawable.chat_bolt, "Permission", modeLabel(state?.mode ?: "auto"), colors) {
            open(ChatSheetPage.PERMISSION)
        }, rowParams())
        content.addView(navRow(R.drawable.chat_model, "Model", state?.let(::modelLabel) ?: "Default", colors) {
            open(ChatSheetPage.MODEL)
        }, rowParams())
    }

    private fun permissionPage(state: ChatState?, colors: StripColors) {
        header("Permission", colors)
        content.addView(note("For this chat", colors))
        CHAT_MODES.forEach { mode ->
            content.addView(choiceRow(mode.name, null, state?.mode == mode.value, colors) {
                chat()?.setMode(mode.value)
                back()
            }, rowParams(0))
        }
        val current = state?.defaultMode ?: "auto"
        content.addView(navRow(null, "Default for new chats", modeLabel(current), colors) {
            open(ChatSheetPage.DEFAULT_MODE)
        }, rowParams().apply { topMargin = dp(12) })
    }

    private fun defaultModePage(state: ChatState?, colors: StripColors) {
        header("Default for new chats", colors)
        content.addView(note("New chats start in this mode; a chat's own pick wins for that chat.", colors))
        val current = state?.defaultMode ?: "auto"
        CHAT_MODES.forEach { mode ->
            content.addView(choiceRow(mode.name, null, current == mode.value, colors) {
                chat()?.setDefaultMode(mode.value)
                back()
            }, rowParams(0))
        }
    }

    private fun modelPage(state: ChatState?, colors: StripColors) {
        header("Model", colors)
        state?.models.orEmpty().forEach { model ->
            content.addView(choiceRow(model.name, model.description, state?.model == model.value, colors) {
                chat()?.setModel(model.value)
                back()
            }, rowParams(0))
        }
    }

    private fun usagePage(state: ChatState?, colors: StripColors) {
        header("Usage", colors, iconButton(R.drawable.chat_refresh, "Refresh", colors.text) { draw() })
        val usage = state?.usage ?: return
        var section: String? = null
        usageRows(usage, clock(), ZoneId.systemDefault()).forEach { row ->
            if (row.section != section) {
                section = row.section
                content.addView(TextView(context).apply {
                    text = row.section
                    setTextColor(fadedColor(colors.text, 0xB0))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(dp(8), dp(12), dp(8), dp(8))
                })
            }
            content.addView(usageCard(row.label, row.percent, row.detail, colors), rowParams())
        }
    }

    private fun usageCard(label: String, percent: Int?, detail: String, colors: StripColors) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colors.selectedBackground, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            percent?.let { p ->
                addView(TextView(context).apply {
                    text = "$p% used"
                    setTextColor(fadedColor(colors.text, 0xB0))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(dp(8), 0, 0, 0)
                })
            }
        })
        percent?.let { p ->
            addView(UsageBar(context, p.coerceIn(0, 100), colors), LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            })
        }
        if (detail.isNotEmpty()) addView(TextView(context).apply {
            text = detail
            setTextColor(fadedColor(colors.text, 0xB0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (percent == null) setPadding(0, dp(6), 0, 0)
        })
    }

    private class UsageBar(context: Context, private val percent: Int, colors: StripColors) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fadedColor(colors.accent, 0x40) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, r, r, track)
            if (percent == 0) return
            box.right = maxOf(height.toFloat(), width * percent / 100f)
            canvas.drawRoundRect(box, r, r, fill)
        }
    }

    private fun tile(icon: Int, label: String, colors: StripColors, onTap: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val shape = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), fadedColor(colors.text, 0x50))
        }
        background = RippleDrawable(ColorStateList.valueOf(fadedColor(colors.accent, 0x40)), shape, rounded(colors.text, 16))
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onTap() }
        addView(chatIcon(context, icon, colors.text, 28))
        addView(TextView(context).apply {
            text = label
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            setPadding(0, dp(8), 0, 0)
        })
    }

    // A card row that leads to a sub page: an icon in a circle, the name over its value, and ›.
    private fun navRow(icon: Int?, title: String, value: String, colors: StripColors, onTap: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(fadedColor(colors.accent, 0x40)), rounded(colors.selectedBackground, 16), null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        icon?.let {
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(fadedColor(colors.text, 0x1C))
                }
                addView(chatIcon(context, it, colors.text, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = value
                setTextColor(fadedColor(colors.text, 0xA0))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (icon == null) marginStart = dp(4) })
        addView(chatIcon(context, R.drawable.chat_chevron, fadedColor(colors.text, 0xA0), 20))
    }

    // One choice: a tick on the one in use, the name, and under it what it does.
    private fun choiceRow(label: String, description: String?, ticked: Boolean, colors: StripColors, onTap: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(fadedColor(colors.accent, 0x40)), null, rounded(colors.text, 12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = label
                    setTextColor(if (ticked) colors.accent else colors.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                })
                description?.let { more ->
                    addView(TextView(context).apply {
                        text = more
                        setTextColor(fadedColor(colors.text, 0x99))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    })
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
            addView(chatIcon(context, R.drawable.chat_check, colors.accent, 22).apply {
                visibility = if (ticked) View.VISIBLE else View.INVISIBLE
            })
        }

    private fun note(text: String, colors: StripColors) = TextView(context).apply {
        this.text = text
        setTextColor(fadedColor(colors.text, 0xA0))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(8), dp(4), dp(8), dp(8))
    }

    private fun iconButton(icon: Int, description: String, color: Int, onTap: () -> Unit) = FrameLayout(context).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(fadedColor(color, 0x30)), null, null)
        setOnClickListener { onTap() }
        addView(chatIcon(context, icon, color, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
    }

    private fun rowParams(gap: Int = 8) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(gap) }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun modeLabel(value: String) = chatModeName(value)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** The Model row's value: the picked model's name, else what the line under the title says. */
internal fun modelLabel(state: ChatState): String =
    state.models.firstOrNull { it.value == state.model }?.name ?: state.line.model.ifEmpty { "Default" }
