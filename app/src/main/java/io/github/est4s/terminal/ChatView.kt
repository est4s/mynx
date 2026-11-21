package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.est4s.terminal.core.ArtifactAction
import io.github.est4s.terminal.core.ArtifactKind
import io.github.est4s.terminal.core.CLAUDE_INSTALL_COMMAND
import io.github.est4s.terminal.core.CLAUDE_SIGN_IN_COMMAND
import io.github.est4s.terminal.core.ChatAccess
import io.github.est4s.terminal.core.ChatArtifact
import io.github.est4s.terminal.core.ChatAttachment
import io.github.est4s.terminal.core.ChatController
import io.github.est4s.terminal.core.ChatItem
import io.github.est4s.terminal.core.ChatState
import io.github.est4s.terminal.core.ChatStatusLine
import io.github.est4s.terminal.core.ChatTabHold
import io.github.est4s.terminal.core.FileKind
import io.github.est4s.terminal.core.OpenWith
import io.github.est4s.terminal.core.ChangedFileRow
import io.github.est4s.terminal.core.SyntaxKind
import io.github.est4s.terminal.core.changedFileRows
import io.github.est4s.terminal.core.fileKindOf
import io.github.est4s.terminal.core.syntaxColor
import io.github.est4s.terminal.core.IncrementalMarkdown
import io.github.est4s.terminal.core.MdRow
import io.github.est4s.terminal.core.NoticeKind
import io.github.est4s.terminal.core.PermissionOptionKind
import io.github.est4s.terminal.core.QuestionPicks
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.ToolCallInfo
import io.github.est4s.terminal.core.ToolStatus
import io.github.est4s.terminal.core.chatArtifactActions
import io.github.est4s.terminal.core.chatArtifactLine
import io.github.est4s.terminal.core.chatArtifactNote
import io.github.est4s.terminal.core.chatArtifactRunCommand
import io.github.est4s.terminal.core.chatArtifactShortcutCommand
import io.github.est4s.terminal.core.chatFilePath
import io.github.est4s.terminal.core.chatImageFile
import io.github.est4s.terminal.core.chatFit
import io.github.est4s.terminal.core.chatOutcomeText
import io.github.est4s.terminal.core.chatRenderDelay
import io.github.est4s.terminal.core.chatReviewCut
import io.github.est4s.terminal.core.chatRowChanges
import io.github.est4s.terminal.core.chatToolBody
import io.github.est4s.terminal.core.chatToolStatus
import io.github.est4s.terminal.core.mdLinkAllowed
import io.github.est4s.terminal.core.mdRows
import io.github.est4s.terminal.core.runThenShell
import io.github.est4s.terminal.core.splitAttachments
import io.github.est4s.terminal.core.usageRing
import java.io.File
import kotlin.math.abs

private const val CHAT_TITLE = "claude code"
private const val NEW_CHAT_TITLE = "New chat"
// An image card's picture, at most this high.
private const val IMAGE_PREVIEW_DP = 240
private val RIPPLE_ON_FILL: Int get() = Pixel.RIPPLE_ON_CYAN
// What the rows keep of the height when it's short (see chatFit).
private const val CONVERSATION_MIN_DP = 48

// The box is outside it, so nothing here needs scrolling to when it takes the focus:
// a tall selectable row that did would make the rows jump.
private class ChatScroll(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

// A card's buttons side by side, wrapping onto the next line; one too wide for a
// line takes a line of its own, at full width (its text wraps, then ellipsizes).
private class ButtonFlow(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun shouldDelayChildPressedState(): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED
        val width = if (unbounded) Int.MAX_VALUE / 2 else View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val loose = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var x = 0
        var y = 0
        var lineHeight = 0
        var used = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(loose, loose)
            if (child.measuredWidth > width) {
                child.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), View.MeasureSpec.EXACTLY), loose)
            }
            if (x > 0 && x + child.measuredWidth > width) {
                y += lineHeight + gap
                x = 0
                lineHeight = 0
            }
            x += child.measuredWidth + gap
            used = maxOf(used, x - gap)
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        setMeasuredDimension(
            View.resolveSize(used + paddingLeft + paddingRight, widthMeasureSpec),
            View.resolveSize(y + lineHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > width) {
                y += lineHeight + gap
                x = 0
                lineHeight = 0
            }
            val left = paddingLeft + x
            val top = paddingTop + y
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += child.measuredWidth + gap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}

