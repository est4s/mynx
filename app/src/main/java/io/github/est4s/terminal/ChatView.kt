package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import io.github.est4s.terminal.core.ArtifactAction
import io.github.est4s.terminal.core.CLAUDE_INSTALL_COMMAND
import io.github.est4s.terminal.core.CLAUDE_SIGN_IN_COMMAND
import io.github.est4s.terminal.core.ChatAccess
import io.github.est4s.terminal.core.ChatController
import io.github.est4s.terminal.core.ChatItem
import io.github.est4s.terminal.core.ChatState
import io.github.est4s.terminal.core.ChatTabHold
import io.github.est4s.terminal.core.ClaudeSessionSummary
import io.github.est4s.terminal.core.IncrementalMarkdown
import io.github.est4s.terminal.core.MdAlign
import io.github.est4s.terminal.core.MdRow
import io.github.est4s.terminal.core.MdStyleKind
import io.github.est4s.terminal.core.MdStyled
import io.github.est4s.terminal.core.NoticeKind
import io.github.est4s.terminal.core.PermissionOptionKind
import io.github.est4s.terminal.core.QuestionPicks
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.ToolCallInfo
import io.github.est4s.terminal.core.chatArtifactActions
import io.github.est4s.terminal.core.chatArtifactLine
import io.github.est4s.terminal.core.chatArtifactNote
import io.github.est4s.terminal.core.chatArtifactRunCommand
import io.github.est4s.terminal.core.chatArtifactShortcutCommand
import io.github.est4s.terminal.core.chatFit
import io.github.est4s.terminal.core.chatOutcomeText
import io.github.est4s.terminal.core.chatRelativeTime
import io.github.est4s.terminal.core.chatRenderDelay
import io.github.est4s.terminal.core.chatReviewCut
import io.github.est4s.terminal.core.chatRowChanges
import io.github.est4s.terminal.core.chatToolBody
import io.github.est4s.terminal.core.chatToolStatus
import io.github.est4s.terminal.core.mdLinkAllowed
import io.github.est4s.terminal.core.mdRows
import io.github.est4s.terminal.core.runThenShell
import kotlin.math.abs
import kotlin.math.ceil

private const val CHAT_TITLE = "Claude Code"
// Root's home, as the chat runs in it (ChatController's default).
private const val CHAT_HOME = "/root"
// What the rows keep of the height when it's short (see chatFit).
private const val CONVERSATION_MIN_DP = 48
// One level of a list or quote.
private const val MD_INDENT_DP = 18
// Wider cells wrap: a table of prose stays readable, and the table scrolls sideways.
private const val MD_CELL_MAX_DP = 220
private val MD_HEADING_SCALE = floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f)

// The box is outside it, so nothing here needs scrolling to when it takes the focus:
// a tall selectable row that did would make the rows jump.
private class ChatScroll(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

// Likewise for the code and tables that scroll sideways.
private class ChatSideScroll(context: Context) : HorizontalScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

// Selectable text ignores a ClickableSpan's taps: selectableText finds them itself.
private class ChatLink(private val url: String, private val color: Int, private val open: (String) -> Unit) : ClickableSpan() {
    override fun onClick(widget: View) = open(url)

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
        ds.isUnderlineText = true
    }
}

