package com.portal.tv.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.portal.tv.R
import com.portal.tv.data.AppInfo
import com.portal.tv.data.Profile
import com.portal.tv.repo.AppRepository
import com.portal.tv.repo.ProfileManager
import com.portal.tv.ui.HomeFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Search, shown as a glass panel over the dimmed home screen. Typing finds installed apps; pressing
 * Enter opens the best match, or, when nothing matches and the profile may use the AI, asks the AI
 * instead. The conversation is saved per profile until it is cleared.
 */
class SearchDialogFragment : DialogFragment() {

    private lateinit var profile: Profile
    private lateinit var level: SearchLevel
    private var apps: List<AppInfo> = emptyList()
    private var matches: List<AppInfo> = emptyList()
    private val history = mutableListOf<ChatMessage>()

    /** Summary of messages that were compacted away to keep the chat inside the model's context. */
    private var summary = ""
    private var streamJob: Job? = null

    /** An app the model asked to open; it opens once the model has finished its reply. */
    private var pendingLaunch: AppInfo? = null
    private var recognizer: SpeechRecognizer? = null

    // Spoken replies: set when the question was spoken, and consumed by the next question asked.
    private var askedByVoice = false
    private var speakReply = false
    private var splitter = SpeechText.SentenceSplitter()

    // The reply being read aloud, and how far into it the voice has got, for highlighting the spoken word.
    private var replyBubble: TextView? = null
    private var wordCursor = 0
    private var highlight: IntRange? = null
    private var replyRaw = ""   // the reply being shown, as the model wrote it (markdown)

    // The animated "Thinking..." shown while nothing has arrived yet.
    private val dotsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dotsTarget: TextView? = null
    private var dotsLabel = ""
    private var dotsStep = 0
    private val dotsTick = object : Runnable {
        override fun run() {
            val target = dotsTarget ?: return
            dotsStep = (dotsStep + 1) % 4
            // Figure spaces keep the width steady, so the bubble doesn't twitch as the dots come and go.
            target.text = dotsLabel + ".".repeat(dotsStep) + "\u2007".repeat(3 - dotsStep)
            dotsHandler.postDelayed(this, 420)
        }
    }

    private fun startDots(bubble: TextView, label: String) {
        stopDots()
        dotsTarget = bubble
        dotsLabel = label
        dotsStep = 0
        bubble.text = label + "\u2007".repeat(3)
        // Wide enough for all three dots from the start, so the bubble doesn't change size as they cycle.
        bubble.minWidth = (bubble.paint.measureText("$label...") + bubble.paddingLeft + bubble.paddingRight).toInt()
        dotsHandler.postDelayed(dotsTick, 420)
    }

    private fun stopDots() {
        dotsHandler.removeCallbacks(dotsTick)
        dotsTarget?.minWidth = 0
        dotsTarget = null
    }

