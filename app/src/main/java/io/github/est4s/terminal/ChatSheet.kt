package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
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
enum class ChatSheetPage { ADD, PERMISSION, DEFAULT_MODE, MODEL, DEFAULT_MODEL, USAGE }

/** One of Chat's block icons. */
internal fun chatIcon(context: Context, icon: PixelIcon, color: Int, sizeDp: Int) = ImageView(context).apply {
    setImageDrawable(Pixel.icon(context, icon, color, sizeDp))
    scaleType = ImageView.ScaleType.FIT_CENTER
    val size = Pixel.dp(context, sizeDp)
    layoutParams = LinearLayout.LayoutParams(size, size)
}

/**
 * The header's context use, as in the pixel mock-up: a square meter filled
 * as far as [percent] in cyan, with the number under it (an empty meter
 * when it isn't known yet). The view keeps its name from the ring it was.
 */
class UsageRing(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val frame = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Pixel.LINE
    }
    private val fill = Paint().apply { color = Pixel.CYAN }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pixel.DIM
        textSize = 11 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Pixel.text(context)
    }

    var percent: Int? = null
        set(value) {
            if (value == field) return
            field = value
            contentDescription = value?.let { "Context $it% used" } ?: "Usage"
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = 36 * density
        val h = 12 * density
        val shown = percent
        val text = label.fontMetrics
        val textHeight = text.descent - text.ascent
        val gap = 3 * density
        val top = ((height - h - (if (shown != null) gap + textHeight else 0f)) / 2).toInt().toFloat()
        val left = ((width - w) / 2).toInt().toFloat()
        val border = frame.strokeWidth
        canvas.drawRect(left + border / 2, top + border / 2, left + w - border / 2, top + h - border / 2, frame)
        shown ?: return
        // A sliver even at 0%, so a known value looks different from an unknown one.
        val inner = w - 2 * border - 2 * density
        val filled = maxOf(density, inner * shown.coerceIn(0, 100) / 100f).toInt().toFloat()
        val inside = border + density
        canvas.drawRect(left + inside, top + inside, left + inside + filled, top + h - inside, fill)
        canvas.drawText("$shown%", width / 2f, top + h + gap - text.ascent, label)
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
        setBackgroundColor(Pixel.SCRIM)
        setOnClickListener { close() }
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
    }
    private val contentScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(content)
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
        // Square, on the background, with a cyan line along its top.
        sheet.background = LayerDrawable(arrayOf(ColorDrawable(Pixel.CYAN), ColorDrawable(colors.background))).apply {
            setLayerInset(1, 0, dp(2), 0, 0)
        }
        sheet.removeAllViews()
        sheet.addView(View(context).apply { setBackgroundColor(Pixel.LINE) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })
        content.removeAllViews()
        when (page) {
            ChatSheetPage.ADD -> addPage(now, colors)
            ChatSheetPage.PERMISSION -> permissionPage(now, colors)
            ChatSheetPage.DEFAULT_MODE -> defaultModePage(now, colors)
            ChatSheetPage.MODEL -> modelPage(now, colors)
            ChatSheetPage.DEFAULT_MODEL -> defaultModelPage(now, colors)
            ChatSheetPage.USAGE -> usagePage(now, colors)
        }
        contentScroll.scrollTo(0, 0)
        sheet.addView(contentScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // × on a sheet of its own, ← on a sub page; the title centred over the whole width.
    private fun header(title: String, colors: StripColors, end: View? = null) {
        val bar = FrameLayout(context).apply { minimumHeight = dp(56) }
        val sub = stack.size > 1
        bar.addView(iconButton(if (sub) PixelIcon.BACK else PixelIcon.CLOSE, if (sub) "Back" else "Close", if (sub) Pixel.CYAN else colors.text) {
            if (sub) back() else close()
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(TextView(context).apply {
            text = title
            setTextColor(colors.text)
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
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
                Triple(ChatAttachSource.CAMERA, PixelIcon.CAMERA, "Camera"),
                Triple(ChatAttachSource.PHOTOS, PixelIcon.IMAGE, "Photos"),
                Triple(ChatAttachSource.FILES, PixelIcon.FILE, "Files"),
            ).forEachIndexed { i, (source, icon, label) ->
                tiles.addView(tile(icon, label, colors) {
                    close()
                    pick(source)
                }, LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            content.addView(tiles, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        content.addView(navRow(PixelIcon.BOLT, "Permission", modeLabel(state?.mode ?: "auto"), colors) {
            open(ChatSheetPage.PERMISSION)
        }, rowParams())
        content.addView(navRow(PixelIcon.CHIP, "Model", state?.let(::modelLabel) ?: "Default", colors) {
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
        content.addView(note("For this chat", colors))
        state?.models.orEmpty().forEach { model ->
            content.addView(choiceRow(model.name, model.description, state?.model == model.value, colors) {
                chat()?.setModel(model.value)
                back()
            }, rowParams(0))
        }
        val current = state?.defaultModel ?: "default"
        val name = state?.models.orEmpty().firstOrNull { it.value == current }?.name ?: current
        content.addView(navRow(null, "Default for new chats", name, colors) {
            open(ChatSheetPage.DEFAULT_MODEL)
        }, rowParams().apply { topMargin = dp(12) })
    }

    private fun defaultModelPage(state: ChatState?, colors: StripColors) {
        header("Default for new chats", colors)
        content.addView(note("New chats start with this model; a chat's own pick wins for that chat.", colors))
        val current = state?.defaultModel ?: "default"
        state?.models.orEmpty().forEach { model ->
            content.addView(choiceRow(model.name, model.description, current == model.value, colors) {
                chat()?.setDefaultModel(model.value)
                back()
            }, rowParams(0))
        }
    }

    private fun usagePage(state: ChatState?, colors: StripColors) {
        header("Usage", colors, iconButton(PixelIcon.REFRESH, "Refresh", colors.text) { draw() })
        val usage = state?.usage ?: return
        var section: String? = null
        usageRows(usage, clock(), ZoneId.systemDefault()).forEach { row ->
            if (row.section != section) {
                section = row.section
                content.addView(sectionLabel(context, row.section).apply {
                    setPadding(0, dp(12), 0, dp(8))
                })
            }
            content.addView(usageCard(row.label, row.percent, row.detail, colors), rowParams())
        }
    }

    private fun usageCard(label: String, percent: Int?, detail: String, colors: StripColors) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label
                setTextColor(colors.text)
                typeface = Pixel.label(context, 600)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            percent?.let { p ->
                addView(TextView(context).apply {
                    text = "$p% used"
                    setTextColor(Pixel.DIM)
                    typeface = Pixel.text(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(8), 0, 0, 0)
                })
            }
        })
        percent?.let { p ->
            addView(UsageBar(context, p.coerceIn(0, 100)), LinearLayout.LayoutParams(MATCH_PARENT, dp(14)).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            })
        }
        if (detail.isNotEmpty()) addView(TextView(context).apply {
            text = detail
            setTextColor(Pixel.DIM)
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            if (percent == null) setPadding(0, dp(6), 0, 0)
        })
    }

    // As the header's meter: a 2 dp frame, filled in cyan 2 dp inside it.
    private class UsageBar(context: Context, private val percent: Int) : View(context) {
        private val density = resources.displayMetrics.density
        private val frame = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = Pixel.LINE
        }
        private val fill = Paint().apply { color = Pixel.CYAN }

        override fun onDraw(canvas: Canvas) {
            val b = frame.strokeWidth
            canvas.drawRect(b / 2, b / 2, width - b / 2, height - b / 2, frame)
            if (percent == 0) return
            val inside = b + density
            val filled = ((width - 2 * inside) * percent / 100f).toInt().toFloat()
            canvas.drawRect(inside, inside, inside + filled, height - inside, fill)
        }
    }

    private fun tile(icon: PixelIcon, label: String, colors: StripColors, onTap: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE, ripple = Pixel.LINE)
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onTap() }
        addView(chatIcon(context, icon, Pixel.CYAN, 28))
        addView(TextView(context).apply {
            text = label
            setTextColor(colors.text)
            typeface = Pixel.label(context, 600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            setPadding(0, dp(8), 0, 0)
            gravity = Gravity.CENTER
        })
    }

    // A card row that leads to a sub page: an icon in a square, the name over its value, and ›.
    private fun navRow(icon: PixelIcon?, title: String, value: String, colors: StripColors, onTap: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE, ripple = Pixel.LINE)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        icon?.let {
            addView(FrameLayout(context).apply {
                background = Pixel.box(context, fill = colors.background, border = Pixel.LINE)
                addView(chatIcon(context, it, Pixel.CYAN, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(colors.text)
                typeface = Pixel.label(context, 600)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = value
                setTextColor(Pixel.DIM)
                typeface = Pixel.text(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (icon == null) marginStart = dp(4) })
        addView(chatIcon(context, PixelIcon.CHEVRON, Pixel.DIM, 20))
    }

    // One choice: a tick on the one in use, the name, and under it what it does.
    private fun choiceRow(label: String, description: String?, ticked: Boolean, colors: StripColors, onTap: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pixelRipple()
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = label
                    setTextColor(if (ticked) colors.accent else colors.text)
                    typeface = Pixel.label(context, 600)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                })
                description?.let { more ->
                    addView(TextView(context).apply {
                        text = more
                        setTextColor(Pixel.DIM)
                        typeface = Pixel.text(context)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    })
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
            addView(chatIcon(context, PixelIcon.CHECK, colors.accent, 20).apply {
                visibility = if (ticked) View.VISIBLE else View.INVISIBLE
            })
        }

    private fun note(text: String, colors: StripColors) = TextView(context).apply {
        this.text = text
        setTextColor(Pixel.DIM)
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(8), dp(4), dp(8), dp(8))
    }

    private fun iconButton(icon: PixelIcon, description: String, color: Int, onTap: () -> Unit) = FrameLayout(context).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = pixelRipple()
        setOnClickListener { onTap() }
        addView(chatIcon(context, icon, color, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    }

    private fun rowParams(gap: Int = 8) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(gap) }

    private fun modeLabel(value: String) = chatModeName(value)

    private fun dp(value: Int) = Pixel.dp(context, value)
}

/** The Model row's value: the picked model's name, else what the line under the title says. */
internal fun modelLabel(state: ChatState): String =
    state.models.firstOrNull { it.value == state.model }?.name ?: state.line.model.ifEmpty { "Default" }