/**
 * Touch mode's Chat: the service's [ChatController] as rows over the
 * terminal, drawn from the [ChatState]s the activity passes to [show].
 * Rows only change in place (see [chatRowChanges]), so streaming touches
 * one row. [back] is ‹ and [showTerminal] the Terminal button, as on Home;
 * [openTab] runs a command in a new tab (signing in, installing, an
 * artifact card's Run). A card's Open is [openApp] (a web app's name),
 * Share [shareFile] and Install [installApk] (Debian paths); a null
 * [installApk] means this build can't install apps.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ChatView(
    context: Context,
    private val controller: () -> ChatController?,
    private val back: () -> Unit,
    showTerminal: () -> Unit,
    private val openTab: (List<String>) -> Unit,
    private val openApp: (String) -> Unit,
    private val shareFile: (String) -> Unit,
    private val installApk: ((String) -> Unit)?,
) : LinearLayout(context) {
    private class Cell(val view: View, val bind: (ChatItem) -> Unit)
    private class MdCell(val type: Class<*>, val view: View, val bind: (MdRow) -> Unit)

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    private val scroll = ChatScroll(context).apply { addView(rows) }
    private val backButton = headerText("‹").apply {
        setOnClickListener { back() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val titleText = headerText(CHAT_TITLE).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(0, dp(12), dp(8), dp(12))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val newButton = headerText("New").apply {
        setOnClickListener { askNewChat() }
        setPadding(dp(10), dp(12), dp(10), dp(12))
    }
    private val chatsButton = headerText("Chats").apply {
        setOnClickListener { showList() }
        setPadding(dp(10), dp(12), dp(10), dp(12))
    }
    private val tabButton = headerText("To tab").apply {
        contentDescription = "Open this chat in a terminal tab"
        setOnClickListener { handOff() }
        setPadding(dp(10), dp(12), dp(10), dp(12))
    }
    private val terminalButton = headerText("Terminal").apply {
        setOnClickListener { showTerminal() }
        setPadding(dp(12), dp(12), dp(16), dp(12))
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        addView(backButton)
        addView(titleText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(chatsButton)
        addView(newButton)
        addView(tabButton)
        addView(terminalButton)
    }
    private val statusText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(4), dp(16), dp(4))
        visibility = View.GONE
    }
    private val gateText = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
    private val gateButton = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = dp(44)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        isFocusable = true
    }
    private val gateSecond = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = dp(44)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(32), dp(8), 0, dp(8))
        isFocusable = true
        visibility = View.GONE
    }
    private val gateCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(4))
        visibility = View.GONE
        addView(gateText)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(gateButton)
            addView(gateSecond)
        })
    }
    // The Chats list: the folder's past chats, over the conversation.
    private val listRows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    private val listScroll = ScrollView(context).apply {
        addView(listRows)
        visibility = View.GONE
    }
    private var listLoad = 0
    private val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        maxLines = 6
        hint = "Message"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = null
        setPadding(dp(16), dp(12), dp(8), dp(12))
    }
    private val stopButton = barButton("Stop").apply {
        setOnClickListener { controller()?.cancel() }
        visibility = View.GONE
    }
    private val sendButton = barButton("Send").apply {
        setOnClickListener { sendInput() }
    }
    private val inputRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(stopButton)
        addView(sendButton)
    }

    private val cells = ArrayList<Cell>()
    private var drawn: List<ChatItem> = emptyList()
    private var latest: ChatState? = null
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
            listOf(backButton, titleText, chatsButton, newButton, tabButton, terminalButton).forEach { it.setTextColor(value.accent) }
            statusText.setTextColor(value.accent)
            gateText.setTextColor(value.text)
            gateButton.setTextColor(value.accent)
            gateSecond.setTextColor(value.accent)
            input.setTextColor(value.text)
            input.setHintTextColor(value.accent)
            input.setBackgroundColor(value.selectedBackground)
            stopButton.setTextColor(value.mark)
            sendButton.setTextColor(value.accent)
            // The rows hold the old colours: draw them again.
            cells.clear()
            rows.removeAllViews()
            drawn = emptyList()
            render()
        }

    init {
        orientation = VERTICAL
        // Over the terminal: a touch on an empty part mustn't reach it.
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(listScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(gateCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
        if (listShown) {
            header.visibility = View.VISIBLE
            return
        }
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

    // A hardware keyboard types in the box, whatever row was tapped last.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed && !input.isFocused) input.requestFocus()
        return super.dispatchKeyEvent(event)
    }

    /** Takes the chat as it stands; drawn soon, with whatever else comes first. */
    fun show(state: ChatState) {
        latest = state
        if (renderPending) return
        renderPending = true
        postDelayed(renderTask, chatRenderDelay(SystemClock.uptimeMillis(), lastRender))
    }

    private fun render() {
        val state = latest ?: return
        val colors = colors ?: return
        renderRows(state.conversation.items, colors)
        renderControls(state, colors)
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
        cells.add(cell)
        rows.addView(cell.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(12), dp(4), dp(12), dp(4))
        })
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
        stopButton.visibility = if (conversation.running) View.VISIBLE else View.GONE
        val canSend = state.access == ChatAccess.READY && !state.stopping && state.tabHold != ChatTabHold.TAKING_BACK
        sendButton.isEnabled = canSend
        sendButton.alpha = if (canSend) 1f else 0.4f
        tabButton.alpha = if (state.handOffBlock == null) 1f else 0.4f
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

    private val listShown get() = listScroll.visibility == View.VISIBLE

    /** Back: closes the Chats list; false when there's none to close. */
    fun back(): Boolean {
        if (!listShown) return false
        hideList()
        return true
    }

    private fun showList() {
        val chat = controller() ?: return
        val colors = colors ?: return
        listScroll.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        statusText.visibility = View.GONE
        gateCard.visibility = View.GONE
        inputRow.visibility = View.GONE
        listOf(chatsButton, newButton, tabButton).forEach { it.visibility = View.GONE }
        titleText.text = "Chats"
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        listRows.removeAllViews()
        listRows.addView(listNote("Reading…", colors))
        val own = ++listLoad
        chat.pastChats { sessions -> if (own == listLoad && listShown) fillList(sessions, colors) }
    }

    private fun hideList() {
        listLoad++
        listScroll.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        inputRow.visibility = View.VISIBLE
        listOf(chatsButton, newButton, tabButton).forEach { it.visibility = View.VISIBLE }
        titleText.text = CHAT_TITLE
        requestLayout()
        render()
    }

    private fun fillList(sessions: List<ClaudeSessionSummary>, colors: StripColors) {
        listRows.removeAllViews()
        if (sessions.isEmpty()) {
            listRows.addView(listNote("No past chats here yet.", colors))
            return
        }
        val now = System.currentTimeMillis()
        for (session in sessions) {
            val title = plainText(colors.text, 16f).apply {
                text = session.title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val time = plainText(colors.accent, 13f).apply { text = chatRelativeTime(now, session.modifiedMs) }
            listRows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(56)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                isFocusable = true
                background = RippleDrawable(ColorStateList.valueOf(colors.selectedBackground), null, ColorDrawable(colors.background))
                addView(title)
                addView(time)
                setOnClickListener {
                    controller()?.resume(session.sessionId)
                    hideList()
                    stick = true
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun listNote(text: String, colors: StripColors) = plainText(colors.accent, 15f).apply {
        this.text = text
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun sendInput() {
        val text = input.text.toString()
        if (controller()?.send(text) == true) {
            input.setText("")
            stick = true
        }
    }

    private fun askNewChat() {
        if (drawn.isEmpty()) return
        AlertDialog.Builder(context)
            .setMessage("End this chat and start a new one?")
            .setPositiveButton("New chat") { _, _ -> controller()?.newChat() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun userCell(colors: StripColors): Cell {
        val bubble = selectableText(colors.text, 16f).apply {
            background = rounded(colors.selectedBackground)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(48), 0, 0, 0)
            addView(bubble)
        }
        return Cell(row) { item -> bubble.text = (item as ChatItem.User).text }
    }

    // Rows that didn't change keep their views, and any selection in them.
    private fun agentCell(colors: StripColors): Cell {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val parser = IncrementalMarkdown()
        val drawnCells = ArrayList<MdCell>()
        var drawnRows: List<MdRow> = emptyList()
        return Cell(column) { item ->
            val next = mdRows(parser.update((item as ChatItem.Agent).text))
            next.forEachIndexed { i, row ->
                val before = next.getOrNull(i - 1)
                val cell = drawnCells.getOrNull(i)
                when {
                    cell == null -> {
                        val made = mdCell(row, colors)
                        made.bind(row)
                        drawnCells.add(made)
                        column.addView(made.view, mdParams(row, before))
                    }
                    cell.type != row.javaClass -> {
                        val made = mdCell(row, colors)
                        made.bind(row)
                        drawnCells[i] = made
                        column.removeViewAt(i)
                        column.addView(made.view, i, mdParams(row, before))
                    }
                    row != drawnRows[i] -> {
                        cell.bind(row)
                        cell.view.layoutParams = mdParams(row, before)
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

    private fun mdParams(row: MdRow, before: MdRow?): LinearLayout.LayoutParams {
        val height = if (row is MdRow.Rule) dp(1) else WRAP_CONTENT
        return LinearLayout.LayoutParams(MATCH_PARENT, height).apply {
            topMargin = when {
                before == null -> 0
                row is MdRow.Text && row.heading > 0 -> dp(12)
                row is MdRow.Text && row.marker != null && before is MdRow.Text && before.marker != null -> dp(3)
                else -> dp(8)
            }
            // Text rows indent with spans, for the hanging indent.
            if (row !is MdRow.Text) leftMargin = (row.indent + row.quote) * dp(MD_INDENT_DP)
        }
    }

    private fun mdCell(row: MdRow, colors: StripColors): MdCell = when (row) {
        is MdRow.Text -> mdTextCell(row, colors)
        is MdRow.Code -> mdCodeCell(row, colors)
        is MdRow.Table -> mdTableCell(row, colors)
        is MdRow.Rule -> MdCell(row.javaClass, View(context).apply {
            setBackgroundColor(colors.accent)
            alpha = 0.4f
        }) { }
    }

    private fun mdTextCell(first: MdRow.Text, colors: StripColors): MdCell {
        val text = selectableText(colors.text, 16f, links = true)
        return MdCell(first.javaClass, text) { row -> text.text = mdText(row as MdRow.Text, text, colors) }
    }

    private fun mdText(row: MdRow.Text, view: TextView, colors: StripColors): CharSequence {
        val out = SpannableStringBuilder()
        val marker = row.marker
        if (marker != null) out.append(marker).append(' ')
        out.append(mdSpanned(row.text, colors))
        val end = out.length
        if (end == 0) return out
        if (row.heading > 0) {
            out.setSpan(StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(MD_HEADING_SCALE[row.heading - 1]), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        repeat(row.quote) {
            out.setSpan(QuoteSpan(colors.accent), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(LeadingMarginSpan.Standard(dp(8)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val first = row.indent * dp(MD_INDENT_DP)
        val rest = if (marker != null) first + ceil(view.paint.measureText("$marker ")).toInt() else first
        if (rest > 0) {
            // A line break in an item starts a paragraph, which would get the first line's margin.
            val breakAt = out.indexOf('\n')
            val firstEnd = if (breakAt < 0) end else breakAt + 1
            out.setSpan(LeadingMarginSpan.Standard(first, rest), 0, firstEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (firstEnd < end) out.setSpan(LeadingMarginSpan.Standard(rest), firstEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun mdSpanned(styled: MdStyled, colors: StripColors): SpannableString {
        val out = SpannableString(styled.text)
        for (style in styled.styles) {
            val spans: List<Any> = when (style.kind) {
                MdStyleKind.BOLD -> listOf(StyleSpan(Typeface.BOLD))
                MdStyleKind.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                MdStyleKind.STRIKE -> listOf(StrikethroughSpan())
                MdStyleKind.CODE -> listOf(TypefaceSpan("monospace"), BackgroundColorSpan(colors.selectedBackground))
                MdStyleKind.LINK -> listOf(ChatLink(style.url.orEmpty(), colors.accent) { url -> openLink(url) })
            }
            spans.forEach { out.setSpan(it, style.start, style.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return out
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

    private fun mdCodeCell(first: MdRow.Code, colors: StripColors): MdCell {
        var code = ""
        val language = plainText(colors.accent, 12f)
        val copy = cardButton("Copy", colors.accent, colors) { }
        copy.minHeight = dp(36)
        copy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
            copy.text = "Copied"
            copy.postDelayed({ copy.text = "Copy" }, 1500L)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            addView(language, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(copy)
        }
        val body = selectableText(colors.text, 13f).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colors.selectedBackground)
            addView(head)
            addView(ChatSideScroll(context).apply { addView(body) })
        }
        return MdCell(first.javaClass, card) { row ->
            val block = row as MdRow.Code
            code = block.text
            language.text = block.language.orEmpty()
            body.text = block.text
        }
    }

    private fun mdTableCell(first: MdRow.Table, colors: StripColors): MdCell {
        val grid = TableLayout(context).apply {
            setDividerDrawable(GradientDrawable().apply {
                setColor(colors.selectedBackground)
                setSize(1, dp(1))
            })
            setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE)
        }
        var drawnLines: List<List<MdStyled>> = emptyList()
        return MdCell(first.javaClass, ChatSideScroll(context).apply { addView(grid) }) { row ->
            val table = (row as MdRow.Table).table
            val lines = listOf(table.header) + table.rows
            lines.forEachIndexed { r, line ->
                if (line == drawnLines.getOrNull(r)) return@forEachIndexed
                val rowView = grid.getChildAt(r) as? TableRow ?: TableRow(context).also { made ->
                    if (r == 0) made.setBackgroundColor(colors.selectedBackground)
                    grid.addView(made)
                }
                while (rowView.childCount > line.size) rowView.removeViewAt(rowView.childCount - 1)
                line.forEachIndexed { c, styled ->
                    val cell = rowView.getChildAt(c) as? TextView ?: mdTableText(colors, r == 0).also { rowView.addView(it) }
                    cell.gravity = when (table.aligns.getOrNull(c)) {
                        MdAlign.CENTER -> Gravity.CENTER_HORIZONTAL
                        MdAlign.RIGHT -> Gravity.END
                        else -> Gravity.START
                    }
                    cell.text = mdSpanned(styled, colors)
                }
            }
            while (grid.childCount > lines.size) grid.removeViewAt(grid.childCount - 1)
            drawnLines = lines
        }
    }

    private fun mdTableText(colors: StripColors, header: Boolean) = selectableText(colors.text, 14f, links = true).apply {
        maxWidth = dp(MD_CELL_MAX_DP)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        if (header) setTypeface(typeface, Typeface.BOLD)
    }

    private fun toolCell(colors: StripColors): Cell {
        var call: ToolCallInfo? = null
        val title = plainText(colors.text, 14f).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val status = plainText(colors.accent, 13f)
        val body = selectableText(colors.text, 13f).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        fun refresh() {
            val current = call ?: return
            val expanded = current.id in expandedTools
            title.text = current.title
            status.text = chatToolStatus(current) + if (expanded) "  ▾" else "  ▸"
            title.maxLines = if (expanded) Int.MAX_VALUE else 2
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) body.text = chatToolBody(current)
        }
        val card = cardColumn(colors)
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
            card.addView(plainText(colors.accent, 13f).apply { text = "Permission needed · " + request.call.name })
            val cut = chatReviewCut(request.review)
            val whole = request.id in expandedReviews
            card.addView(selectableText(colors.text, 14f).apply {
                typeface = Typeface.MONOSPACE
                text = if (whole) request.review else cut.text
                setPadding(0, dp(4), 0, dp(4))
            })
            if (cut.hidden > 0) {
                val label = if (whole) "Show less" else "Show all (${cut.hidden} more characters)"
                card.addView(cardButton(label, colors.accent, colors) {
                    if (!expandedReviews.remove(request.id)) expandedReviews.add(request.id)
                    draw(request)
                })
            }
            val outcome = request.outcome
            if (outcome != null) {
                card.addView(answeredText(chatOutcomeText(outcome), colors))
            } else {
                request.options.forEach { option ->
                    val refuses = option.kind == PermissionOptionKind.REJECT_ONCE || option.kind == PermissionOptionKind.REJECT_ALWAYS
                    card.addView(cardButton(option.name, if (refuses) colors.mark else colors.accent, colors) {
                        controller()?.answerPermission(request.requestId, option.id)
                    })
                }
            }
        }
        return Cell(card) { item -> draw(item as ChatItem.Permission) }
    }

    private fun questionCell(colors: StripColors): Cell {
        val card = cardColumn(colors)
        fun draw(request: ChatItem.Question) {
            card.removeAllViews()
            val current = questionPicks.getOrPut(request.id) { QuestionPicks(request.questions) }
            request.questions.forEachIndexed { q, question ->
                question.header?.let { head -> card.addView(plainText(colors.accent, 13f).apply { text = head }) }
                card.addView(plainText(colors.text, 16f).apply {
                    text = question.question
                    setPadding(0, dp(2), 0, dp(4))
                })
                if (request.outcome == null) {
                    question.options.forEachIndexed { o, option ->
                        val mark = when {
                            current.isPicked(q, o) -> if (question.multiSelect) "☑ " else "● "
                            else -> if (question.multiSelect) "☐ " else "○ "
                        }
                        val label = mark + option.label + (option.description?.let { "\n" + it } ?: "")
                        card.addView(cardButton(label, colors.text, colors) {
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
                card.addView(cardButton("Send answer", colors.accent, colors) {
                    if (current.complete) controller()?.answerQuestion(request.requestId, current.answers())
                }.apply { alpha = if (current.complete) 1f else 0.4f })
            }
        }
        return Cell(card) { item -> draw(item as ChatItem.Question) }
    }

    private fun planCell(colors: StripColors): Cell {
        val card = cardColumn(colors)
        return Cell(card) { item ->
            val request = item as ChatItem.Plan
            card.removeAllViews()
            card.addView(plainText(colors.accent, 13f).apply { text = "Plan" })
            card.addView(selectableText(colors.text, 15f).apply {
                text = request.plan
                setPadding(0, dp(4), 0, dp(4))
            })
            request.planFilePath?.let { path -> card.addView(plainText(colors.accent, 12f).apply { text = path }) }
            val outcome = request.outcome
            if (outcome != null) {
                card.addView(answeredText(chatOutcomeText(outcome), colors))
            } else {
                card.addView(cardButton("Approve", colors.accent, colors) {
                    controller()?.answerPlan(request.requestId, true)
                })
                card.addView(cardButton("Keep planning", colors.mark, colors) {
                    controller()?.answerPlan(request.requestId, false)
                })
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
                    NoticeKind.ERROR -> colors.mark
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
            card.addView(plainText(colors.text, 16f).apply { text = artifact.label })
            card.addView(plainText(colors.accent, 13f).apply {
                text = chatArtifactLine(artifact, CHAT_HOME)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            chatArtifactActions(artifact, canInstall = install != null).forEach { action ->
                card.addView(cardButton(action.label, colors.accent, colors) {
                    when (action) {
                        ArtifactAction.OPEN -> artifact.appName?.let(openApp)
                        ArtifactAction.ADD_TO_HOME -> openTab(chatArtifactShortcutCommand(artifact))
                        ArtifactAction.RUN -> openTab(chatArtifactRunCommand(artifact))
                        ArtifactAction.INSTALL -> install?.invoke(artifact.shareFile)
                        ArtifactAction.SHARE -> shareFile(artifact.shareFile)
                    }
                })
            }
            chatArtifactNote(artifact, canInstall = install != null)?.let { note ->
                card.addView(answeredText(note, colors))
            }
        }
    }

    private fun cardColumn(colors: StripColors) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colors.selectedBackground)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun plainText(color: Int, sp: Float) = TextView(context).apply {
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

    private fun linkAt(row: TextView, event: MotionEvent): ChatLink? {
        val layout = row.layout ?: return null
        val x = event.x - row.totalPaddingLeft + row.scrollX
        val line = layout.getLineForVertical((event.y - row.totalPaddingTop + row.scrollY).toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        val text = row.text as? Spanned ?: return null
        return text.getSpans(offset, offset, ChatLink::class.java).firstOrNull()
    }

    // Posted: the touch hasn't given the row the focus yet, nor ended the selection.
    private fun focusBoxAfter(row: TextView) {
        row.post { if (row.isFocused && !row.hasSelection()) input.requestFocus() }
    }

    private fun answeredText(text: String, colors: StripColors) = plainText(colors.accent, 14f).apply {
        this.text = text
        setPadding(0, dp(6), 0, 0)
    }

    private fun cardButton(label: String, color: Int, colors: StripColors, onTap: () -> Unit) = plainText(color, 15f).apply {
        text = label
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(44)
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(colors.accent), null, ColorDrawable(colors.text))
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun headerText(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun barButton(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isFocusable = true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