    // Streamed text is drawn at most every 90 ms, so a fast stream can't flood the screen thread.
    private val latestText = java.util.concurrent.atomic.AtomicReference("")
    private val updatePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showStatus("Allow the microphone to use voice input")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Portal_Search)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val active = ProfileManager.getActive(context)
        if (active == null || !SearchSettings.level(context, active).findsApps) {
            dismissAllowingStateLoss()
            return
        }
        profile = active
        level = SearchSettings.level(context, active)

        input = view.findViewById(R.id.search_input)
        val stopTalking = view.findViewById<View>(R.id.search_stop_speaking)
        stopTalking.setOnClickListener { Speaker.stop() }
        Speaker.onSpeakingChanged = { speaking ->
            if (isAdded) {
                stopTalking.visibility = if (speaking) View.VISIBLE else View.GONE
                if (!speaking) clearHighlight()
            }
        }
        Speaker.onWord = { word -> if (isAdded) highlightWord(word) }
        // Wake the voice now if replies may be spoken, so the first sentence isn't held up by its start-up.
        if (SearchSettings.spoken(context, active.id) != SearchSettings.SpokenReplies.OFF && Speaker.isAvailable(context)) {
            Speaker.voiceName = SearchSettings.voice(context, active.id)
            Speaker.warmUp(context)
        }
        stopTalking.visibility = if (Speaker.isSpeaking) View.VISIBLE else View.GONE
        status = view.findViewById(R.id.search_status)
        chat = view.findViewById(R.id.search_chat)
        chatScroll = view.findViewById(R.id.search_chat_scroll)
        val clear = view.findViewById<TextView>(R.id.search_clear)

        // What the profile may use decides what the panel shows.
        view.findViewById<TextView>(R.id.search_title).text = if (level.usesAi) "Ask AI" else "Search apps"
        view.findViewById<View>(R.id.search_sparkle).visibility = if (level.usesAi) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.search_kid_badge).visibility =
            if (level.usesAi && profile.isKids) View.VISIBLE else View.GONE
        chatScroll.visibility = if (level.usesAi) View.VISIBLE else View.GONE
        input.hint = if (level.usesAi) "Search apps or ask a question" else "Search apps"

        if (level.usesAi) {
            ChatHistory.load(context, profile.id).let { saved ->
                summary = saved.summary
                history.addAll(saved.messages)
            }
            renderHistory()
            clear.setOnClickListener { clearChat() }
            updateClearButton()
        }

        viewLifecycleOwner.lifecycleScope.launch { loadApps() }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshMatches()
        })
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submit()
                true
            } else false
        }
        view.findViewById<TextView>(R.id.search_ask).setOnClickListener { ask(input.text.toString()) }
        setupMic(view)

        input.post {
            input.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroyView() {
        stopDots()
        Speaker.onSpeakingChanged = null
        Speaker.onWord = null
        recognizer?.destroy()
        recognizer = null
        super.onDestroyView()
    }

    // ---------- Apps ----------

    /** Every app this profile may find: a kid sees only the apps a parent allowed; hidden apps are still found. */
    private suspend fun loadApps() {
        val all = AppRepository(requireContext()).getLaunchableApps()
        apps = if (profile.isKids) all.filter { it.packageName in profile.allowedApps } else all
        refreshMatches()
    }

    private fun refreshMatches() {
        val view = view ?: return
        val query = input.text.toString().trim()
        matches = AppSearch.match(apps, query) { it.label }

        val row = view.findViewById<LinearLayout>(R.id.search_apps_row)
        row.removeAllViews()
        matches.take(MAX_APP_RESULTS).forEach { app ->
            val item = layoutInflater.inflate(R.layout.item_search_app, row, false)
            item.findViewById<ImageView>(R.id.search_app_icon).setImageDrawable(app.icon)
            item.findViewById<TextView>(R.id.search_app_label).text = app.label
            item.setOnClickListener { open(app) }
            row.addView(item)
        }
        val any = matches.isNotEmpty()
        view.findViewById<View>(R.id.search_apps_label).visibility = if (any) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.search_apps_scroll).visibility = if (any) View.VISIBLE else View.GONE

        val ask = view.findViewById<TextView>(R.id.search_ask)
        ask.visibility = if (level.usesAi && query.isNotEmpty()) View.VISIBLE else View.GONE
        ask.text = "Ask AI: \"$query\""
    }

    private fun open(app: AppInfo) {
        dismissAllowingStateLoss()
        (parentFragment as? HomeFragment)?.launchFromSearch(app)
    }

    /** Enter: open the best matching app; if there is none, ask the AI (when allowed). */
    private fun submit() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return
        val best = matches.firstOrNull()
        when {
            best != null -> { askedByVoice = false; open(best) }
            level.usesAi -> ask(query)
            else -> showStatus("No apps match \"$query\"")
        }
    }

    // ---------- Chat ----------

    private fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || !level.usesAi) return
        val context = requireContext()
        if (streamJob?.isActive == true) {
            showStatus("Still answering the last question")
            return
        }
        if (!SearchSettings.isAiConfigured(context)) {
            showStatus("The AI isn't set up yet. Add a server in Settings, Search")
            return
        }

        // Read the answer aloud when the profile wants it: always, or after a spoken question.
        val mode = SearchSettings.spoken(context, profile.id)
        speakReply = Speaker.isAvailable(context) &&
            (mode == SearchSettings.SpokenReplies.ALWAYS || (mode == SearchSettings.SpokenReplies.AFTER_VOICE && askedByVoice))
        askedByVoice = false
        Speaker.stop()
        Speaker.voiceName = SearchSettings.voice(context, profile.id)
        splitter = SpeechText.SentenceSplitter()

        history += ChatMessage("user", question)
        saveChat()
        input.setText("")
        // Put the keyboard away so the answer is readable; select on the box brings it back.
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        renderHistory()
        updateClearButton()

        val bubble = addBubble("assistant", "")
        startDots(bubble, "Thinking")
        replyBubble = bubble
        replyRaw = ""
        wordCursor = 0
        highlight = null
        val base = SearchSettings.baseUrl(context)
        val model = SearchSettings.model(context)
        val key = SearchSettings.apiKey(context)
        val contextTokens = SearchSettings.contextTokens(context)

        streamJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Before sending, make room if the request is getting close to the context size.
                val size = Compaction.requestTokens(history, summary, profile.isKids)
                if (Compaction.needsCompaction(size, contextTokens)) {
                    compact(bubble, base, model, key, Compaction.KEEP_RECENT, contextTokens)
                }

                pendingLaunch = null
                val answer = send(bubble, base, model, key)
                stopDots()
                showReply(bubble, answer)
                history += ChatMessage("assistant", answer)
                saveChat()
                // The model asked to open an app: let the reply be read for a moment, then open it.
                pendingLaunch?.let { app ->
                    kotlinx.coroutines.delay(LAUNCH_DELAY_MS)
                    open(app)
                    return@launch
                }
            } catch (e: LlmClient.LlmException) {
                Speaker.stop()
                stopDots()
                bubble.text = "${e.message}. Try again."
                // The question went unanswered, so don't keep it in the chat; put it back to retry.
                if (history.lastOrNull()?.role == "user") history.removeAt(history.lastIndex)
                saveChat()
                input.setText(question)
                input.setSelection(question.length)
                updateClearButton()
            }
            if (highlight == null) scrollToEnd()
        }
    }

    /**
     * Sends the chat and streams the answer into [bubble]. The model may ask to run tools (open an
     * app, search the web) first; Portal runs them and sends the results back until it answers.
     * If the server says the chat is too long, summarizes harder and tries once more. The reply is
     * shown exactly as sent.
     */
    private suspend fun send(bubble: TextView, base: String, model: String, key: String?): String {
        val context = requireContext()
        var retried = false
        while (true) {
            val openApp = apps.isNotEmpty() && SearchSettings.toolsSupported(context)
            val web = !profile.isKids && SearchSettings.toolsSupported(context) && SearchSettings.isWebSearchReady(context)
            val tools = Tools.definitions(openApp, web)
            val messages = ChatPrompt.build(
                history, profile.isKids, summary.ifEmpty { null }, Tools.note(openApp, web)
            ).toMutableList()
            try {
                return runChat(bubble, base, model, key, messages, tools, web)
            } catch (e: LlmClient.ToolsNotSupportedException) {
                // This server can't do tools: remember that, say so once, and chat without them.
                SearchSettings.markToolsUnsupported(context)
                addNote("This server can't use tools, so opening apps and web search are off", bubble)
            } catch (e: LlmClient.ContextTooLongException) {
                if (retried) throw e
                retried = true
                // The estimate was too hopeful: keep only the very latest messages this time.
                compact(bubble, base, model, key, Compaction.KEEP_RECENT_TIGHT, SearchSettings.contextTokens(context))
            }
        }
    }

    /** One pass through the tool loop: ask, run any tools the model calls, and ask again until it answers in words. */
    private suspend fun runChat(
        bubble: TextView, base: String, model: String, key: String?,
        messages: MutableList<ChatMessage>, tools: org.json.JSONArray?, web: Boolean
    ): String {
        var offered = tools
        repeat(MAX_TOOL_ROUNDS + 1) { round ->
            // After the last round, take away the tools so the model has to answer.
            if (round == MAX_TOOL_ROUNDS) offered = null
            val partial = StringBuffer()
            val shown = intArrayOf(0)   // how much of the visible reply has gone to the voice
            val reply = withContext(Dispatchers.IO) {
                LlmClient.streamWithTools(base, model, key, messages, offered) { piece ->
                    partial.append(piece)
                    // A tool call a model writes as text (<tool_call>...) is run, not shown or spoken.
                    val visible = ToolCallText.visiblePrefix(partial.toString())
                    if (speakReply && visible.length > shown[0]) {
                        // Straight to the voice, from this thread: it must not queue behind the screen updates.
                        val app = bubble.context.applicationContext
                        splitter.feed(visible.substring(shown[0])).forEach { sentence -> Speaker.speak(app, sentence) }
                        shown[0] = visible.length
                    }
                    if (visible.isBlank()) return@streamWithTools
                    latestText.set(visible)
                    if (updatePending.compareAndSet(false, true)) {
                        bubble.postDelayed({
                            updatePending.set(false)
                            stopDots()
                            showReply(bubble, latestText.get())
                            // While the voice is being followed, the view follows the spoken word instead of the end.
                            if (highlight == null) scrollToEnd()
                        }, 90)
                    }
                }
            }
            if (reply.toolCalls.isEmpty()) {
                if (speakReply) {
                    val app = bubble.context.applicationContext
                    splitter.flush()?.let { rest -> Speaker.speak(app, rest) }
                }
                return reply.text.trim()
            }
            splitter = SpeechText.SentenceSplitter()   // words said before a tool call aren't the answer

            messages += ChatMessage("assistant", reply.text, toolCalls = reply.toolCalls)
            for (call in reply.toolCalls) {
                messages += ChatMessage("tool", runTool(call, bubble, web), toolCallId = call.id)
            }
            startDots(bubble, "Thinking")
        }
        throw LlmClient.LlmException("The model kept asking for tools")
    }

    /** Runs one tool call and returns what to tell the model. Failures are reported to the model, not thrown. */
    private suspend fun runTool(call: ToolCall, bubble: TextView, web: Boolean): String = when (call.name) {
        Tools.OPEN_APP -> openAppTool(call, bubble)
        Tools.WEB_SEARCH -> if (web) webSearchTool(call, bubble) else "Web search isn't available."
        else -> "Unknown tool ${call.name}."
    }

    private fun openAppTool(call: ToolCall, bubble: TextView): String {
        val name = Tools.argument(call.arguments, "name") ?: return "No app name was given."
        pendingLaunch?.let { return "Already opening ${it.label}." }
        val app = AppSearch.find(apps, name) { it.label }
        if (app == null) {
            val installed = apps.take(40).joinToString(", ") { it.label }
            return "There is no app called \"$name\". Installed apps: $installed."
        }
        stopDots()
        bubble.text = "Opening ${app.label}…"
        pendingLaunch = app
        return "Opening ${app.label} now."
    }

    private suspend fun webSearchTool(call: ToolCall, bubble: TextView): String {
        val query = Tools.argument(call.arguments, "query") ?: return "No search query was given."
        val context = requireContext()
        // The search stays on screen above the answer, so you can see what was looked up and how it went.
        val note = addNote("Searching the web for “$query”…", bubble)
        startDots(bubble, "Reading the results")
        val provider = SearchSettings.webProvider(context)
        val searxng = SearchSettings.searxngUrl(context)
        val brave = SearchSettings.braveKey(context)
        return try {
            val results = withContext(Dispatchers.IO) { WebSearch.search(provider, query, searxng, brave) }
            note.text = "Searched the web for “$query” · ${results.size} result${if (results.size == 1) "" else "s"}"
            WebSearch.format(query, results)
        } catch (e: WebSearch.SearchException) {
            note.text = "Web search for “$query” failed: ${e.message}"
            "Web search failed: ${e.message}."
        }
    }

    /**
     * Folds the older messages into the summary and keeps only the recent ones. If the model can't
     * produce a summary, the oldest messages are dropped instead so the chat still fits.
     */
    private suspend fun compact(
        bubble: TextView, base: String, model: String, key: String?, keepRecent: Int, contextTokens: Int
    ) {
        val parts = Compaction.split(history, keepRecent)
        if (parts == null) {
            val trimmed = Compaction.trimToFit(history, summary.ifEmpty { null }, profile.isKids, contextTokens)
            history.clear(); history.addAll(trimmed)
            saveChat()
            return
        }
        val (older, recent) = parts
        startDots(bubble, "Summarizing earlier messages")
        val request = Compaction.summaryRequest(summary.ifEmpty { null }, older)
        val newSummary = try {
            withContext(Dispatchers.IO) { LlmClient.stream(base, model, key, request) {} }.trim()
        } catch (e: LlmClient.LlmException) {
            null
        }
        if (newSummary != null && newSummary.isNotEmpty()) summary = newSummary
        // Whether or not the summary worked, the older messages leave the request.
        history.clear(); history.addAll(recent)
        if (newSummary == null) {
            val trimmed = Compaction.trimToFit(history, summary.ifEmpty { null }, profile.isKids, contextTokens)
            history.clear(); history.addAll(trimmed)
        }
        saveChat()
        startDots(bubble, "Thinking")
        addNote(if (newSummary != null) "Earlier messages were summarized to make room" else "Older messages were dropped to make room", bubble)
    }

    private fun saveChat() = ChatHistory.save(requireContext(), profile.id, ChatHistory.Chat(summary, history.toList()))

    private fun clearChat() {
        streamJob?.cancel()
        stopDots()
        Speaker.stop()
        history.clear()
        summary = ""
        ChatHistory.clear(requireContext(), profile.id)
        renderHistory()
        updateClearButton()
        showStatus("Chat cleared")
    }

    private fun updateClearButton() {
        view?.findViewById<View>(R.id.search_clear)?.visibility =
            if (level.usesAi && (history.isNotEmpty() || summary.isNotEmpty())) View.VISIBLE else View.GONE
    }

    private fun renderHistory() {
        chat.removeAllViews()
        if (history.isEmpty() && summary.isEmpty()) {
            chat.addView(TextView(requireContext()).apply {
                text = "Ask anything. Your chat is saved here until you clear it."
                textSize = 17f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        if (summary.isNotEmpty()) addNote("Earlier messages were summarized to make room")
        history.forEach { addBubble(it.role, it.content) }
        scrollToEnd()
    }

    private fun addBubble(role: String, text: String): TextView {
        val mine = role == "user"
        val bubble = TextView(requireContext()).apply {
            // The model's replies are markdown; what you typed is shown as typed.
            this.text = if (mine) text else MarkdownText.toSpanned(text)
            textSize = 20f
            setLineSpacing(0f, 1.15f)
            setTextColor(resources.getColor(R.color.text_primary, null))
            setBackgroundResource(if (mine) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai)
            setPadding(dp(18), dp(12), dp(18), dp(14))
            maxWidth = (resources.displayMetrics.widthPixels * 0.55f).toInt()
        }
        chat.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(10)
        })
        return bubble
    }

    /** A small centered line in the chat, placed just before [before] when given, else at the end. */
    private fun addNote(text: String, before: View? = null): TextView {
        val note = TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.text_secondary, null))
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
        val index = before?.let { chat.indexOfChild(it) } ?: -1
        if (index >= 0) chat.addView(note, index, params) else chat.addView(note, params)
        return note
    }

    /** Lights up the word the voice is saying in the reply, and scrolls so that it is on screen. */
    private fun highlightWord(word: String) {
        val bubble = replyBubble ?: return
        val at = SpeechText.locate(bubble.text.toString(), word, wordCursor) ?: return
        wordCursor = at.last + 1
        highlight = at
        applyHighlight(bubble)
        bubble.post { scrollToHighlight(bubble, at) }
    }

    /** Shows [raw] (markdown) as styled text in the reply bubble, with the spoken word lit if one is being followed. */
    private fun showReply(bubble: TextView, raw: String) {
        replyRaw = raw
        applyHighlight(bubble)
    }

    /** Draws the reply as it is now: styled, and with the highlight on top when one is being followed. */
    private fun applyHighlight(bubble: TextView? = replyBubble) {
        bubble ?: return
        val styled = MarkdownText.toSpanned(replyRaw)
        val at = highlight
        if (at != null && at.last < styled.length) {
            styled.setSpan(
                android.text.style.BackgroundColorSpan(0xFF6C63FF.toInt()), at.first, at.last + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        bubble.text = styled
    }

    /** Scrolls the chat just enough that the line holding the highlighted word is fully visible, with room around it. */
    private fun scrollToHighlight(bubble: TextView, at: IntRange) {
        val layout = bubble.layout ?: return
        val line = layout.getLineForOffset(at.first.coerceAtMost((layout.text.length - 1).coerceAtLeast(0)))
        // The line's box in the chat's own coordinates, which the scroll position is measured in.
        val box = android.graphics.Rect(0, layout.getLineTop(line) + bubble.paddingTop, bubble.width, layout.getLineBottom(line) + bubble.paddingTop)
        chatScroll.offsetDescendantRectToMyCoords(bubble, box)
        val margin = dp(56)
        val viewTop = chatScroll.scrollY
        val viewBottom = chatScroll.scrollY + chatScroll.height
        val target = when {
            box.top - margin < viewTop -> box.top - margin
            box.bottom + margin > viewBottom -> box.bottom + margin - chatScroll.height
            else -> return
        }
        chatScroll.smoothScrollTo(0, target.coerceAtLeast(0))
    }

    private fun clearHighlight() {
        highlight = null
        applyHighlight()
    }

    private fun scrollToEnd() {
        chatScroll.post { chatScroll.smoothScrollTo(0, chat.height) }
    }

    // ---------- Voice ----------

    /** The mic shows only when this TV has a speech recognizer to listen with. */
    private fun setupMic(view: View) {
        val mic = view.findViewById<View>(R.id.search_mic)
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) return
        mic.visibility = View.VISIBLE
        mic.setOnClickListener {
            val context = requireContext()
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startListening() {
        val context = requireContext()
        Speaker.stop()   // so the microphone doesn't hear the TV talking
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) { showStatus("Didn't catch that. Try again"); return }
                    hideStatus()
                    input.setText(text)
                    input.setSelection(text.length)
                    askedByVoice = true
                    submit()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.let { input.setText(it); input.setSelection(it.length) }
                }

                override fun onError(error: Int) {
                    showStatus(
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                            "Didn't catch that. Try again"
                        else "Voice input isn't available right now"
                    )
                }

                override fun onReadyForSpeech(params: Bundle?) = showStatus("Listening…")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(
                android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    // Wait longer for the person to finish: the defaults cut off after about a second of quiet.
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6_000L)
                }
            )
        }
    }

    // ---------- Helpers ----------

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_APP_RESULTS = 12
        const val MAX_TOOL_ROUNDS = 4
        const val LAUNCH_DELAY_MS = 1_200L
    }
}
