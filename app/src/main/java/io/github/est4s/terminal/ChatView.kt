package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.est4s.terminal.core.CLAUDE_INSTALL_COMMAND
import io.github.est4s.terminal.core.CLAUDE_SIGN_IN_COMMAND
import io.github.est4s.terminal.core.ChatAccess
import io.github.est4s.terminal.core.ChatController
import io.github.est4s.terminal.core.ChatItem
import io.github.est4s.terminal.core.ChatState
import io.github.est4s.terminal.core.NoticeKind
import io.github.est4s.terminal.core.PermissionOptionKind
import io.github.est4s.terminal.core.QuestionPicks
import io.github.est4s.terminal.core.StripColors
import io.github.est4s.terminal.core.ToolCallInfo
import io.github.est4s.terminal.core.chatFit
import io.github.est4s.terminal.core.chatOutcomeText
import io.github.est4s.terminal.core.chatReviewCut
import io.github.est4s.terminal.core.chatRowChanges
import io.github.est4s.terminal.core.chatToolBody
import io.github.est4s.terminal.core.chatToolStatus
import io.github.est4s.terminal.core.runThenShell

// Streaming text comes faster than the screen needs: changes are drawn together.
private const val RENDER_DELAY_MS = 33L
// What the rows keep of the height when it's short (see chatFit).
private const val CONVERSATION_MIN_DP = 48

// The box is outside it, so nothing here needs scrolling to when it takes the focus:
// a tall selectable row that did would make the rows jump.
private class ChatScroll(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

/**
 * Touch mode's Chat: the service's [ChatController] as rows over the
 * terminal, drawn from the [ChatState]s the activity passes to [show].
 * Rows only change in place (see [chatRowChanges]), so streaming touches
 * one row. [back] is ‹ and [showTerminal] the Terminal button, as on Home;
 * [openTab] runs a command in a new tab (signing in, installing).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ChatView(
    context: Context,
    private val controller: () -> ChatController?,
    private val back: () -> Unit,
    showTerminal: () -> Unit,
    private val openTab: (List<String>) -> Unit,
) : LinearLayout(context) {
    private class Cell(val view: View, val bind: (ChatItem) -> Unit)

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    private val scroll = ChatScroll(context).apply { addView(rows) }
    private val backButton = headerText("‹").apply {
        setOnClickListener { back() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val titleText = headerText("Claude Code").apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(0, dp(12), dp(8), dp(12))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val newButton = headerText("New").apply {
        setOnClickListener { askNewChat() }
        setPadding(dp(12), dp(12), dp(12), dp(12))
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
        addView(newButton)
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
    private val gateCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(4))
        visibility = View.GONE
        addView(gateText)
        addView(gateButton)
    }
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
    private val renderTask = Runnable {
        renderPending = false
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
            listOf(backButton, titleText, newButton, terminalButton).forEach { it.setTextColor(value.accent) }
            statusText.setTextColor(value.accent)
            gateText.setTextColor(value.text)
            gateButton.setTextColor(value.accent)
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
        postDelayed(renderTask, RENDER_DELAY_MS)
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
            state.canRestart ->
                showGate("The chat has stopped.", "Restart") { controller()?.restart() }
            else -> gateWanted = false
        }
        if (statusWanted != hadStatus || gateWanted != hadGate) requestLayout()
        stopButton.visibility = if (conversation.running) View.VISIBLE else View.GONE
        val canSend = state.access == ChatAccess.READY
        sendButton.isEnabled = canSend
        sendButton.alpha = if (canSend) 1f else 0.4f
    }

    private fun showGate(text: String, action: String, onTap: () -> Unit) {
        gateText.text = text
        gateButton.text = action
        gateButton.setOnClickListener { onTap() }
        gateWanted = true
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

    private fun agentCell(colors: StripColors): Cell {
        val text = selectableText(colors.text, 16f)
        return Cell(text) { item -> text.text = (item as ChatItem.Agent).text }
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
    private fun selectableText(color: Int, sp: Float): TextView = plainText(color, sp).also { row ->
        row.setTextIsSelectable(true)
        row.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) focusBoxAfter(row)
            false
        }
        row.setCustomSelectionActionModeCallback(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) = focusBoxAfter(row)
        })
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