/**
 * Touch mode's Chat, in the pixel style ([Pixel]): the hub's shown
 * [ChatController] as rows over the terminal, drawn from the [ChatState]s the activity passes to [show].
 * Rows only change in place (see [chatRowChanges]), so streaming touches
 * one row. [back] is ←; ⋮ has To tab (the terminal is the drawer's);
 * [openTab] runs a command in a new tab (signing in, installing, an
 * artifact card's Run). A card's Open is [openApp] (a web app's name),
 * Share [shareFile], Copy [copyFile] (a Markdown file's text to the
 * clipboard) and Install [installApk] (Debian paths); a null
 * [installApk] means this build can't install apps. [attach] picks files
 * for + (camera, photos, files) and hands them back copied into Debian;
 * null hides the tiles. [thumbnails] draws images' chips with the picture
 * (null: the photo icon), and image cards' previews. [viewImage] shows a
 * picture full screen (a Debian path): tapping an image's chip or card,
 * or the card's View. [openFile] opens any other file (a Debian path) in
 * mynx's viewer or another app, as [OpenWith] says (null: the kind's
 * setting): another file's chip, a Markdown card's View, an audio or
 * video card's Play, a changed files card's rows, and file paths in
 * replies (underlined when [hostFile], a Debian path's file on the phone,
 * says they're there). [diffs] gives a changed file's `+N −M` and opens
 * its diff view (rows with no diff, or no [diffs]: [openFile]). [clock] is the time
 * Usage's resets are told from.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ChatView(
    context: Context,
    private val controller: () -> ChatController?,
    private val back: () -> Unit,
    private val openTab: (List<String>) -> Unit,
    private val openApp: (String) -> Unit,
    private val shareFile: (String) -> Unit,
    private val copyFile: (String) -> Unit,
    private val installApk: ((String) -> Unit)?,
    attach: ((ChatAttachSource, (List<ChatAttachment>) -> Unit) -> Unit)? = null,
    private val dictation: ChatDictation? = null,
    private val thumbnails: ChatThumbnails? = null,
    private val viewImage: ((String) -> Unit)? = null,
    private val openFile: ((path: String, with: OpenWith?) -> Unit)? = null,
    private val hostFile: ((String) -> File?)? = null,
    private val diffs: ChatDiffs? = null,
    clock: () -> Long = System::currentTimeMillis,
) : FrameLayout(context) {
    private class Cell(val view: View, val bind: (ChatItem) -> Unit)

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    private val scroll = ChatScroll(context).apply {
        addView(rows)
        Pixel.scrollbar(this)
    }
    private val backButton = iconButton(PixelIcon.BACK, Pixel.CYAN, "Back") { back() }
    private val titleText = headerText("New chat").apply {
        typeface = Pixel.label(context, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        ellipsize = TextUtils.TruncateAt.END
    }
    // "claude code · " and the permission mode, under the title.
    private val subtitleText = headerText("").apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val titleColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(titleText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(subtitleText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private var drawnLine: ChatStatusLine? = null
    // The context window's use; a tap opens Usage.
    private val ring = UsageRing(context).apply {
        contentDescription = "Usage"
        isClickable = true
        isFocusable = true
        setOnClickListener { sheets.open(ChatSheetPage.USAGE) }
    }
    // To tab: Model is +'s, the terminal the drawer's.
    private val moreButton = iconButton(PixelIcon.MORE, Pixel.TEXT, "More") { showMore() }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        // A 2 dp line under it, as on the chat home's drawer.
        background = LayerDrawable(arrayOf(ColorDrawable(Pixel.LINE), ColorDrawable(Pixel.BACKGROUND))).apply {
            setLayerInset(1, 0, 0, 0, dp(2))
        }
        setPadding(dp(4), 0, dp(4), dp(2))
        addView(backButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(titleColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(ring, LinearLayout.LayoutParams(dp(56), dp(52)))
        addView(moreButton, LinearLayout.LayoutParams(dp(44), dp(52)))
    }
    private val statusText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(16), dp(4), dp(16), dp(4))
        visibility = View.GONE
    }
    private val gateText = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }
    // Styled as card buttons when the colours come.
    private val gateButton = TextView(context)
    private val gateSecond = TextView(context).apply { visibility = View.GONE }
    private val gateCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        visibility = View.GONE
        addView(gateText)
        addView(buttonFlow().apply {
            addView(gateButton)
            addView(gateSecond)
        }, buttonFlowParams())
    }
    private val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        maxLines = 6
        hint = "Reply to Claude…"
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = null
        highlightColor = (Pixel.CYAN and 0x00FFFFFF) or 0x55000000
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(Pixel.CYAN)
                setSize(dp(2), 0)
            }
        }
        setPadding(dp(8), dp(12), dp(4), dp(12))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderSend()
        })
    }
    // + opens Add context: files, Permission and Model.
    private val plusButton = iconButton(PixelIcon.PLUS, Pixel.TEXT, "Add") { sheets.open(ChatSheetPage.ADD) }
    // Send; while a turn runs and there's nothing to send, Stop.
    private val sendButton = iconButton(PixelIcon.SEND, Pixel.BACKGROUND, "Send") {
        if (showsStop) controller()?.cancel() else sendInput()
    }
    private var showsStop = false
    private var canSend = true
    // Dictation into the box; accent while it listens, hidden when the phone can't.
    private val micButton = iconButton(PixelIcon.MIC, Pixel.TEXT, "Dictate") { toggleDictation() }.apply {
        visibility = if (dictation?.available == true) View.VISIBLE else View.GONE
    }
    private var listening = false
    // The screen's main action: the cyan box with the logo's shadow, as on the chat home.
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.CYAN, shadow = true)
        // The border inside, and the shadow's room right and bottom.
        setPadding(dp(6), dp(6), dp(6) + dp(Pixel.SHADOW_DP), dp(6) + dp(Pixel.SHADOW_DP))
        addView(TextView(context).apply {
            text = ">"
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Pixel.CYAN)
            setPadding(dp(8), dp(12), 0, dp(12))
        })
        addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(plusButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(micButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)))
    }
    // What + added for the next prompt, each with ×.
    private val attachments = ArrayList<ChatAttachment>()
    private val chips = ButtonFlow(context, dp(6)).apply { visibility = View.GONE }
    private val inputRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // The shadow sticks out 4 dp: keep the box's own edge 16 dp from the screen's.
        setPadding(dp(16), dp(4), dp(16) - dp(Pixel.SHADOW_DP), dp(10))
        addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        addView(pill, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val sheets = ChatSheetLayer(
        context,
        state = { latest },
        chat = controller,
        attach = attach?.let { pick -> { source: ChatAttachSource -> pick(source) { added -> addAttachments(added) } } },
        clock = clock,
    )

    private val cells = ArrayList<Cell>()
    private var drawn: List<ChatItem> = emptyList()
    // Queued prompts: views in [rows] after the cells, drawn again whenever they change.
    private var drawnQueued: List<ChatItem.User> = emptyList()
    private var latest: ChatState? = null
    private var drawnChat: Any? = null
    private var renderPending = false
    private var lastRender = 0L
    private val renderTask = Runnable {
        renderPending = false
        lastRender = SystemClock.uptimeMillis()
        render()
    }
    // Keeps to the bottom only while the user is there; scrolling up stops it.
    private var stick = true
    // Whether these rows are wanted; onMeasure decides whether they fit.
    private var statusWanted = false
    private var gateWanted = false
    // Outside the cells, by item id: rows drawn again (new colours) keep them.
    private val expandedTools = HashSet<String>()
    private val expandedReviews = HashSet<String>()
    private val questionPicks = HashMap<String, QuestionPicks>()

    var colors: StripColors? = null
        set(value) {
            // Set again each time the app comes on screen: most times nothing changed.
            if (value == field) return
            field = value
            value ?: return
            setBackgroundColor(value.background)
            titleText.setTextColor(value.text)
            statusText.setTextColor(value.accent)
            subtitleText.setTextColor(Pixel.DIM)
            drawnLine = null
            gateText.setTextColor(value.text)
            styleButton(gateButton, true, value)
            styleButton(gateSecond, false, value)
            input.setTextColor(value.text)
            input.setHintTextColor(Pixel.DIM)
            renderMic()
            sheets.colors = value
            renderChips()
            renderSend()
            // The rows hold the old colours: draw them again.
            cells.clear()
            rows.removeAllViews()
            drawn = emptyList()
            drawnQueued = emptyList()
            render()
        }

    init {
        // Over the terminal: a touch on an empty part mustn't reach it.
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        column.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(gateCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(sheets, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            stick = rows.height - scrollY - scroll.height <= dp(32)
        }
        val follow = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (stick) scroll.post { if (stick) scrollToEnd() }
        }
        rows.addOnLayoutChangeListener(follow)
        scroll.addOnLayoutChangeListener(follow)
    }

    // With no height the box would lose the keyboard for good: see chatFit.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fitRows(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fitRows(widthSpec: Int, heightSpec: Int) {
        if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED) return
        val natural = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun height(view: View, wanted: Boolean): Int {
            if (!wanted) return 0
            view.measure(widthSpec, natural)
            return view.measuredHeight
        }
        val fit = chatFit(
            available = View.MeasureSpec.getSize(heightSpec),
            input = height(inputRow, true),
            conversation = dp(CONVERSATION_MIN_DP),
            header = height(header, true),
            status = height(statusText, statusWanted),
            gate = height(gateCard, gateWanted),
        )
        header.visibility = if (fit.header) View.VISIBLE else View.GONE
        statusText.visibility = if (statusWanted && fit.status) View.VISIBLE else View.GONE
        gateCard.visibility = if (gateWanted && fit.gate) View.VISIBLE else View.GONE
    }

    // fullScroll() would move the focus out of the box.
    private fun scrollToEnd() {
        scroll.scrollTo(0, (rows.height - scroll.height).coerceAtLeast(0))
    }

    /** Puts the input box in focus, so a hardware keyboard types in it. */
    fun focusInput() {
        input.requestFocus()
    }

    /** Puts [text] in the box with the cursor at its end: the chat home's draft, carried over. */
    fun setDraft(text: String) {
        input.setText(text)
        input.setSelection(input.text.length)
    }

    /** Back with a sheet open: its page before, or the chat. False when none was open. */
    fun sheetBack(): Boolean = sheets.back()

    /** Opens a sheet as + or the ring would (the screenshots open them directly). */
    fun openSheet(page: ChatSheetPage) = sheets.open(page)

    /** Adds files for the next prompt, as + does once they're copied in. */
    fun attachFiles(added: List<ChatAttachment>) = addAttachments(added)

    // A hardware keyboard types in the box, whatever row was tapped last.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed && !input.isFocused) input.requestFocus()
        return super.dispatchKeyEvent(event)
    }

    /**
     * Takes the chat as it stands; drawn soon, with whatever else comes first.
     * Another [chat] than the last one's (the hub's controller) starts the
     * screen over: its rows, what's open in them, and the box.
     */
    fun show(state: ChatState, chat: Any? = null) {
        if (chat !== drawnChat) {
            drawnChat = chat
            cells.clear()
            rows.removeAllViews()
            drawn = emptyList()
            drawnQueued = emptyList()
            expandedTools.clear()
            expandedReviews.clear()
            questionPicks.clear()
            input.setText("")
            attachments.clear()
            renderChips()
            sheets.close()
            stick = true
        }
        latest = state
        if (renderPending) return
        renderPending = true
        postDelayed(renderTask, chatRenderDelay(SystemClock.uptimeMillis(), lastRender))
    }

    private fun render() {
        val state = latest ?: return
        val colors = colors ?: return
        renderRows(state.conversation.items, colors)
        renderQueued(state.conversation.queued, colors)
        renderControls(state, colors)
    }

    private fun renderQueued(queued: List<ChatItem.User>, colors: StripColors) {
        if (queued == drawnQueued && rows.childCount == cells.size + queued.size) return
        rows.removeViews(cells.size, rows.childCount - cells.size)
        queued.forEach { item ->
            val cell = userCell(colors, queued = true)
            cell.bind(item)
            rows.addView(cell.view, cellParams())
        }
        drawnQueued = queued
    }

    private fun renderRows(items: List<ChatItem>, colors: StripColors) {
        val changes = chatRowChanges(drawn, items)
        if (changes.rebuild) {
            cells.clear()
            rows.removeAllViews()
            items.forEach { addCell(it, colors) }
            stick = true
            val ids = items.mapTo(HashSet()) { it.id }
            expandedTools.retainAll(ids)
            expandedReviews.retainAll(ids)
            questionPicks.keys.retainAll(ids)
        } else {
            changes.changed.forEach { i ->
                val cell = cells.getOrNull(i)
                if (cell != null) cell.bind(items[i]) else addCell(items[i], colors)
            }
        }
        drawn = items
    }

    private fun addCell(item: ChatItem, colors: StripColors) {
        val cell = when (item) {
            is ChatItem.User -> userCell(colors)
            is ChatItem.Agent -> agentCell(colors)
            is ChatItem.Tool -> toolCell(colors)
            is ChatItem.Permission -> permissionCell(colors)
            is ChatItem.Question -> questionCell(colors)
            is ChatItem.Plan -> planCell(colors)
            is ChatItem.Notice -> noticeCell(colors)
            is ChatItem.Artifact -> artifactCell(colors)
        }
        cell.bind(item)
        // Before any queued prompts' views, which stay last.
        rows.addView(cell.view, cells.size, cellParams())
        cells.add(cell)
    }

    private fun cellParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        setMargins(dp(16), dp(6), dp(16), dp(6))
    }

    private fun renderControls(state: ChatState, colors: StripColors) {
        val conversation = state.conversation
        val status = when {
            state.access == ChatAccess.CHECKING -> "Checking Claude Code…"
            state.stopping -> "Stopping the chat…"
            conversation.thinking -> "Thinking…"
            conversation.running -> "Working…"
            else -> ""
        }
        statusText.text = status
        titleText.text = state.title ?: NEW_CHAT_TITLE
        if (state.line != drawnLine) {
            drawnLine = state.line
            subtitleText.text = statusLineText(state.line, colors)
        }
        ring.percent = usageRing(state.usage)
        val hadStatus = statusWanted
        val hadGate = gateWanted
        statusWanted = status.isNotEmpty()
        when {
            state.access == ChatAccess.NOT_INSTALLED ->
                showGate("Claude Code isn't installed.", "Install") { openTab(runThenShell(CLAUDE_INSTALL_COMMAND)) }
            state.access == ChatAccess.NOT_SIGNED_IN ->
                showGate("Claude Code needs you to sign in.", "Sign in") { openTab(runThenShell(CLAUDE_SIGN_IN_COMMAND)) }
            state.tabHold == ChatTabHold.ASKING ->
                showGate("Claude is still open in a tab. Take it back? That closes the tab.", "Take back", "Cancel", { controller()?.keepInTab() }) {
                    controller()?.takeBack()
                }
            state.tabHold == ChatTabHold.TAKING_BACK -> showGate("Closing the tab…", null) { }
            state.canRestart ->
                showGate("The chat has stopped.", "Restart") { controller()?.restart() }
            else -> gateWanted = false
        }
        if (statusWanted != hadStatus || gateWanted != hadGate) requestLayout()
        canSend = state.access == ChatAccess.READY && !state.stopping && state.tabHold != ChatTabHold.TAKING_BACK
        renderSend()
        sheets.update()
    }

    // Stop while a turn runs and nothing's typed or added; else Send, dimmed when it can't go.
    private fun renderSend() {
        val colors = colors ?: return
        val running = latest?.conversation?.running == true
        val empty = input.text.isNullOrBlank() && attachments.isEmpty()
        val stop = running && empty
        showsStop = stop
        sendButton.contentDescription = if (stop) "Stop" else "Send"
        val usable = stop || (canSend && !empty)
        sendButton.isEnabled = stop || canSend
        // Filled when it does something now (pink for Stop); an empty square when it can't.
        val fill = when {
            stop -> colors.mark
            usable -> colors.accent
            else -> null
        }
        sendButton.icon(if (stop) PixelIcon.STOP else PixelIcon.SEND, if (fill != null) colors.background else Pixel.DIM)
        sendButton.background = if (fill != null) {
            Pixel.box(context, fill = fill, border = 0, ripple = RIPPLE_ON_FILL)
        } else {
            Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE)
        }
    }

    // The files + added, each a chip with ×.
    private fun renderChips() {
        val colors = colors ?: return
        chips.removeAllViews()
        attachments.toList().forEach { file ->
            chips.addView(attachmentChip(file.path, file.name, file.image, colors) {
                attachments.remove(file)
                renderChips()
                renderSend()
            })
        }
        chips.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The mic: starts dictating into the box after what's typed, or stops. */
    fun toggleDictation() {
        val voice = dictation ?: return
        if (listening) {
            voice.stop()
            return
        }
        val typed = input.text.toString().trimEnd()
        val before = if (typed.isEmpty()) "" else "$typed "
        voice.start(
            text = { heard, _ ->
                input.setText(before + heard)
                input.setSelection(input.text.length)
            },
            listening = { on ->
                listening = on
                renderMic()
            },
        )
        input.requestFocus()
    }

    private fun renderMic() {
        val colors = colors ?: return
        // Listening: ringed in the accent, so it isn't mistaken for Send beside it.
        micButton.background = if (listening) {
            Pixel.box(context, fill = faded(colors.accent, 0x30), border = colors.accent, ripple = Pixel.LINE)
        } else {
            pixelRipple()
        }
        micButton.icon(PixelIcon.MIC, if (listening) colors.accent else colors.text)
        micButton.contentDescription = if (listening) "Stop dictating" else "Dictate"
    }

    private fun addAttachments(added: List<ChatAttachment>) {
        if (added.isEmpty()) return
        attachments += added.filter { new -> attachments.none { it.path == new.path } }
        renderChips()
        renderSend()
        input.requestFocus()
    }

    // A file's name with its icon, or an image's thumbnail once it's decoded; [remove] adds × (the box's chips), none in a sent bubble.
    private fun attachmentChip(path: String, name: String, image: Boolean, colors: StripColors, remove: (() -> Unit)? = null) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE)
            setPadding(dp(10), 0, if (remove == null) dp(12) else 0, 0)
            minimumHeight = dp(36)
            val chip = this
            if (image && viewImage != null) {
                setOnClickListener { viewImage?.invoke(path) }
                contentDescription = "View $name"
            } else if (!image && openFile != null) {
                setOnClickListener { openFile?.invoke(path, null) }
                contentDescription = "Open $name"
            }
            val kindIcon = when {
                image -> PixelIcon.IMAGE
                else -> when (fileKindOf(name)) {
                    FileKind.AUDIO -> PixelIcon.NOTE
                    FileKind.VIDEO -> PixelIcon.PLAY
                    else -> PixelIcon.FILE
                }
            }
            val icon = ImageView(context).apply {
                setImageDrawable(Pixel.icon(context, kindIcon, Pixel.DIM, 16))
            }
            addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)))
            if (image) thumbnails?.load(path) { bitmap ->
                icon.setImageBitmap(bitmap)
                icon.layoutParams = LinearLayout.LayoutParams(dp(CHAT_THUMBNAIL_DP), dp(CHAT_THUMBNAIL_DP))
                chip.setPadding(dp(4), 0, chip.paddingRight, 0)
            }
            addView(plainText(colors.text, 13f).apply {
                text = name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                maxWidth = dp(200)
                setPadding(dp(6), 0, 0, 0)
            })
            remove?.let { onRemove ->
                addView(iconButton(PixelIcon.CLOSE, Pixel.DIM, "Remove $name", 14) { onRemove() }, LinearLayout.LayoutParams(dp(36), dp(36)))
            }
        }

    // The mode stands out when it isn't the default: edits happen without asking, or none at all.
    private fun statusLineText(line: ChatStatusLine, colors: StripColors): CharSequence {
        val shown = "$CHAT_TITLE · ${line.mode}"
        if (!line.highlighted) return shown
        val text = SpannableString(shown)
        val start = shown.length - line.mode.length
        text.setSpan(ForegroundColorSpan(Pixel.PINK_TEXT), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun showGate(text: String, action: String?, second: String? = null, onSecond: () -> Unit = {}, onTap: () -> Unit) {
        gateText.text = text
        gateButton.text = action.orEmpty()
        gateButton.visibility = if (action == null) View.GONE else View.VISIBLE
        gateButton.setOnClickListener { onTap() }
        gateSecond.text = second.orEmpty()
        gateSecond.visibility = if (second == null) View.GONE else View.VISIBLE
        gateSecond.setOnClickListener { onSecond() }
        gateWanted = true
    }

    private fun showMore() {
        // Greyed when it can't go, but still tappable, to say why.
        val blocked = controller()?.state?.handOffBlock != null
        pixelMenu(moreButton, listOf(PixelMenuEntry("Open in a tab", greyed = blocked) { handOff() }))
    }

    // Says why when it can't go now (a turn running, nothing to resume yet).
    private fun handOff() {
        val chat = controller() ?: return
        val block = chat.state.handOffBlock
        if (block != null) {
            Toast.makeText(context, block, Toast.LENGTH_SHORT).show()
            return
        }
        chat.handOff()
    }

    private fun sendInput() {
        val text = input.text.toString()
        if (controller()?.send(text, attachments.toList()) == true) {
            input.setText("")
            attachments.clear()
            renderChips()
            renderSend()
            stick = true
        }
    }

    // A queued prompt is dimmed under a "Queued" label until the agent takes it.
    private fun userCell(colors: StripColors, queued: Boolean = false): Cell {
        val bubble = selectableText(colors.text, 15f).apply {
            background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            if (queued) alpha = 0.55f
        }
        // The files sent with it, a chip each, over the text.
        val files = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            if (queued) alpha = 0.55f
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(48), 0, 0, 0)
            if (queued) addView(plainText(Pixel.DIM, 12f).apply {
                text = "Queued"
                setPadding(0, 0, dp(4), dp(2))
            })
            addView(files)
            addView(bubble)
        }
        return Cell(row) { item ->
            val (text, paths) = splitAttachments((item as ChatItem.User).text)
            bubble.text = text
            bubble.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            files.removeAllViews()
            paths.forEach { path ->
                val name = path.substringAfterLast('/')
                files.addView(attachmentChip(path, name, chatImageFile(name), colors), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                })
            }
        }
    }

    // Rows that didn't change keep their views, and any selection in them.
    private fun agentCell(colors: StripColors): Cell {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val parser = IncrementalMarkdown()
        val markdown = MarkdownViews(
            context, colors, { color, sp, links -> selectableText(color, sp, links) }, ::openLink,
            fileAt = ::codeFile, openFile = { path -> openFile?.invoke(path, null) },
        )
        val drawnCells = ArrayList<MarkdownViews.Cell>()
        var drawnRows: List<MdRow> = emptyList()
        // The reply as Claude wrote it (its Markdown), under the rows.
        var source = ""
        val copy = copyButton(colors) { source }
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(column)
            addView(copy, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        return Cell(outer) { item ->
            source = (item as ChatItem.Agent).text
            copy.visibility = if (source.isBlank()) View.GONE else View.VISIBLE
            val next = mdRows(parser.update(source))
            next.forEachIndexed { i, row ->
                val before = next.getOrNull(i - 1)
                val cell = drawnCells.getOrNull(i)
                when {
                    cell == null -> {
                        val made = markdown.cell(row)
                        made.bind(row)
                        drawnCells.add(made)
                        column.addView(made.view, markdown.params(row, before))
                    }
                    cell.type != row.javaClass -> {
                        val made = markdown.cell(row)
                        made.bind(row)
                        drawnCells[i] = made
                        column.removeViewAt(i)
                        column.addView(made.view, i, markdown.params(row, before))
                    }
                    row != drawnRows[i] -> {
                        cell.bind(row)
                        cell.view.layoutParams = markdown.params(row, before)
                    }
                }
            }
            while (drawnCells.size > next.size) {
                drawnCells.removeAt(drawnCells.lastIndex)
                column.removeViewAt(column.childCount - 1)
            }
            drawnRows = next
        }
    }

    // A file that code in a reply names, if it's there: a stat on the main thread, only for code that looks like a path.
    private fun codeFile(text: String): String? {
        val chat = controller() ?: return null
        val host = hostFile ?: return null
        return chatFilePath(text, chat.cwd, chat.home)?.takeIf { host(it)?.isFile == true }
    }

    private fun openLink(url: String) {
        if (!mdLinkAllowed(url)) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open that link.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toolCell(colors: StripColors): Cell {
        var call: ToolCallInfo? = null
        val title = plainText(Pixel.DIM, 13f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val status = plainText(colors.accent, 12f)
        val body = selectableText(colors.text, 12f).apply {
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        fun refresh() {
            val current = call ?: return
            val expanded = current.id in expandedTools
            title.text = current.title
            status.text = "[" + chatToolStatus(current) + "]" + if (expanded) "  ▾" else "  ▸"
            status.setTextColor(if (current.status == ToolStatus.FAILED) Pixel.PINK_TEXT else colors.accent)
            title.maxLines = if (expanded) Int.MAX_VALUE else 2
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) body.text = chatToolBody(current)
        }
        val card = cardColumn(colors).apply {
            background = Pixel.box(context, fill = colors.background, border = Pixel.LINE, ripple = Pixel.LINE)
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(status)
            setOnClickListener {
                val id = call?.id
                if (id != null && !expandedTools.remove(id)) expandedTools.add(id)
                refresh()
            }
        }
        card.addView(top)
        card.addView(body)
        return Cell(card) { item ->
            call = (item as ChatItem.Tool).call
            refresh()
        }
    }

    private fun permissionCell(colors: StripColors): Cell {
        val card = cardColumn(colors)
        fun draw(request: ChatItem.Permission) {
            card.removeAllViews()
            styleCard(card, main = request.outcome == null)
            card.addView(cardLabel("Permission needed · " + request.call.name))
            val cut = chatReviewCut(request.review)
            val whole = request.id in expandedReviews
            card.addView(selectableText(colors.text, 13f).apply {
                text = if (whole) request.review else cut.text
                setPadding(0, dp(4), 0, dp(2))
            })
            if (cut.hidden > 0) {
                val label = if (whole) "Show less" else "Show all (${cut.hidden} more characters)"
                card.addView(linkButton(label, colors) {
                    if (!expandedReviews.remove(request.id)) expandedReviews.add(request.id)
                    draw(request)
                })
            }
            val outcome = request.outcome
            if (outcome != null) {
                card.addView(answeredText(chatOutcomeText(outcome), colors))
            } else {
                fun refuses(kind: PermissionOptionKind) = kind == PermissionOptionKind.REJECT_ONCE || kind == PermissionOptionKind.REJECT_ALWAYS
                // Filled: allowing once, else the first that allows at all.
                val primary = request.options.firstOrNull { it.kind == PermissionOptionKind.ALLOW_ONCE }
                    ?: request.options.firstOrNull { !refuses(it.kind) }
                val flow = buttonFlow()
                // Once-only answers first, so Allow and Reject sit side by side; the long Always below.
                fun always(kind: PermissionOptionKind) = kind == PermissionOptionKind.ALLOW_ALWAYS || kind == PermissionOptionKind.REJECT_ALWAYS
                request.options.sortedBy { always(it.kind) }.forEach { option ->
                    val tone = if (refuses(option.kind)) colors.mark else colors.accent
                    flow.addView(cardButton(option.name, option === primary, colors, tone) {
                        controller()?.answerPermission(request.requestId, option.id)
                    })
                }
                card.addView(flow, buttonFlowParams())
            }
        }
        return Cell(card) { item -> draw(item as ChatItem.Permission) }
    }

    private fun questionCell(colors: StripColors): Cell {
        val card = cardColumn(colors)
        fun draw(request: ChatItem.Question) {
            card.removeAllViews()
            styleCard(card, main = request.outcome == null)
            val current = questionPicks.getOrPut(request.id) { QuestionPicks(request.questions) }
            request.questions.forEachIndexed { q, question ->
                question.header?.let { head ->
                    card.addView(cardLabel(head).apply {
                        if (q > 0) setPadding(0, dp(10), 0, 0)
                    })
                }
                card.addView(plainText(colors.text, 15f).apply {
                    text = question.question
                    setPadding(0, if (q > 0 && question.header == null) dp(10) else dp(2), 0, dp(2))
                })
                if (request.outcome == null) {
                    question.options.forEachIndexed { o, option ->
                        val mark = when {
                            current.isPicked(q, o) -> if (question.multiSelect) "☑" else "●"
                            else -> if (question.multiSelect) "☐" else "○"
                        }
                        card.addView(optionRow(mark, current.isPicked(q, o), option.label, option.description, colors) {
                            current.toggle(q, o)
                            if (current.answersOnTap) {
                                controller()?.answerQuestion(request.requestId, current.answers())
                            } else {
                                draw(request)
                            }
                        })
                    }
                }
            }
            val outcome = request.outcome
            if (outcome != null) {
                card.addView(answeredText(chatOutcomeText(outcome), colors))
            } else if (!current.answersOnTap) {
                card.addView(buttonFlow().apply {
                    addView(cardButton("Send answer", true, colors) {
                        if (current.complete) controller()?.answerQuestion(request.requestId, current.answers())
                    }.apply { alpha = if (current.complete) 1f else 0.4f })
                }, buttonFlowParams())
            }
        }
        return Cell(card) { item -> draw(item as ChatItem.Question) }
    }

    private fun planCell(colors: StripColors): Cell {
        val card = cardColumn(colors)
        return Cell(card) { item ->
            val request = item as ChatItem.Plan
            card.removeAllViews()
            styleCard(card, main = request.outcome == null)
            card.addView(cardLabel("Plan"))
            card.addView(selectableText(colors.text, 14f).apply {
                text = request.plan
                setPadding(0, dp(4), 0, dp(2))
            })
            request.planFilePath?.let { path -> card.addView(plainText(Pixel.DIM, 12f).apply { text = path }) }
            val outcome = request.outcome
            if (outcome != null) {
                card.addView(answeredText(chatOutcomeText(outcome), colors))
            } else {
                card.addView(buttonFlow().apply {
                    addView(cardButton("Approve", true, colors) {
                        controller()?.answerPlan(request.requestId, true)
                    })
                    addView(cardButton("Keep planning", false, colors, colors.mark) {
                        controller()?.answerPlan(request.requestId, false)
                    })
                }, buttonFlowParams())
            }
        }
    }

    private fun noticeCell(colors: StripColors): Cell {
        val text = selectableText(colors.text, 14f)
        return Cell(text) { item ->
            val notice = item as ChatItem.Notice
            text.text = notice.text
            text.setTextColor(
                when (notice.kind) {
                    NoticeKind.ERROR -> Pixel.PINK_TEXT
                    NoticeKind.SIGN_IN -> colors.accent
                    NoticeKind.INFO -> colors.text
                },
            )
        }
    }

    private fun artifactCell(colors: StripColors): Cell {
        val card = cardColumn(colors)
        return Cell(card) { item ->
            val artifact = (item as ChatItem.Artifact).artifact
            val install = installApk
            card.removeAllViews()
            styleCard(card, main = true)
            if (artifact.kind == ArtifactKind.IMAGE) card.addView(imagePreview(artifact), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(artifactBadge(artifact), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(plainText(colors.text, 17f).apply {
                        text = artifact.label
                        typeface = Pixel.label(context, 700)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(plainText(Pixel.DIM, 12f).apply {
                        text = chatArtifactLine(artifact, controller()?.home ?: "/root")
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            val flow = buttonFlow()
            chatArtifactActions(artifact, canInstall = install != null).forEachIndexed { i, action ->
                flow.addView(cardButton(action.label, i == 0, colors) {
                    when (action) {
                        ArtifactAction.OPEN -> artifact.appName?.let(openApp)
                        ArtifactAction.ADD_TO_HOME -> openTab(chatArtifactShortcutCommand(artifact))
                        ArtifactAction.RUN -> openTab(chatArtifactRunCommand(artifact))
                        ArtifactAction.INSTALL -> install?.invoke(artifact.shareFile)
                        ArtifactAction.COPY -> copyFile(artifact.shareFile)
                        ArtifactAction.VIEW -> if (artifact.kind == ArtifactKind.IMAGE) {
                            viewImage?.invoke(artifact.shareFile)
                        } else {
                            openFile?.invoke(artifact.shareFile, OpenWith.MYNX)
                        }
                        ArtifactAction.PLAY -> openFile?.invoke(artifact.shareFile, OpenWith.MYNX)
                        ArtifactAction.SHARE -> shareFile(artifact.shareFile)
                    }
                })
            }
            if (flow.childCount > 0) card.addView(flow, buttonFlowParams())
            if (artifact.kind == ArtifactKind.CHANGED_FILES) changedFileRows(artifact, controller()?.home ?: "/root").forEach { row ->
                card.addView(changedFileRow(row, colors), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            chatArtifactNote(artifact, canInstall = install != null)?.let { note ->
                card.addView(answeredText(note, colors))
            }
        }
    }

    // A card's square: audio, video and changed files their icon; the rest the label's first two letters.
    private fun artifactBadge(artifact: ChatArtifact): View {
        val icon = when (artifact.kind) {
            ArtifactKind.AUDIO -> PixelIcon.NOTE
            ArtifactKind.VIDEO -> PixelIcon.PLAY
            ArtifactKind.CHANGED_FILES -> PixelIcon.FILE
            else -> null
        }
        if (icon != null) return ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, icon, Pixel.BACKGROUND, 24))
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundColor(Pixel.PINK)
        }
        return TextView(context).apply {
            text = artifact.label.filter { it.isLetterOrDigit() }.take(2).uppercase()
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Pixel.BACKGROUND)
            gravity = Gravity.CENTER
            setBackgroundColor(Pixel.PINK)
        }
    }

    // A changed file: its name, its folder dim under it, its `+N −M` once worked out; a tap opens its diff, else the file.
    private fun changedFileRow(row: ChangedFileRow, colors: StripColors) = LinearLayout(context).apply {
        val rowDiffs = diffs?.takeIf { row.toolIds.isNotEmpty() }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE, ripple = Pixel.LINE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        minimumHeight = dp(48)
        isFocusable = true
        contentDescription = if (rowDiffs != null) "Changes to ${row.name}" else "Open ${row.name}"
        setOnClickListener { if (rowDiffs?.open(row) != true) openFile?.invoke(row.path, null) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(plainText(colors.text, 15f).apply {
                text = row.name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(plainText(Pixel.DIM, 12f).apply {
                text = row.folder
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (rowDiffs != null) {
            val summary = plainText(Pixel.DIM, 13f).apply {
                maxLines = 1
                setPadding(dp(8), 0, 0, 0)
            }
            addView(summary, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            val shown = rowDiffs.summary(row) { text -> summary.text = summarySpans(text, colors) }
            if (shown != null) summary.text = summarySpans(shown, colors)
        }
    }

    // A changed file's `+12 −3`: the numbers green and red, as the terminal's theme has them on the row; "new · …" green.
    private fun summarySpans(summary: String, colors: StripColors): CharSequence {
        val scheme = terminalColorScheme(context).copy(background = colors.selectedBackground)
        val added = syntaxColor(SyntaxKind.ADDED, scheme)
        val text = SpannableString(summary)
        when {
            summary.startsWith("+") -> {
                val space = summary.indexOf(' ').takeIf { it > 0 } ?: summary.length
                text.setSpan(ForegroundColorSpan(added), 0, space, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (space < summary.length) {
                    text.setSpan(ForegroundColorSpan(syntaxColor(SyntaxKind.REMOVED, scheme)), space + 1, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            summary.startsWith("new") -> text.setSpan(ForegroundColorSpan(added), 0, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    // The picture, as large as the card is wide (up to IMAGE_PREVIEW_DP high), once decoded; a tap views it.
    private fun imagePreview(artifact: ChatArtifact) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_START
        adjustViewBounds = true
        maxHeight = dp(IMAGE_PREVIEW_DP)
        visibility = View.GONE
        contentDescription = "View ${artifact.label}"
        setOnClickListener { viewImage?.invoke(artifact.shareFile) }
        val view = this
        thumbnails?.preview(artifact.shareFile, artifact.version) { bitmap ->
            view.setImageBitmap(bitmap)
            view.visibility = View.VISIBLE
        }
    }

    private fun cardColumn(colors: StripColors) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    // A card waiting on the user is the screen's main action: cyan, with the shadow
    // (and room for it right and bottom); once answered, it's outlined like the rest.
    private fun styleCard(card: LinearLayout, main: Boolean) {
        val shadow = if (main) dp(Pixel.SHADOW_DP) else 0
        card.background = Pixel.box(context, fill = Pixel.CARD, border = if (main) Pixel.CYAN else Pixel.LINE, shadow = main)
        card.setPadding(dp(14), dp(12), dp(14) + shadow, dp(12) + shadow)
    }

    // A card's heading: "PERMISSION NEEDED · BASH", "PLAN", as the chat home's sections.
    private fun cardLabel(text: String) = sectionLabel(context, text).apply { setPadding(0, 0, 0, dp(4)) }

    private fun plainText(color: Int, sp: Float) = TextView(context).apply {
        typeface = Pixel.text(context)
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    // Selectable text takes the focus, which leaves the keyboard typing nowhere:
    // it goes back to the box when a tap ends, or the selection does.
    private fun selectableText(color: Int, sp: Float, links: Boolean = false): TextView = plainText(color, sp).also { row ->
        var downX = 0f
        var downY = 0f
        row.setTextIsSelectable(true)
        row.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    val tap = abs(event.x - downX) <= slop && abs(event.y - downY) <= slop &&
                        event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                    if (links && tap && !row.hasSelection()) linkAt(row, event)?.onClick(row)
                    focusBoxAfter(row)
                }
                MotionEvent.ACTION_CANCEL -> focusBoxAfter(row)
            }
            false
        }
        row.setCustomSelectionActionModeCallback(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) = focusBoxAfter(row)
        })
    }

    private fun linkAt(row: TextView, event: MotionEvent): MdLink? {
        val layout = row.layout ?: return null
        val x = event.x - row.totalPaddingLeft + row.scrollX
        val line = layout.getLineForVertical((event.y - row.totalPaddingTop + row.scrollY).toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        val text = row.text as? Spanned ?: return null
        return text.getSpans(offset, offset, MdLink::class.java).firstOrNull()
    }

    // Posted: the touch hasn't given the row the focus yet, nor ended the selection.
    private fun focusBoxAfter(row: TextView) {
        row.post { if (row.isFocused && !row.hasSelection()) input.requestFocus() }
    }

    private fun answeredText(text: String, @Suppress("UNUSED_PARAMETER") colors: StripColors) = plainText(Pixel.DIM, 13f).apply {
        this.text = text
        setPadding(0, dp(6), 0, 0)
    }

    private fun buttonFlow() = ButtonFlow(context, dp(8))

    private fun buttonFlowParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }

    // The primary action is filled with [tone]; the others are outlined in it.
    private fun cardButton(label: String, primary: Boolean, colors: StripColors, tone: Int = colors.accent, onTap: () -> Unit) =
        TextView(context).apply {
            text = label
            styleButton(this, primary, colors, tone)
            setOnClickListener { onTap() }
        }

    // Square, in Pixelify Sans: the primary one filled with [tone], the others outlined, their text in [tone]
    // when it's a warning (pink), else plain.
    private fun styleButton(button: TextView, primary: Boolean, colors: StripColors, tone: Int = colors.accent) {
        button.typeface = Pixel.label(context, 600)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        button.setTextColor(
            when {
                primary -> colors.background
                tone == colors.accent -> colors.text
                else -> Pixel.PINK_TEXT
            },
        )
        button.gravity = Gravity.CENTER
        button.minHeight = dp(48)
        button.minWidth = dp(72)
        button.maxLines = 2
        button.ellipsize = TextUtils.TruncateAt.END
        button.setPadding(dp(16), dp(6), dp(16), dp(6))
        button.isFocusable = true
        button.background = if (primary) {
            Pixel.box(context, fill = tone, border = 0, ripple = RIPPLE_ON_FILL)
        } else {
            Pixel.box(context, fill = colors.selectedBackground, border = Pixel.LINE, ripple = Pixel.LINE)
        }
    }

    // Copy: [text]'s at the time of the tap, on the clipboard; it says Copied for a moment.
    private fun copyButton(colors: StripColors, text: () -> String) = linkButton("Copy", colors) { }.apply {
        setTextColor(Pixel.DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minHeight = dp(40)
        setPadding(dp(2), 0, dp(8), 0)
        setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("reply", text()))
            this.text = "Copied"
            postDelayed({ this.text = "Copy" }, 1500L)
        }
    }

    // A quiet action inside a card's text (Show all): no outline, the same touch size.
    private fun linkButton(label: String, colors: StripColors, onTap: () -> Unit) = plainText(colors.accent, 14f).apply {
        typeface = Pixel.label(context, 600)
        text = label
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setPadding(0, 0, 0, 0)
        background = RippleDrawable(ColorStateList.valueOf(faded(colors.accent, 0x40)), null, ColorDrawable(colors.text))
        isFocusable = true
        setOnClickListener { onTap() }
    }

    // One answer to a question: its mark, the label, and under it the description, smaller and dimmer.
    private fun optionRow(mark: String, picked: Boolean, label: String, description: String?, colors: StripColors, onTap: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(4), dp(6), dp(8), dp(6))
            background = pixelRipple()
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            addView(plainText(if (picked) colors.accent else Pixel.DIM, 18f).apply {
                text = mark
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(plainText(colors.text, 15f).apply { text = label })
                description?.let { more ->
                    addView(plainText(Pixel.DIM, 12f).apply {
                        text = more
                        setPadding(0, dp(1), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

    // A palette colour with another opacity.
    private fun faded(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun headerText(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        maxLines = 1
    }

    // A block icon in a touch area as big as its layout params, pressed with the pixel ripple.
    private fun iconButton(icon: PixelIcon, color: Int, description: String, sizeDp: Int = 20, onTap: () -> Unit) = FrameLayout(context).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = pixelRipple()
        setOnClickListener { onTap() }
        addView(ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, icon, color, sizeDp))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
    }

    private fun FrameLayout.icon(icon: PixelIcon, color: Int) {
        val image = getChildAt(0) as? ImageView ?: return
        image.setImageDrawable(Pixel.icon(context, icon, color, Math.round(image.layoutParams.width / resources.displayMetrics.density)))
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}
