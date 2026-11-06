package com.portal.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.portal.tv.R
import com.portal.tv.repo.ParentPin
import com.portal.tv.repo.WeatherRepository
import com.portal.tv.data.Profile
import com.portal.tv.search.Endpoint
import com.portal.tv.search.LlmClient
import com.portal.tv.search.SearchLevel
import com.portal.tv.search.SearchSettings
import com.portal.tv.search.WebProvider
import com.portal.tv.search.WebSearch
import com.portal.tv.screensaver.ScreensaverSettings
import com.portal.tv.background.BackgroundRepository
import com.portal.tv.background.HolidayStore
import com.portal.tv.background.Holidays
import com.portal.tv.background.VideoThumbnails
import com.portal.tv.settings.AppSettings
import com.portal.tv.settings.BirthdayStore
import com.portal.tv.settings.BackgroundSettings
import android.util.Log

private const val SUPPORT_URL = "https://buymeacoffee.com/portal.tv"

/**
 * Simple settings screen for weather units and background mode.
 * Background mode changes take effect when returning to the home screen.
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val ARG_UPDATES = "updates"

        /** Settings opened straight at System, Updates, with the update's button selected. */
        fun forUpdates() = SettingsFragment().apply { arguments = Bundle().apply { putBoolean(ARG_UPDATES, true) } }
    }

    private var onBackgroundChanged: (() -> Unit)? = null

    /** Shows or hides the shader color picker to match the background mode. */
    private var refreshShaderColors: (() -> Unit)? = null


    fun setBackgroundChangedListener(listener: () -> Unit) {
        onBackgroundChanged = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUnits(view)
        setupWeatherLocation(view)
        setupShaderColors(view)
        setupSpeed(view)
        setupSound(view)
        setupVideoChoices(view)
        setupKidsVideoAccess(view)
        setupBackground(view)
        setupAppDisplay(view)
        setupScreensaver(view)
        setupBirthdays(view)
        setupReminders(view)
        setupExtensions(view)
        setupPin(view)
        setupSystemSettings(view)
        setupAbout(view)
        setupUpdates(view)
        setupProfileHints(view)
        setupAppManagement(view)
        setupKidsSchedule(view)
        setupSearchLevels(view)
        setupSearch(view)
        setupOverlayPermission(view)
        setupPanes(view)
    }

    /** Restoring apps hidden from the home screen, and putting the order back to alphabetical. */
    private fun setupAppManagement(view: View) {
        val context = requireContext()
        val hiddenTile = view.findViewById<TextView>(R.id.opt_hidden_apps)
        val resetTile = view.findViewById<TextView>(R.id.opt_reset_order)

        fun refresh() {
            val count = AppSettings.hiddenApps(context).size
            hiddenTile.text = if (count == 0) "Hidden apps" else "Hidden apps ($count)"
            resetTile.isEnabled = AppSettings.appOrder(context).isNotEmpty()
            resetTile.alpha = if (resetTile.isEnabled) 1f else 0.5f
        }

        hiddenTile.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val hidden = AppSettings.hiddenApps(context)
                // Installed apps first, then any hidden package that has since been uninstalled.
                val labels = com.portal.tv.repo.AppRepository(context).getLaunchableApps()
                    .filter { it.packageName in hidden }
                    .associate { it.packageName to it.label }
                val gone = hidden.filter { it !in labels }
                gone.forEach { AppSettings.unhideApp(context, it) } // nothing to show for these
                if (labels.isEmpty()) {
                    Toast.makeText(context, "No hidden apps", Toast.LENGTH_SHORT).show()
                    refresh()
                    return@launch
                }
                val packages = labels.keys.toList()
                PortalDialog.Builder(context)
                    .setTitle("Select an app to show it again")
                    .setItems(packages.map { labels.getValue(it) }.toTypedArray()) { _, which ->
                        AppSettings.unhideApp(context, packages[which])
                        refresh()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
        resetTile.setOnClickListener {
            if (!resetTile.isEnabled) return@setOnClickListener
            AppSettings.resetAppOrder(context)
            refresh()
            Toast.makeText(context, "App order reset to alphabetical", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    /** The background belongs to the active profile, so say whose it is; the screen saver belongs to the TV. */
    private fun setupProfileHints(view: View) {
        view.findViewById<TextView>(R.id.hint_screensaver).text = "For the whole TV, whichever profile is in use"
        val name = com.portal.tv.repo.ProfileManager.getActive(requireContext())?.name ?: return
        val text = "Saved for $name's profile"
        view.findViewById<TextView>(R.id.hint_background).text = text
        view.findViewById<TextView>(R.id.hint_videos).text = text
    }

    /**
     * The AI server: address, model and an optional API key, one setup for the whole TV. Each is
     * edited in a small dialog with the TV keyboard, and Test connection checks them together.
     */
    private fun setupSearch(view: View) {
        val context = requireContext()
        val url = view.findViewById<TextView>(R.id.opt_ai_url)
        val model = view.findViewById<TextView>(R.id.opt_ai_model)
        val key = view.findViewById<TextView>(R.id.opt_ai_key)
        val contextTile = view.findViewById<TextView>(R.id.opt_ai_context)
        val webProvider = view.findViewById<TextView>(R.id.opt_web_provider)
        val webSearxng = view.findViewById<TextView>(R.id.opt_web_searxng)
        val webBrave = view.findViewById<TextView>(R.id.opt_web_brave)
        val test = view.findViewById<TextView>(R.id.opt_ai_test)
        val testStatus = view.findViewById<TextView>(R.id.ai_test_status)
        view.findViewById<TextView>(R.id.hint_search).text = "Shared by every profile"

        // Spoken replies: each profile chooses when the AI's answers are read aloud.
        val spokenRow = view.findViewById<android.widget.LinearLayout>(R.id.row_spoken)
        val spokenLabel = view.findViewById<TextView>(R.id.label_spoken)
        val spokenProfile = com.portal.tv.repo.ProfileManager.getActive(context)
        if (spokenProfile == null || !com.portal.tv.search.Speaker.isAvailable(context)) {
            spokenLabel.visibility = View.GONE
            spokenRow.visibility = View.GONE
        } else {
            spokenLabel.text = "Speak the AI's replies (${spokenProfile.name}'s profile)"
            val tiles = SearchSettings.SpokenReplies.entries.map { mode ->
                val tile = TextView(context).apply {
                    text = mode.label
                    textSize = 18f
                    typeface = resources.getFont(R.font.inter)
                    gravity = android.view.Gravity.CENTER
                    val pad = (24 * resources.displayMetrics.density).toInt()
                    setPadding(pad, 0, pad, 0)
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    isFocusable = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (12 * resources.displayMetrics.density).toInt() }
                }
                spokenRow.addView(tile)
                mode to tile
            }
            fun styleSpoken() {
                val chosen = SearchSettings.spoken(context, spokenProfile.id)
                tiles.forEach { (mode, tile) ->
                    val on = mode == chosen
                    tile.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                    tile.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
                }
            }
            tiles.forEach { (mode, tile) ->
                tile.setOnClickListener {
                    SearchSettings.setSpoken(context, spokenProfile.id, mode)
                    styleSpoken()
                    if (mode != SearchSettings.SpokenReplies.OFF) {
                        com.portal.tv.search.Speaker.speak(context, "This is how the replies will sound.")
                    } else {
                        com.portal.tv.search.Speaker.stop()
                    }
                }
            }
            styleSpoken()

            // The voice: whatever the TV has for this language, with a sample as each is chosen.
            val voiceLabel = view.findViewById<TextView>(R.id.label_voice)
            val voiceScroll = view.findViewById<View>(R.id.scroll_voice)
            val voiceRow = view.findViewById<android.widget.LinearLayout>(R.id.row_voice)
            voiceLabel.visibility = View.GONE
            voiceScroll.visibility = View.GONE
            com.portal.tv.search.Speaker.voices(context) { voices ->
                if (!isAdded || voices.isEmpty()) return@voices
                voiceLabel.text = "Voice (${voices.size} on this TV)"
                voiceLabel.visibility = View.VISIBLE
                voiceScroll.visibility = View.VISIBLE
                val choices = listOf<android.speech.tts.Voice?>(null) + voices
                val voiceTiles = choices.mapIndexed { index, voice ->
                    val tile = TextView(context).apply {
                        text = if (voice == null) "Default" else com.portal.tv.search.Speaker.label(voice, index)
                        textSize = 18f
                        typeface = resources.getFont(R.font.inter)
                        gravity = android.view.Gravity.CENTER
                        val pad = (22 * resources.displayMetrics.density).toInt()
                        setPadding(pad, 0, pad, 0)
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                        isFocusable = true
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (10 * resources.displayMetrics.density).toInt() }
                    }
                    voiceRow.addView(tile)
                    voice to tile
                }
                fun styleVoices() {
                    val saved = SearchSettings.voice(context, spokenProfile.id)
                    voiceTiles.forEach { (voice, tile) ->
                        val on = (voice?.name) == saved
                        tile.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                        tile.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
                    }
                }
                voiceTiles.forEach { (voice, tile) ->
                    tile.setOnClickListener {
                        SearchSettings.setVoice(context, spokenProfile.id, voice?.name)
                        com.portal.tv.search.Speaker.stop()
                        com.portal.tv.search.Speaker.voiceName = voice?.name
                        com.portal.tv.search.Speaker.speak(context, "Hello, this is how I sound.")
                        styleVoices()
                    }
                }
                styleVoices()
            }
        }

        fun refresh() {
            url.text = SearchSettings.baseUrl(context).ifEmpty { "Not set. Example: https://api.openai.com/v1" }
            model.text = SearchSettings.model(context).ifEmpty { "Not needed for most local servers" }
            contextTile.text = "%,d tokens".format(SearchSettings.contextTokens(context))
            val provider = SearchSettings.webProvider(context)
            webProvider.text = provider.label + when {
                provider == WebProvider.OFF || WebSearch.isReady(provider, SearchSettings.searxngUrl(context), SearchSettings.braveKey(context)) -> ""
                provider == WebProvider.SEARXNG -> "  (add an address)"
                else -> "  (add a key)"
            }
            webSearxng.text = SearchSettings.searxngUrl(context).ifEmpty { "Not set. Example: http://192.168.1.20:8080" }
            webBrave.text = if (SearchSettings.hasBraveKey(context)) "●●●●●●●●●●●●  Saved securely" else "Not set"
            // Only the service in use needs its own setting.
            webSearxng.alpha = if (provider == WebProvider.SEARXNG) 1f else 0.5f
            webBrave.alpha = if (provider == WebProvider.BRAVE) 1f else 0.5f
            key.text = if (SearchSettings.hasApiKey(context)) "●●●●●●●●●●●●  Saved securely" else "Not set (optional)"
            refreshCategories?.invoke()
        }

        fun edit(title: String, current: String, hint: String, secret: Boolean, onSave: (String) -> Unit, onRemove: (() -> Unit)? = null) {
            val input = android.widget.EditText(context).apply {
                setText(current)
                this.hint = hint
                setSingleLine(true)
                inputType = if (secret) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val builder = PortalDialog.Builder(context)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()); refresh() }
                .setNegativeButton("Cancel", null)
            if (onRemove != null) builder.setNeutralButton("Remove") { _, _ -> onRemove(); refresh() }
            builder.show()
        }

        url.setOnClickListener {
            edit("AI server address", SearchSettings.baseUrl(context), "https://api.openai.com/v1", false, { value ->
                val problem = if (value.isEmpty()) null else Endpoint.problem(value)
                if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                else SearchSettings.setBaseUrl(context, value)
            })
        }
        // Ask the server which models it has and offer them as a list; typing a name stays available.
        val typeModel = {
            edit("Model (optional)", SearchSettings.model(context), "gpt-4o-mini", false, { SearchSettings.setModel(context, it) })
        }
        model.setOnClickListener {
            if (!SearchSettings.isAiConfigured(context)) {
                Toast.makeText(context, "Add the server address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val base = SearchSettings.baseUrl(context)
            val apiKey = SearchSettings.apiKey(context)
            Toast.makeText(context, "Asking the server…", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LlmClient.listModels(base, apiKey) }
                if (found == null || found.isEmpty()) {
                    Toast.makeText(context, "The server didn't list its models. Type a name instead", Toast.LENGTH_LONG).show()
                    typeModel()
                    return@launch
                }
                val current = SearchSettings.model(context)
                val labels = listOf("Server default (no name)") + found + "Type a name…"
                val checked = if (current.isEmpty()) 0 else found.indexOf(current).let { if (it >= 0) it + 1 else -1 }
                PortalDialog.Builder(context)
                    .setTitle("Model")
                    .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                        dialog.dismiss()
                        when (which) {
                            0 -> { SearchSettings.setModel(context, ""); refresh() }
                            labels.lastIndex -> typeModel()
                            else -> { SearchSettings.setModel(context, found[which - 1]); refresh() }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        key.setOnClickListener {
            edit("API key (optional)", "", "Paste or type the key", true,
                onSave = { if (it.isNotEmpty()) SearchSettings.setApiKey(context, it) },
                onRemove = { SearchSettings.setApiKey(context, null) }.takeIf { SearchSettings.hasApiKey(context) })
        }
        webProvider.setOnClickListener {
            val choices = WebProvider.entries
            PortalDialog.Builder(context)
                .setTitle("Web search service")
                .setSingleChoiceItems(
                    choices.map { it.label }.toTypedArray(), choices.indexOf(SearchSettings.webProvider(context))
                ) { dialog, which ->
                    SearchSettings.setWebProvider(context, choices[which])
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        webSearxng.setOnClickListener {
            edit("SearXNG address", SearchSettings.searxngUrl(context), "http://192.168.1.20:8080", false, { value ->
                val problem = if (value.isEmpty()) null else Endpoint.problem(value)
                if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                else SearchSettings.setSearxngUrl(context, value)
            })
        }
        webBrave.setOnClickListener {
            edit("Brave Search key", "", "Paste or type the key", true,
                onSave = { if (it.isNotEmpty()) SearchSettings.setBraveKey(context, it) },
                onRemove = { SearchSettings.setBraveKey(context, null) }.takeIf { SearchSettings.hasBraveKey(context) })
        }
        contextTile.setOnClickListener {
            val choices = SearchSettings.CONTEXT_CHOICES
            PortalDialog.Builder(context)
                .setTitle("Context size")
                .setSingleChoiceItems(
                    choices.map { "%,d tokens".format(it) }.toTypedArray(),
                    choices.indexOf(SearchSettings.contextTokens(context))
                ) { dialog, which ->
                    SearchSettings.setContextTokens(context, choices[which])
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        test.setOnClickListener {
            // Testing is also how to try tools again after fixing the server.
            SearchSettings.resetToolsSupport(context)
            if (!SearchSettings.isAiConfigured(context)) {
                testStatus.setTextColor(resources.getColor(R.color.text_secondary, null))
                testStatus.text = Endpoint.problem(SearchSettings.baseUrl(context)) ?: "Add the server address first"
                return@setOnClickListener
            }
            testStatus.setTextColor(resources.getColor(R.color.text_secondary, null))
            testStatus.text = "Testing…"
            val base = SearchSettings.baseUrl(context)
            val name = SearchSettings.model(context)
            val apiKey = SearchSettings.apiKey(context)
            viewLifecycleOwner.lifecycleScope.launch {
                val problem = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    LlmClient.test(base, name, apiKey)
                }
                testStatus.setTextColor(if (problem == null) 0xFF7FE0BD.toInt() else 0xFFFF8A80.toInt())
                testStatus.text = problem ?: "Connected"
            }
        }
        refresh()
    }

    /**
     * Search level for every profile. Changing a kid's level needs the parent PIN, so only a parent
     * can give a kid search or the AI.
     */
    private fun setupSearchLevels(view: View) {
        val context = requireContext()
        val list = view.findViewById<android.widget.LinearLayout>(R.id.search_levels_list)
        val dp = resources.displayMetrics.density
        // Rows are built once and only restyled after a change: rebuilding them would destroy the
        // button that has focus, and Android would send focus to the first item on the page.
        val tiles = mutableListOf<Triple<Profile, SearchLevel, TextView>>()

        fun restyle() {
            tiles.forEach { (profile, level, tile) ->
                val on = level == SearchSettings.level(context, profile)
                tile.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                tile.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
            }
            refreshCategories?.invoke()
        }

        com.portal.tv.repo.ProfileManager.getAll(context).forEach { profile ->
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            row.addView(TextView(context).apply {
                text = profile.name + if (profile.isKids) "  (kid)" else ""
                textSize = 18f
                typeface = resources.getFont(R.font.inter)
                setTextColor(resources.getColor(R.color.text_primary, null))
                layoutParams = android.widget.LinearLayout.LayoutParams((210 * dp).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            SearchLevel.entries.forEach { level ->
                val tile = TextView(context).apply {
                    text = level.label
                    textSize = 17f
                    typeface = resources.getFont(R.font.inter)
                    gravity = android.view.Gravity.CENTER
                    setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
                    minHeight = (44 * dp).toInt()
                    isFocusable = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (8 * dp).toInt() }
                    setOnClickListener {
                        if (level == SearchSettings.level(context, profile)) return@setOnClickListener
                        val apply = {
                            SearchSettings.setLevel(context, profile.id, level)
                            if (level.usesAi && !SearchSettings.isAiConfigured(context)) {
                                Toast.makeText(context, "Set up the AI server on the Search page to use it", Toast.LENGTH_LONG).show()
                            }
                            restyle()
                        }
                        when {
                            !profile.isKids -> apply()
                            ParentPin.isSet(context) -> PinDialogs.verify(context, "Parent PIN to change ${profile.name}'s search") { apply() }
                            else -> PinDialogs.create(
                                context,
                                message = "A parent PIN is needed to change a kid's search. ${ParentPin.MIN_LENGTH} digits.",
                                onSet = { apply() }
                            )
                        }
                    }
                }
                tiles += Triple(profile, level, tile)
                row.addView(tile)
            }
            list.addView(row)
        }
        restyle()
    }

    private var refreshCategories: (() -> Unit)? = null

    private var refreshOverlayPermission: (() -> Unit)? = null
    private var refreshReminderOverlay: (() -> Unit)? = null
    private var refreshExtensions: (() -> Unit)? = null
    private var extensionsPage: ExtensionsPage? = null
    /** The category to show when this screen is drawn again, after something opened from it was closed. */
    private var returnToCategory = 0
    private var extensionsSummary = ""

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission?.invoke() // back from the system's permission screen
        refreshReminderOverlay?.invoke()
        refreshExtensions?.invoke() // an extension may have been installed or removed
    }

    /**
     * The "Display over other apps" permission lets the countdown show on top of an app a kid has
     * open. Without it a kid still can't use more time than allowed, but they won't see the clock.
     */
    private fun setupOverlayPermission(view: View) {
        val context = requireContext()
        val tile = view.findViewById<TextView>(R.id.opt_overlay_permission)
        val hint = view.findViewById<TextView>(R.id.overlay_permission_hint)
        val adb = "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"

        fun refresh() {
            val granted = android.provider.Settings.canDrawOverlays(context)
            tile.text = if (granted) "Timer over apps: On" else "Timer over apps: Off. Select to allow"
            hint.text = if (granted) "The time left shows on top of whatever app a kid has open."
            else "Lets Portal show the time left on top of an app, and bring a kid back when time is up."
        }
        tile.setOnClickListener {
            if (android.provider.Settings.canDrawOverlays(context)) return@setOnClickListener
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            if (intent.resolveActivity(context.packageManager) != null) startActivity(intent)
            else PortalDialog.Builder(context)
                .setTitle("Allow display over other apps")
                .setMessage("This TV has no screen for this permission. From a computer, run:\n\n$adb")
                .setPositiveButton("OK", null)
                .show()
        }
        refreshOverlayPermission = ::refresh
        refresh()
    }

    /**
     * One row per kid profile with Off and On for whether they may pick their own everyday video. Searching
     * for videos stays with parents either way. Changing it needs the parent PIN, as with a kid's search.
     */
    private fun setupKidsVideoAccess(view: View) {
        val context = requireContext()
        val section = view.findViewById<View>(R.id.kids_video_section)
        val list = view.findViewById<android.widget.LinearLayout>(R.id.kids_video_list)
        val dp = resources.displayMetrics.density
        if (com.portal.tv.repo.ProfileManager.isActiveKids(context)) {
            section.visibility = View.GONE
            return
        }
        val kids = com.portal.tv.repo.ProfileManager.getAll(context).filter { it.isKids }
        if (kids.isEmpty()) {
            section.visibility = View.GONE
            return
        }
        val tiles = mutableListOf<Triple<com.portal.tv.data.Profile, Boolean, TextView>>()
        fun restyle() = tiles.forEach { (kid, on, tile) ->
            val chosen = BackgroundSettings.kidCanPickVideo(context, kid.id) == on
            tile.setBackgroundResource(if (chosen) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
            tile.setTextColor(resources.getColor(if (chosen) R.color.text_primary else R.color.text_secondary, null))
        }
        kids.forEach { kid ->
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            row.addView(TextView(context).apply {
                text = kid.name
                textSize = 18f
                typeface = resources.getFont(R.font.inter)
                setTextColor(resources.getColor(R.color.text_primary, null))
                layoutParams = android.widget.LinearLayout.LayoutParams((210 * dp).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            listOf("Off" to false, "On" to true).forEach { (label, on) ->
                val tile = TextView(context).apply {
                    text = label
                    textSize = 17f
                    typeface = resources.getFont(R.font.inter)
                    gravity = android.view.Gravity.CENTER
                    setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
                    minHeight = (44 * dp).toInt()
                    isFocusable = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (8 * dp).toInt() }
                    setOnClickListener {
                        if (BackgroundSettings.kidCanPickVideo(context, kid.id) == on) return@setOnClickListener
                        val apply = { BackgroundSettings.setKidCanPickVideo(context, kid.id, on); restyle() }
                        if (ParentPin.isSet(context)) PinDialogs.verify(context, "Parent PIN to change ${kid.name}'s videos") { apply() }
                        else PinDialogs.create(
                            context,
                            message = "A parent PIN is needed to change what a kid can do. ${ParentPin.MIN_LENGTH} digits.",
                            onSet = { apply() }
                        )
                    }
                }
                tiles += Triple(kid, on, tile)
                row.addView(tile)
            }
            list.addView(row)
        }
        restyle()
    }

    /**
     * One row per kid profile showing its schedule. Editing needs the parent PIN, and a PIN is
     * required first so a kid can't be locked out with no way for a parent to unlock.
     */
    private fun setupKidsSchedule(view: View) {
        val context = requireContext()
        val list = view.findViewById<android.widget.LinearLayout>(R.id.kids_schedule_list)
        val dp = resources.displayMetrics.density

        fun refresh() {
            list.removeAllViews()
            val kids = com.portal.tv.repo.ProfileManager.getAll(context).filter { it.isKids }
            if (kids.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = "No kid profiles yet"
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                })
                return
            }
            kids.forEach { kid ->
                list.addView(TextView(context).apply {
                    text = "${kid.name}  ·  " +
                        com.portal.tv.repo.KidsSchedule.summary(com.portal.tv.repo.KidsSchedule.config(context, kid.id))
                    textSize = 18f
                    typeface = resources.getFont(R.font.inter)
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
                    minHeight = (48 * dp).toInt()
                    setBackgroundResource(R.drawable.bg_settings_tile)
                    isFocusable = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * dp).toInt() }
                    setOnClickListener {
                        val edit = { KidsScheduleDialogs.edit(context, kid) { refresh() } }
                        if (ParentPin.isSet(context)) PinDialogs.verify(context, onVerified = edit)
                        else PinDialogs.create(
                            context,
                            message = "A parent PIN is needed to change a kid's schedule and to unlock it. " +
                                "${ParentPin.MIN_LENGTH} digits.",
                            onSet = edit
                        )
                    }
                })
            }
        }
        refresh()
    }

    /**
     * The two-pane layout: moving down the category list on the left shows that category's
     * settings on the right; right moves into them, left or Back returns to the list. Each
     * category shows a one-line summary of its current values.
     */
    private fun setupPanes(view: View) {
        val context = requireContext()

        class Category(val itemId: Int, val pageId: Int, val summary: () -> String)

        val categories = listOf(
            Category(R.id.cat_background, R.id.page_background) {
                when (BackgroundSettings.getMode(context)) {
                    "off" -> "Off"
                    com.portal.tv.extensions.BackgroundChoice.MODE ->
                        "Extension" + (BackgroundSettings.getExtension(context)?.let { pkg ->
                            com.portal.tv.extensions.ExtensionRegistry.find(context, pkg)?.name?.let { " · $it" }
                        } ?: "")
                    "shader" -> "Shader · " + ShaderStyles.find(BackgroundSettings.getShaderStyle(context)).label +
                        " · " + ShaderPalettes.find(BackgroundSettings.getShaderColor(context)).label
                    else -> {
                        val video = BackgroundRepository.availableVideos(context)
                            .firstOrNull { it.asset == BackgroundRepository.selectedVideo(context) }
                        if (BackgroundRepository.isRandom(context)) "Video · Random" + (video?.let { " (${it.label} today)" } ?: "")
                        else "Video" + (video?.let { " · ${it.label}" } ?: "")
                    }
                }
            },
            Category(R.id.cat_videos, R.id.page_videos) {
                val everyday = if (BackgroundRepository.isRandom(context)) "Random"
                else BackgroundRepository.availableVideos(context)
                    .firstOrNull { it.asset == BackgroundRepository.selectedVideo(context) }?.label ?: "Default"
                everyday + when {
                    com.portal.tv.repo.ProfileManager.isActiveKids(context) -> ""
                    !BackgroundSettings.getHolidayVideos(context) -> " · holidays off"
                    else -> {
                        val count = HolidayStore.load(context).size
                        " · $count holiday" + if (count == 1) "" else "s"
                    }
                }
            },
            Category(R.id.cat_apps, R.id.page_apps) {
                val layout = when (AppSettings.getAppLayout(context)) {
                    AppSettings.LAYOUT_ICONS -> "Icons"
                    AppSettings.LAYOUT_LIST -> "List"
                    else -> "Icon + title"
                }
                val hidden = AppSettings.hiddenApps(context).size
                layout + (if (AppSettings.isGrouped(context)) " · grouped" else "") +
                    (if (hidden > 0) " · $hidden hidden" else "")
            },
            Category(R.id.cat_search, R.id.page_search) {
                val profile = com.portal.tv.repo.ProfileManager.getActive(context)
                val level = profile?.let { SearchSettings.level(context, it) } ?: SearchLevel.APPS
                level.label + if (level.usesAi && !SearchSettings.isAiConfigured(context)) " · AI not set up" else ""
            },
            Category(R.id.cat_weather, R.id.page_weather) {
                (if (AppSettings.isMetric(context)) "°C" else "°F") +
                    " · " + (WeatherRepository.manualCity(context) ?: "Automatic")
            },
            Category(R.id.cat_screensaver, R.id.page_screensaver) {
                val minutes = ScreensaverSettings.idleMinutes(context)
                if (minutes == 0) "Off" else "After $minutes min"
            },
            Category(R.id.cat_reminders, R.id.page_reminders) {
                val profile = com.portal.tv.repo.ProfileManager.getActive(context)
                val count = profile?.let { com.portal.tv.reminders.ReminderStore.forProfile(context, it.id).size } ?: 0
                when (count) { 0 -> "None yet"; 1 -> "1 reminder"; else -> "$count reminders" }
            },
            Category(R.id.cat_extensions, R.id.page_extensions) { extensionsSummary },
            Category(R.id.cat_family, R.id.page_family) {
                val count = BirthdayStore.all(context).size
                val birthdays = if (count == 1) "1 birthday" else "$count birthdays"
                birthdays + " · PIN " + if (ParentPin.isSet(context)) "set" else "not set"
            },
            Category(R.id.cat_system, R.id.page_system) {
                if (com.portal.tv.update.Updates.updateAvailable(context, com.portal.tv.BuildConfig.VERSION_NAME)) "Update available" else ""
            }
        )
        // A kid gets only what is theirs to choose: the background, how the apps are laid out, and
        // the weather units. Search, the screen saver, family and system settings stay with parents,
        // as do hiding apps and the weather location, which is the whole TV's.
        val kid = com.portal.tv.repo.ProfileManager.isActiveKids(context)
        val kidId = com.portal.tv.repo.ProfileManager.getActiveId(context)
        // Videos are the parents', unless they have let this kid pick their own everyday video.
        val kidPicksVideo = kid && kidId != null && BackgroundSettings.kidCanPickVideo(context, kidId)
        val allowed = if (kid) {
            setOfNotNull(R.id.cat_background, R.id.cat_apps, R.id.cat_weather, R.id.cat_videos.takeIf { kidPicksVideo })
        } else null
        categories.filter { allowed != null && it.itemId !in allowed }.forEach {
            view.findViewById<View>(it.itemId).visibility = View.GONE
            view.findViewById<View>(it.pageId).visibility = View.GONE
        }
        if (kid) {
            listOf(R.id.label_manage_apps, R.id.row_manage_apps, R.id.label_location, R.id.row_location)
                .forEach { view.findViewById<View>(it).visibility = View.GONE }
        }
        val shown = if (allowed == null) categories else categories.filter { it.itemId in allowed }
        val items = shown.map { view.findViewById<View>(it.itemId) }
        val pages = shown.map { view.findViewById<View>(it.pageId) }
        var active = 0

        fun refresh() {
            shown.forEachIndexed { i, c ->
                val item = items[i]
                val summary = item.findViewWithTag<TextView>("summary")
                summary.text = c.summary()
                summary.visibility = if (summary.text.isEmpty()) View.GONE else View.VISIBLE
                val on = i == active
                item.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                val tone = context.resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null)
                item.findViewWithTag<TextView>("summary").setTextColor(tone)
                item.findViewWithTag<android.widget.ImageView>("icon")?.imageTintList =
                    android.content.res.ColorStateList.valueOf(tone)
                pages[i].visibility = if (on) View.VISIBLE else View.GONE
            }
        }

        fun inPage(v: View?): Boolean {
            var p = v?.parent
            while (p != null) {
                if (pages.any { it === p }) return true
                p = p.parent
            }
            return false
        }

        // Back from a setting goes to the category list first, then out of Settings.
        val back = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { items[active].requestFocus() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)

        val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
            // The window's focus changes keep arriving after this screen is gone.
            if (!isAdded || view == null) return@OnGlobalFocusChangeListener
            val index = items.indexOfFirst { it === new }
            if (index >= 0 && old != null && inPage(old)) {
                // Focus is leaving a page for the category list. Left should go to the category
                // that owns the page (not whichever one is level with the control); Up and Down
                // at the edge of a page should stay on the page.
                val from = android.graphics.Rect().also { old.getGlobalVisibleRect(it) }
                val to = android.graphics.Rect().also { new!!.getGlobalVisibleRect(it) }
                val level = to.top < from.bottom && to.bottom > from.top
                if (level) { if (index != active) items[active].requestFocus() }
                else old.requestFocus()
                if (level && index == active) { /* fall through to update */ } else return@OnGlobalFocusChangeListener
            }
            if (index >= 0) active = index
            back.isEnabled = inPage(new)
            refresh()
        }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        // Remove it when the view leaves the window. By onDestroyView the view is already detached,
        // and a detached view's observer isn't the one holding the listener.
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
            }
        })

        // Selecting a category moves into its settings.
        items.forEachIndexed { i, item ->
            item.setOnClickListener {
                active = i
                refresh()
                pages[i].requestFocus()
            }
        }
        refreshCategories = ::refresh
        refresh()
        val updatesAt = if (arguments?.getBoolean(ARG_UPDATES) == true) shown.indexOfFirst { it.itemId == R.id.cat_system } else -1
        if (updatesAt >= 0) {
            // Straight to the update: its Download button if there is one to download, otherwise the check.
            active = updatesAt
            refresh()
            val install = view.findViewById<View>(R.id.opt_update_install)
            val target = if (install.visibility == View.VISIBLE) install else view.findViewById(R.id.opt_update_check)
            target.post { target.requestFocus() }
        } else {
            val back = shown.indexOfFirst { it.itemId == returnToCategory }.takeIf { returnToCategory != 0 && it >= 0 } ?: 0
            active = back
            refresh()
            items[back].post { items[back].requestFocus() }
        }
    }

    /**
     * Screen saver options: idle time, what it does at night, which videos it plays, and a
     * preview button, and whether it also runs over other apps. That system screen saver reads the same
     * night and video options.
     */
    private fun setupScreensaver(view: View) {
        val context = requireContext()
        val dp = resources.displayMetrics.density

        fun tile(label: String) = TextView(context).apply {
            text = label
            textSize = 18f
            typeface = resources.getFont(R.font.inter)
            gravity = android.view.Gravity.CENTER
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
            minHeight = (48 * dp).toInt()
            isFocusable = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (12 * dp).toInt() }
        }

        fun style(tile: TextView, on: Boolean) {
            tile.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
            tile.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
        }

        /** A row of tiles where exactly one is selected. */
        fun <T> choices(rowId: Int, options: List<Pair<String, T>>, current: () -> T, onPick: (T) -> Unit) {
            val row = view.findViewById<android.widget.LinearLayout>(rowId)
            val tiles = options.map { (label, _) -> tile(label).also { row.addView(it) } }
            fun refresh() = tiles.forEachIndexed { i, t -> style(t, options[i].second == current()) }
            tiles.forEachIndexed { i, t ->
                t.setOnClickListener { onPick(options[i].second); refresh() }
            }
            refresh()
        }

        // Over other apps the TV starts the screen saver by its own clock, so "Start after" sets that too. Set below,
        // once the "Also over other apps" tile exists, so it can refresh.
        var startTimeChanged: () -> Unit = {}
        choices(
            R.id.screensaver_idle_row,
            listOf("Off" to 0, "5 min" to 5, "10 min" to 10, "30 min" to 30),
            { ScreensaverSettings.idleMinutes(context) },
            { minutes ->
                ScreensaverSettings.setIdleMinutes(context, minutes)
                val app = context.applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.portal.tv.adb.SelfSetup.syncStartAfter(app, minutes)
                    }
                    startTimeChanged()
                    if (result is com.portal.tv.adb.SelfSetup.Result.Failed) {
                        Toast.makeText(
                            context,
                            "Couldn't change the TV's start time: ${result.why} Turn on Wireless debugging and pick it again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
        choices(
            R.id.screensaver_night_row,
            listOf(
                "Normal" to ScreensaverSettings.NIGHT_NORMAL,
                "Dim" to ScreensaverSettings.NIGHT_DIM,
                "Black" to ScreensaverSettings.NIGHT_BLACK
            ),
            { ScreensaverSettings.night(context) },
            { ScreensaverSettings.setNight(context, it) }
        )

        choices(
            R.id.screensaver_sound_row,
            com.portal.tv.settings.VideoSound.levels,
            { ScreensaverSettings.sound(context) },
            { ScreensaverSettings.setSound(context, it) }
        )

        // The videos are chosen in a browser of their own, with a preview; this button says how many are on.
        val videosTile = view.findViewById<TextView>(R.id.opt_screensaver_videos)
        val total = BackgroundRepository.availableVideos(context).size
        val on = ScreensaverSettings.enabledVideos(context).size
        videosTile.text = "Choose videos…   $on of $total included"
        videosTile.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, ScreensaverVideosFragment())
                .addToBackStack("saver_videos")
                .commit()
        }

        // The screen saver has its own look, whatever the profile's background is: videos, or a shader of colors with
        // its own style and color. The video list and sound only apply to videos.
        val refreshSaverShader = bindShaderPickers(
            view,
            ShaderPickers(
                R.id.saver_shader_group, R.id.saver_shader_choose, R.id.saver_shader_summary, "Screen saver shader",
                visible = { ScreensaverSettings.look(context) == ScreensaverSettings.LOOK_SHADER },
                color = { ScreensaverSettings.shaderColor(context) },
                setColor = { ScreensaverSettings.setShaderColor(context, it) },
                style = { ScreensaverSettings.shaderStyle(context) },
                setStyle = { ScreensaverSettings.setShaderStyle(context, it) },
                cycle = { ScreensaverSettings.shaderCycle(context) },
                setCycle = { ScreensaverSettings.setShaderCycle(context, it) },
                changed = {}
            )
        )
        fun refreshSaverMode() {
            val shader = ScreensaverSettings.look(context) == ScreensaverSettings.LOOK_SHADER
            view.findViewById<View>(R.id.screensaver_videos_title).visibility = if (shader) View.GONE else View.VISIBLE
            view.findViewById<View>(R.id.opt_screensaver_videos).visibility = if (shader) View.GONE else View.VISIBLE
            for (id in listOf(R.id.screensaver_sound_title, R.id.screensaver_sound_row, R.id.screensaver_sound_note)) {
                view.findViewById<View>(id).visibility = if (shader) View.GONE else View.VISIBLE
            }
            refreshSaverShader()
        }
        choices(
            R.id.screensaver_look_row,
            listOf("Videos" to ScreensaverSettings.LOOK_VIDEO, "Shaders" to ScreensaverSettings.LOOK_SHADER),
            { ScreensaverSettings.look(context) },
            { ScreensaverSettings.setLook(context, it); refreshSaverMode() }
        )
        refreshSaverMode()

        view.findViewById<TextView>(R.id.opt_screensaver_preview).setOnClickListener {
            (activity as? com.portal.tv.PortalActivity)?.startScreensaver(preview = true)
        }

        // Over other apps the system starts Portal's screen saver once the TV has been idle for its own screen saver
        // time. Turning that on or off goes through Portal's connection to the TV's debug service.
        val systemTile = view.findViewById<TextView>(R.id.opt_screensaver_system)
        val systemNote = view.findViewById<TextView>(R.id.screensaver_system_note)
        fun refreshSystem() {
            val on = com.portal.tv.adb.SelfSetup.isSystemScreensaver(context)
            systemTile.text = if (on) "Also over other apps: On" else "Also over other apps: Off"
            style(systemTile, on)
            systemNote.text = when {
                on -> "Portal's screen saver also starts when another app has been idle for the Start after time. " +
                    "Select to give the TV's own screen saver back."
                ScreensaverSettings.idleMinutes(context) == 0 ->
                    "Start after is Off, so the screen saver won't start. Pick a time above to turn it on."
                else -> "Without this the screen saver starts only from Portal's home screen. Select to turn it on. " +
                    "It needs Wireless debugging on, and the TV may ask you to allow it."
            }
        }
        refreshSystem()
        startTimeChanged = { refreshSystem() }
        systemTile.setOnClickListener {
            val turnOn = !com.portal.tv.adb.SelfSetup.isSystemScreensaver(context)
            if (turnOn && ScreensaverSettings.idleMinutes(context) == 0) {
                Toast.makeText(context, "Pick a Start after time first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val app = context.applicationContext
            Toast.makeText(context, "Working…", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    when (val c = com.portal.tv.adb.SelfSetup.connect(app)) {
                        is com.portal.tv.adb.SelfSetup.Result.Failed -> c
                        else -> if (turnOn) com.portal.tv.adb.SelfSetup.enableSystemScreensaver(app)
                        else com.portal.tv.adb.SelfSetup.disableSystemScreensaver(app)
                    }
                }
                refreshSystem()
                if (result is com.portal.tv.adb.SelfSetup.Result.Failed) {
                    Toast.makeText(context, "Couldn't change it: ${result.why}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Weather location: automatic (from the connection's IP) or a city searched by name or zip.
     */
    private fun setupWeatherLocation(view: View) {
        val context = requireContext()
        val auto = view.findViewById<TextView>(R.id.opt_loc_auto)
        val city = view.findViewById<TextView>(R.id.opt_loc_city)

        fun highlight() {
            val manual = WeatherRepository.manualCity(context)
            city.text = manual?.let { "City: $it" } ?: "Choose city…"
            for ((tile, on) in listOf(auto to (manual == null), city to (manual != null))) {
                tile.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                tile.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
            }
        }

        auto.setOnClickListener {
            WeatherRepository.useAutomaticLocation(context)
            highlight()
        }
        city.setOnClickListener { LocationPicker.show(this) { highlight() } }
        highlight()
    }

    private fun setupUnits(view: View) {
        val metric = view.findViewById<TextView>(R.id.opt_metric)
        val imperial = view.findViewById<TextView>(R.id.opt_imperial)

        fun highlight() {
            val isMetric = AppSettings.isMetric(requireContext())
            metric.setBackgroundResource(
                if (isMetric) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile
            )
            metric.setTextColor(
                resources.getColor(
                    if (isMetric) R.color.text_primary else R.color.text_secondary,
                    null
                )
            )
            imperial.setBackgroundResource(
                if (!isMetric) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile
            )
            imperial.setTextColor(
                resources.getColor(
                    if (!isMetric) R.color.text_primary else R.color.text_secondary,
                    null
                )
            )
        }

        metric.setOnClickListener {
            AppSettings.setWeatherUnits(requireContext(), "metric")
            Log.d("Portal", "settings: units=metric")
            highlight()
        }
        imperial.setOnClickListener {
            AppSettings.setWeatherUnits(requireContext(), "imperial")
            Log.d("Portal", "settings: units=imperial")
            highlight()
        }

        highlight()

        // Weather alerts: show or hide the National Weather Service alerts.
        val alertsOn = view.findViewById<TextView>(R.id.opt_alerts_on)
        val alertsOff = view.findViewById<TextView>(R.id.opt_alerts_off)
        fun styleAlerts() {
            val on = AppSettings.showWeatherAlerts(requireContext())
            for ((tile, selected) in listOf(alertsOn to on, alertsOff to !on)) {
                tile.setBackgroundResource(if (selected) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                tile.setTextColor(resources.getColor(if (selected) R.color.text_primary else R.color.text_secondary, null))
            }
        }
        alertsOn.setOnClickListener { AppSettings.setShowWeatherAlerts(requireContext(), true); styleAlerts() }
        alertsOff.setOnClickListener { AppSettings.setShowWeatherAlerts(requireContext(), false); styleAlerts() }
        styleAlerts()

        // Severe alerts over apps: a red banner on top of whatever is open, for the whole TV.
        val severeOn = view.findViewById<TextView>(R.id.opt_severe_on)
        val severeOff = view.findViewById<TextView>(R.id.opt_severe_off)
        val severeHint = view.findViewById<TextView>(R.id.severe_hint)
        fun styleSevere() {
            val context = requireContext()
            val on = com.portal.tv.repo.WeatherAlerts.overApps(context)
            for ((tile, selected) in listOf(severeOn to on, severeOff to !on)) {
                tile.setBackgroundResource(if (selected) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                tile.setTextColor(resources.getColor(if (selected) R.color.text_primary else R.color.text_secondary, null))
            }
            val allowed = android.provider.Settings.canDrawOverlays(context)
            severeHint.text = when {
                !on -> "Off: severe alerts show only on the home screen and the weather page."
                !allowed -> "Needs permission to display over other apps. Allow it under Settings, Family, " +
                    "\"Timer over apps\" (it covers both)."
                else -> "A red banner and a tone, for about 30 seconds, for each new severe alert. US only."
            }
        }
        severeOn.setOnClickListener {
            com.portal.tv.repo.WeatherAlerts.setOverApps(requireContext(), true)
            com.portal.tv.alerts.SevereAlertService.ensureRunning(requireContext())
            styleSevere()
        }
        severeOff.setOnClickListener {
            com.portal.tv.repo.WeatherAlerts.setOverApps(requireContext(), false)
            com.portal.tv.alerts.SevereAlertService.stop(requireContext())
            styleSevere()
        }
        styleSevere()
    }

    private fun setupBackground(view: View) {
        val off = view.findViewById<TextView>(R.id.opt_bg_off)
        val shader = view.findViewById<TextView>(R.id.opt_bg_shader)
        val video = view.findViewById<TextView>(R.id.opt_bg_video)
        val extension = view.findViewById<TextView>(R.id.opt_bg_extension)

        fun highlight() {
            val mode = BackgroundSettings.getMode(requireContext())
            listOf(off to "off", shader to "shader", video to "video", extension to com.portal.tv.extensions.BackgroundChoice.MODE).forEach { (tv, m) ->
                val selected = mode == m
                tv.setBackgroundResource(
                    if (selected) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile
                )
                tv.setTextColor(
                    resources.getColor(
                        if (selected) R.color.text_primary else R.color.text_secondary,
                        null
                    )
                )
            }
            view.findViewById<View>(R.id.background_video_pointer).visibility =
                if (mode == "video") View.VISIBLE else View.GONE
            refreshShaderColors?.invoke()
        }

        off.setOnClickListener {
            BackgroundSettings.setMode(requireContext(), "off")
            Log.d("Portal", "settings: background=off")
            highlight()
            onBackgroundChanged?.invoke()
        }
        shader.setOnClickListener {
            BackgroundSettings.setMode(requireContext(), "shader")
            Log.d("Portal", "settings: background=shader")
            highlight()
            onBackgroundChanged?.invoke()
        }
        video.setOnClickListener {
            BackgroundSettings.setMode(requireContext(), "video")
            Log.d("Portal", "settings: background=video")
            highlight()
            onBackgroundChanged?.invoke()
        }

        // "Extension" is offered when an extension that an adult allowed to draw the background is installed (never for kids).
        val context = requireContext()
        val kid = com.portal.tv.repo.ProfileManager.isActiveKids(context)
        viewLifecycleOwner.lifecycleScope.launch {
            val drawers = if (kid) emptyList() else withContext(Dispatchers.IO) {
                com.portal.tv.extensions.ExtensionRegistry.scan(context).filter {
                    com.portal.tv.extensions.Capability.BACKGROUND in it.granted && it.backgroundService != null
                }
            }
            val inUse = BackgroundSettings.getMode(context) == com.portal.tv.extensions.BackgroundChoice.MODE
            extension.visibility = if (drawers.isNotEmpty() || (inUse && !kid)) View.VISIBLE else View.GONE
            extension.setOnClickListener {
                val choose = { pkg: String ->
                    BackgroundSettings.setExtension(context, pkg)
                    BackgroundSettings.setMode(context, com.portal.tv.extensions.BackgroundChoice.MODE)
                    Log.d("Portal", "settings: background=extension $pkg")
                    highlight()
                    onBackgroundChanged?.invoke()
                }
                when (drawers.size) {
                    0 -> Toast.makeText(context, "No extension is allowed to draw the background. Allow one under Extensions.", Toast.LENGTH_LONG).show()
                    1 -> choose(drawers[0].packageName)
                    else -> PortalDialog.Builder(context)
                        .setTitle("Background drawn by")
                        .setItems(drawers.map { it.name }.toTypedArray()) { _, which -> choose(drawers[which].packageName) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        highlight()
    }

    /** "Set PIN" until a parent PIN exists, then "Change PIN" (asks for the current one first). */
    private fun setupPin(view: View) {
        val option = view.findViewById<TextView>(R.id.opt_pin)
        val remove = view.findViewById<TextView>(R.id.opt_remove_pin)
        fun refresh() {
            val set = ParentPin.isSet(requireContext())
            option.text = if (set) "Change PIN" else "Set PIN"
            // Dimmed rather than hidden: a button that vanishes while focused sends focus to the first item.
            remove.alpha = if (set) 1f else 0.4f
        }
        option.setOnClickListener {
            val context = requireContext()
            if (ParentPin.isSet(context)) {
                PinDialogs.change(context) { refresh() }
            } else {
                PinDialogs.create(context, onSet = { refresh() })
            }
        }
        remove.setOnClickListener {
            val context = requireContext()
            if (ParentPin.isSet(context)) PinDialogs.remove(context) { refresh() }
        }
        refresh()
    }

    /**
     * Updates: ask GitHub for the newest release, say what's new, and download and install it. The check runs by itself
     * when Settings opens, at most every few hours; the download and install only when the person asks.
     */
    private fun setupUpdates(view: View) {
        val context = requireContext()
        val app = context.applicationContext
        val installed = com.portal.tv.BuildConfig.VERSION_NAME
        val status = view.findViewById<TextView>(R.id.update_status)
        val check = view.findViewById<TextView>(R.id.opt_update_check)
        val notes = view.findViewById<TextView>(R.id.opt_update_notes)
        val install = view.findViewById<TextView>(R.id.opt_update_install)
        var busy = false

        // Check automatically: On or Off.
        val dp = resources.displayMetrics.density
        val autoRow = view.findViewById<android.widget.LinearLayout>(R.id.update_auto_row)
        val autoTiles = listOf("On" to true, "Off" to false).map { (label, value) ->
            TextView(context).apply {
                text = label
                textSize = 18f
                typeface = resources.getFont(R.font.inter)
                gravity = android.view.Gravity.CENTER
                setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
                minHeight = (48 * dp).toInt()
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (12 * dp).toInt() }
                autoRow.addView(this)
            } to value
        }
        fun styleAuto() = autoTiles.forEach { (tile, value) ->
            val on = value == com.portal.tv.update.UpdateChecks.enabled(app)
            tile.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
            tile.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
        }
        autoTiles.forEach { (tile, value) ->
            tile.setOnClickListener { com.portal.tv.update.UpdateChecks.setEnabled(app, value); styleAuto() }
        }
        styleAuto()

        fun refresh(message: String? = null) {
            val release = com.portal.tv.update.Updates.lastRelease(app)
            val available = release != null && com.portal.tv.update.Updates.isNewer(release.version, installed)
            notes.visibility = if (available) View.VISIBLE else View.GONE
            install.visibility = if (available) View.VISIBLE else View.GONE
            if (release != null && available) {
                val mb = if (release.apkBytes > 0) " (${release.apkBytes / (1024 * 1024)} MB)" else ""
                install.text = "Download and install ${release.version}$mb"
            }
            val checked = com.portal.tv.update.Updates.lastCheckedAt(app)
            status.text = message ?: when {
                available -> "Version ${release!!.version} is available. You have $installed."
                checked == 0L -> "You have version $installed."
                else -> "Version $installed is up to date. Checked " +
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        checked, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                    ).toString().lowercase() + "."
            }
        }
        refresh()

        fun check(manual: Boolean) {
            if (busy) return
            busy = true
            if (manual) refresh("Checking for updates…")
            viewLifecycleOwner.lifecycleScope.launch {
                val result = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.portal.tv.update.UpdateClient.latest(app) }
                }
                busy = false
                result.onSuccess {
                    com.portal.tv.update.Updates.saveCheck(app, it)
                    refresh()
                }.onFailure { refresh(if (manual) "Couldn't check for updates: ${it.message}" else null) }
            }
        }
        check.setOnClickListener { check(manual = true) }
        // If Android's install is cancelled or fails after it has the file, say so here.
        com.portal.tv.update.UpdateInstaller.onFailed = { message ->
            busy = false
            refresh(message)
        }
        viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                com.portal.tv.update.UpdateInstaller.onFailed = null
            }
        })
        if (com.portal.tv.update.UpdateChecks.enabled(app) &&
            System.currentTimeMillis() - com.portal.tv.update.Updates.lastCheckedAt(app) > 6 * 60 * 60 * 1000L
        ) check(manual = false)

        notes.setOnClickListener {
            val release = com.portal.tv.update.Updates.lastRelease(app) ?: return@setOnClickListener
            PortalDialog.Builder(context)
                .setTitle("What's new in ${release.version}")
                .setMessage(com.portal.tv.update.Updates.tidyNotes(release.notes))
                .setPositiveButton("Close", null)
                .show()
        }

        install.setOnClickListener {
            val release = com.portal.tv.update.Updates.lastRelease(app) ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            busy = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    status.text = "Downloading ${release.version}…"
                    val file = com.portal.tv.update.UpdateClient.download(app, release) { done, total ->
                        val percent = if (total > 0) " ${(done * 100 / total).toInt()}%" else ""
                        status.post { status.text = "Downloading ${release.version}…$percent" }
                    }
                    if (!com.portal.tv.update.UpdateInstaller.canInstall(app)) {
                        // Setup normally allows this. If it didn't, do it now over the debug connection.
                        status.text = "Allowing Portal to install updates…"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (com.portal.tv.adb.SelfSetup.connect(app) is com.portal.tv.adb.SelfSetup.Result.Ok) {
                                com.portal.tv.adb.SelfSetup.allowInstalls(app)
                            }
                        }
                    }
                    if (!com.portal.tv.update.UpdateInstaller.canInstall(app)) {
                        refresh(
                            "Portal isn't allowed to install apps yet. Turn on Wireless debugging and try again, or allow " +
                                "Portal under the TV's Settings, Apps, Special app access, Install unknown apps."
                        )
                        return@launch
                    }
                    status.text = "Installing ${release.version}. Portal will restart…"
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.portal.tv.update.UpdateInstaller.install(app, file)
                    }
                } catch (e: java.io.IOException) {
                    refresh("Couldn't update: ${e.message}")
                } finally {
                    busy = false
                }
            }
        }
    }

    /** The About card: which version is installed (handy when updating the TV), and a way to say thanks. */
    private fun setupAbout(view: View) {
        view.findViewById<TextView>(R.id.opt_restore_launcher).setOnClickListener {
            val context = requireContext()
            PortalDialog.Builder(context)
                .setTitle("Restore the TV's own home screen?")
                .setMessage(
                    "This turns the TV's own launcher and screen saver back on, so the Home button opens it again. " +
                        "It needs Wireless debugging on, and the TV may ask you to allow it. " +
                        "From a computer you can run scripts/install.sh --restore instead."
                )
                .setPositiveButton("Restore") { _, _ ->
                    val app = context.applicationContext
                    Toast.makeText(context, "Restoring…", Toast.LENGTH_SHORT).show()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            when (val c = com.portal.tv.adb.SelfSetup.connect(app)) {
                                is com.portal.tv.adb.SelfSetup.Result.Failed -> c
                                else -> com.portal.tv.adb.SelfSetup.restore(app)
                            }
                        }
                        Toast.makeText(
                            context,
                            if (result is com.portal.tv.adb.SelfSetup.Result.Failed)
                                "Couldn't restore: ${result.why}"
                            else "Done. The Home button opens the TV's own home screen again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        view.findViewById<TextView>(R.id.opt_setup).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, OnboardingFragment.forRerun())
                .addToBackStack(null)
                .commit()
        }
        view.findViewById<TextView>(R.id.opt_about).setOnClickListener {
            val context = requireContext()
            val card = LayoutInflater.from(context).inflate(R.layout.dialog_about, null)
            val build = if (com.portal.tv.BuildConfig.DEBUG) " (debug)" else ""
            card.findViewById<TextView>(R.id.about_version).text =
                "Version ${com.portal.tv.BuildConfig.VERSION_NAME}$build"
            card.findViewById<android.widget.ImageView>(R.id.about_qr).setImageBitmap(
                QrCode.bitmap(SUPPORT_URL, 240)
            )
            card.findViewById<TextView>(R.id.about_footer).text =
                "\u00A9 ${java.time.Year.now()} Portal TV  \u00B7  getportaltv.net"
            val dialog = android.app.Dialog(context, R.style.Theme_Portal_About)
            dialog.setContentView(card)
            card.findViewById<TextView>(R.id.about_ok).apply {
                setOnClickListener { dialog.dismiss() }
                requestFocus()
            }
            dialog.show()
            // The theme's dialog window is narrower than the card; let the card set its own width.
            dialog.window?.setLayout(
                (520 * context.resources.displayMetrics.density).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** Opens the TV's own settings app, which is no longer in the app grid. */
    private fun setupSystemSettings(view: View) {
        view.findViewById<TextView>(R.id.opt_system_settings).setOnClickListener {
            val context = requireContext()
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.w("Portal", "Cannot open system settings: ${e.message}")
                Toast.makeText(context, "Can't open system settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Layout (icon + title, icon only, list) and whether apps are grouped; both are per profile. */
    private fun setupAppDisplay(view: View) {
        val context = requireContext()
        val layouts = listOf(
            view.findViewById<TextView>(R.id.opt_layout_tiles) to AppSettings.LAYOUT_TILES,
            view.findViewById<TextView>(R.id.opt_layout_icons) to AppSettings.LAYOUT_ICONS,
            view.findViewById<TextView>(R.id.opt_layout_list) to AppSettings.LAYOUT_LIST
        )
        val groups = listOf(
            view.findViewById<TextView>(R.id.opt_group_on) to true,
            view.findViewById<TextView>(R.id.opt_group_off) to false
        )
        val labels = listOf(
            view.findViewById<TextView>(R.id.opt_labels_show) to true,
            view.findViewById<TextView>(R.id.opt_labels_hide) to false
        )

        fun mark(tv: TextView, selected: Boolean) {
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile
            )
            tv.setTextColor(
                resources.getColor(if (selected) R.color.text_primary else R.color.text_secondary, null)
            )
        }

        fun highlight() {
            val layout = AppSettings.getAppLayout(context)
            val grouped = AppSettings.isGrouped(context)
            layouts.forEach { (tv, value) -> mark(tv, layout == value) }
            groups.forEach { (tv, value) -> mark(tv, grouped == value) }
            val showLabels = AppSettings.showGroupLabels(context)
            labels.forEach { (tv, value) ->
                mark(tv, showLabels == value)
                tv.alpha = if (grouped) 1f else 0.4f  // titles only apply to grouped lists
            }
        }

        layouts.forEach { (tv, value) ->
            tv.setOnClickListener { AppSettings.setAppLayout(context, value); highlight() }
        }
        groups.forEach { (tv, value) ->
            tv.setOnClickListener { AppSettings.setGrouped(context, value); highlight() }
        }
        labels.forEach { (tv, value) ->
            tv.setOnClickListener { AppSettings.setShowGroupLabels(context, value); highlight() }
        }
        highlight()
    }

    /** What one set of shader settings (style, color, cycling) shows and changes: the background's, or the screen saver's. */
    private class ShaderPickers(
        val groupId: Int,
        /** The button that opens the chooser, and the line under it that says what is chosen. */
        val chooseId: Int,
        val summaryId: Int,
        /** The chooser's title. */
        val title: String,
        val visible: () -> Boolean,
        val color: () -> String,
        val setColor: (String) -> Unit,
        val style: () -> String,
        val setStyle: (String) -> Unit,
        val cycle: () -> Boolean,
        val setCycle: (Boolean) -> Unit,
        /** Runs after the person changes something, for whatever else needs to follow it. */
        val changed: () -> Unit
    )

    /** The shader background's pickers, which follow the active profile. */
    private fun setupShaderColors(view: View) {
        val context = requireContext()
        refreshShaderColors = bindShaderPickers(
            view,
            ShaderPickers(
                R.id.shader_color_group, R.id.shader_choose, R.id.shader_summary, "Background shader",
                visible = { BackgroundSettings.getMode(context) == "shader" },
                color = { BackgroundSettings.getShaderColor(context) },
                setColor = { BackgroundSettings.setShaderColor(context, it) },
                style = { BackgroundSettings.getShaderStyle(context) },
                setStyle = { BackgroundSettings.setShaderStyle(context, it) },
                cycle = { BackgroundSettings.getShaderCycle(context) },
                setCycle = { BackgroundSettings.setShaderCycle(context, it) },
                changed = { refreshShaderColors?.invoke(); onBackgroundChanged?.invoke() }
            )
        )
    }

    /**
     * The shader settings as a button that opens the chooser and a line saying what is chosen, for whichever settings
     * [pickers] reads and writes. Returns what refreshes them.
     */
    private fun bindShaderPickers(view: View, pickers: ShaderPickers): () -> Unit {
        val context = requireContext()
        val group = view.findViewById<View>(pickers.groupId)
        val choose = view.findViewById<View>(pickers.chooseId)
        val summary = view.findViewById<TextView>(pickers.summaryId)

        fun refresh() {
            group.visibility = if (pickers.visible()) View.VISIBLE else View.GONE
            val style = ShaderStyles.find(pickers.style()).label
            val color = ShaderPalettes.find(pickers.color()).label
            summary.text = "$style · $color" + if (pickers.cycle()) " · colors cycling" else ""
        }
        choose.setOnClickListener {
            ShaderPickerDialog.show(
                context, pickers.title, ShaderPickerDialog.Choice(pickers.style(), pickers.color(), pickers.cycle())
            ) { picked ->
                pickers.setStyle(picked.style)
                pickers.setColor(picked.color)
                pickers.setCycle(picked.cycle)
                refresh()
                pickers.changed()
            }
        }
        refresh()
        return ::refresh
    }

    /** How loud the home screen's video is; shown only for a video background, since a shader has no sound. */
    private fun setupSound(view: View) {
        val context = requireContext()
        val group = view.findViewById<View>(R.id.sound_group)
        val row = view.findViewById<android.widget.LinearLayout>(R.id.sound_row)
        val px = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val chips = com.portal.tv.settings.VideoSound.levels.map { (label, level) ->
            val chip = TextView(context).apply {
                text = label
                textSize = 18f
                typeface = resources.getFont(R.font.inter)
                gravity = android.view.Gravity.CENTER
                setPadding(px(24), 0, px(24), 0)
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, px(48)
                ).apply { marginEnd = px(8) }
            }
            row.addView(chip)
            level to chip
        }
        fun refresh() {
            group.visibility = if (BackgroundSettings.getMode(context) == "video") View.VISIBLE else View.GONE
            val current = BackgroundSettings.getSound(context)
            chips.forEach { (level, chip) ->
                val on = level == current
                chip.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                chip.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
            }
        }
        chips.forEach { (level, chip) ->
            chip.setOnClickListener {
                BackgroundSettings.setSound(context, level)
                refresh()
                onBackgroundChanged?.invoke()
            }
        }
        val previous = refreshShaderColors
        refreshShaderColors = { previous?.invoke(); refresh() }
        refresh()
    }

    /** How fast the video plays and the shader moves; shown for those two backgrounds. */
    private fun setupSpeed(view: View) {
        val context = requireContext()
        val group = view.findViewById<View>(R.id.speed_group)
        val row = view.findViewById<android.widget.LinearLayout>(R.id.speed_row)
        val px = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val chips = listOf(0.5f to "0.5×", 0.75f to "0.75×", 1f to "Normal", 1.5f to "1.5×", 2f to "2×").map { (value, label) ->
            val chip = TextView(context).apply {
                text = label
                textSize = 18f
                typeface = resources.getFont(R.font.inter)
                gravity = android.view.Gravity.CENTER
                setPadding(px(24), 0, px(24), 0)
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, px(48)
                ).apply { marginEnd = px(8) }
            }
            row.addView(chip)
            value to chip
        }
        fun refresh() {
            val mode = BackgroundSettings.getMode(context)
            group.visibility = if (mode == "video" || mode == "shader") View.VISIBLE else View.GONE
            val speed = BackgroundSettings.getSpeed(context)
            chips.forEach { (value, chip) ->
                val on = value == speed
                chip.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                chip.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
            }
        }
        chips.forEach { (value, chip) ->
            chip.setOnClickListener {
                BackgroundSettings.setSpeed(context, value)
                refresh()
                onBackgroundChanged?.invoke()
            }
        }
        val previous = refreshShaderColors
        refreshShaderColors = { previous?.invoke(); refresh() }
        refresh()
    }

    /**
     * Birthdays for the whole household. Each one is a row; select it to remove it.
     * On the day, the home screen plays the birthday video.
     */
    private fun setupBirthdays(view: View) {
        val context = requireContext()
        val list = view.findViewById<android.widget.LinearLayout>(R.id.birthday_list)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        fun refresh() {
            list.removeAllViews()
            val birthdays = BirthdayStore.all(context)
            if (birthdays.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = "No birthdays yet"
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    setPadding(0, 0, 0, px(12))
                })
            }
            birthdays.forEach { b ->
                list.addView(TextView(context).apply {
                    text = "${b.name}  ·  ${b.dateLabel}"
                    textSize = 18f
                    typeface = resources.getFont(R.font.inter)
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(24), 0, px(24), 0)
                    minHeight = px(48)
                    setBackgroundResource(R.drawable.bg_settings_tile)
                    isFocusable = true
                    contentDescription = "${b.name}, ${b.dateLabel}. Select to remove."
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = px(8) }
                    setOnClickListener {
                        PortalDialog.Builder(context)
                            .setTitle("Remove ${b.name}'s birthday?")
                            .setDestructive()
                            .setMessage(b.dateLabel)
                            .setPositiveButton("Remove") { _, _ ->
                                BirthdayStore.remove(context, b.id)
                                refresh()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
            }
        }

        view.findViewById<TextView>(R.id.opt_add_birthday).setOnClickListener {
            val name = android.widget.EditText(context).apply {
                hint = "Name"
                setSingleLine(true)
                setPadding(px(16), px(16), px(16), px(16))
            }
            val monthNames = java.time.Month.values().map {
                it.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            }
            var month = java.time.LocalDate.now().monthValue
            var day = java.time.LocalDate.now().dayOfMonth

            // Month and day are plain buttons that open a list, which works with a remote's
            // up/down/OK, unlike wheel pickers.
            fun chip() = TextView(context).apply {
                textSize = 18f
                typeface = resources.getFont(R.font.inter)
                setTextColor(resources.getColor(R.color.text_primary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(px(24), 0, px(24), 0)
                minHeight = px(48)
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(12) }
            }
            val monthChip = chip()
            val dayChip = chip()
            fun showDate() {
                monthChip.text = monthNames[month - 1]
                dayChip.text = day.toString()
            }
            monthChip.setOnClickListener {
                PortalDialog.Builder(context)
                    .setTitle("Month")
                    .setSingleChoiceItems(monthNames.toTypedArray(), month - 1) { d, which ->
                        month = which + 1
                        day = minOf(day, BirthdayStore.maxDay(month))
                        showDate()
                        d.dismiss()
                    }
                    .show()
            }
            dayChip.setOnClickListener {
                val days = (1..BirthdayStore.maxDay(month)).map { it.toString() }.toTypedArray()
                PortalDialog.Builder(context)
                    .setTitle("Day")
                    .setSingleChoiceItems(days, day - 1) { d, which ->
                        day = which + 1
                        showDate()
                        d.dismiss()
                    }
                    .show()
            }
            showDate()

            val dateRow = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, px(16), 0, px(8))
                addView(monthChip)
                addView(dayChip)
            }
            val box = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(px(24), px(8), px(24), 0)
                addView(name)
                addView(dateRow)
            }
            PortalDialog.Builder(context)
                .setTitle("Add Birthday")
                .setView(box)
                .setPositiveButton("Add") { _, _ ->
                    val who = name.text.toString().trim()
                    if (who.isEmpty()) {
                        Toast.makeText(context, "Name can't be empty", Toast.LENGTH_SHORT).show()
                    } else {
                        BirthdayStore.add(context, who, month, day)
                        refresh()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refresh()
    }

    /** The Extensions page: what is installed, and what an adult has allowed. A kid's Settings never shows it. */
    private fun setupExtensions(view: View) {
        if (com.portal.tv.repo.ProfileManager.isActiveKids(requireContext())) return
        val page = ExtensionsPage(requireContext(), viewLifecycleOwner.lifecycleScope, { startActivity(it) }, { found, allowed ->
            extensionsSummary = com.portal.tv.extensions.ExtensionLabels.summary(found, allowed)
            refreshCategories?.invoke()
        }) {
            // Back from the catalog lands on this page again.
            returnToCategory = R.id.cat_extensions
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, ExtensionCatalogFragment())
                .addToBackStack("extension_catalog")
                .commit()
        }
        page.bind(view.findViewById(R.id.extensions_list))
        extensionsPage = page
        // After installing from the catalog, "Review" comes back here and opens that extension's page.
        parentFragmentManager.setFragmentResultListener(ExtensionCatalogFragment.REVIEW_KEY, viewLifecycleOwner) { _, result ->
            result.getString(ExtensionCatalogFragment.REVIEW_PACKAGE)?.let { page.review(it) }
        }
        refreshExtensions = page::refresh
        page.refresh()
    }

    /**
     * Reminders for the active profile: a row for each (select it to change or delete it), Add, what to do with a
     * reminder that came due while the TV was off, and the permission to show them over other apps.
     */
    private fun setupReminders(view: View) {
        val context = requireContext()
        val list = view.findViewById<android.widget.LinearLayout>(R.id.reminders_list)
        val add = view.findViewById<TextView>(R.id.opt_add_reminder)
        val label = view.findViewById<TextView>(R.id.reminders_label)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val profile = com.portal.tv.repo.ProfileManager.getActive(context)
        if (profile == null || profile.isKids) {
            // A kid's settings never show this page; with no profile there is nobody to set one for.
            add.visibility = View.GONE
            return
        }
        label.text = "${profile.name}'s reminders"

        fun refresh() {
            list.removeAllViews()
            val reminders = com.portal.tv.reminders.ReminderStore.forProfile(context, profile.id)
            if (reminders.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = "No reminders yet"
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    setPadding(0, 0, 0, px(12))
                })
            }
            val now = System.currentTimeMillis()
            // One lookup for each extension, not one for each of its reminders.
            val sourceNames = HashMap<String, String>()
            reminders.forEach { r ->
                val whenText = com.portal.tv.reminders.ReminderLogic.whenLabel(r.atMillis, now) +
                    (if (r.repeat == com.portal.tv.reminders.Reminder.Repeat.NONE) "" else "  ·  " + r.repeat.label.lowercase()) +
                    (r.source?.let { src -> "  ·  via " + sourceNames.getOrPut(src) { com.portal.tv.extensions.ExtensionRegistry.find(context, src)?.name ?: src } } ?: "")
                list.addView(TextView(context).apply {
                    text = "${r.title}  ·  $whenText"
                    textSize = 18f
                    typeface = resources.getFont(R.font.inter)
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(24), 0, px(24), 0)
                    minHeight = px(48)
                    setBackgroundResource(R.drawable.bg_settings_tile)
                    isFocusable = true
                    contentDescription = "${r.title}, $whenText. Select to change or delete."
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = px(8) }
                    setOnClickListener {
                        ReminderDialogs.edit(context, profile, r) { refresh(); refreshCategories?.invoke() }
                    }
                })
            }
        }
        add.setOnClickListener {
            ReminderDialogs.edit(context, profile, null) { refresh(); refreshCategories?.invoke() }
        }

        val missed = view.findViewById<TextView>(R.id.opt_reminder_missed)
        val missedHint = view.findViewById<TextView>(R.id.reminder_missed_hint)
        fun showMissed() {
            val policy = com.portal.tv.reminders.ReminderStore.missedPolicy(context)
            missed.text = policy.label
            missedHint.text = policy.hint
        }
        missed.setOnClickListener {
            val all = com.portal.tv.reminders.MissedPolicy.values().toList()
            PortalDialog.Builder(context)
                .setTitle("A reminder that came due while the TV was off")
                .setSingleChoiceItems(
                    all.map { it.label }.toTypedArray(),
                    all.indexOf(com.portal.tv.reminders.ReminderStore.missedPolicy(context))
                ) { d, which ->
                    com.portal.tv.reminders.ReminderStore.setMissedPolicy(context, all[which])
                    showMissed()
                    d.dismiss()
                }
                .show()
        }
        showMissed()

        val overlay = view.findViewById<TextView>(R.id.opt_reminder_overlay)
        val overlayHint = view.findViewById<TextView>(R.id.reminder_overlay_hint)
        val adb = "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
        fun showOverlay() {
            val granted = android.provider.Settings.canDrawOverlays(context)
            overlay.text = if (granted) "Reminders over apps: On" else "Reminders over apps: Off. Select to allow"
            overlayHint.text = if (granted) "A card shows on top of whatever app is open."
            else "Without this a reminder is an ordinary notification, which is easy to miss while watching something."
        }
        overlay.setOnClickListener {
            if (android.provider.Settings.canDrawOverlays(context)) return@setOnClickListener
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            if (intent.resolveActivity(context.packageManager) != null) startActivity(intent)
            else PortalDialog.Builder(context)
                .setTitle("Allow display over other apps")
                .setMessage("This TV has no screen for this permission. From a computer, run:\n\n$adb")
                .setPositiveButton("OK", null)
                .show()
        }
        refreshReminderOverlay = ::showOverlay
        showOverlay()
        refresh()
    }

    /**
     * Preview tiles for the bundled videos (the default and the space clips). Shown while the
     * Video background is selected; choosing one saves it and the home screen plays it.
     */
    /** A choice in a row of video tiles: [key] null is the Automatic one, which gets a card instead of a picture. */
    private class PickTile(val key: String?, val label: String, val thumbAsset: String?)

    /** Adds a tile for each of [tiles] to [row]: a preview picture over its name. */
    private fun addPickerTiles(
        row: android.widget.LinearLayout,
        tiles: List<PickTile>
    ): List<Triple<String?, android.widget.FrameLayout, android.widget.LinearLayout>> {
        val context = requireContext()
        fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
        return tiles.map { t ->
            val ring = android.widget.FrameLayout(context).apply { setPadding(px(3), px(3), px(3), px(3)) }
            val tile = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(px(8), px(8), px(8), px(6))
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(8) }
                addView(ring)
                addView(TextView(context).apply {
                    text = t.label
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, px(4), 0, 0)
                })
            }
            if (t.thumbAsset == null) {
                ring.addView(TextView(context).apply {
                    text = "Switches\nbetween them"
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(0xFF1A1A2E.toInt())
                }, android.widget.FrameLayout.LayoutParams(px(176), px(99)))
            } else {
                val thumb = android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF1A1A2E.toInt())
                }
                ring.addView(thumb, android.widget.FrameLayout.LayoutParams(px(176), px(99)))
                viewLifecycleOwner.lifecycleScope.launch {
                    VideoThumbnails.load(context, t.thumbAsset)?.let { thumb.setImageBitmap(it) }
                }
            }
            row.addView(tile)
            Triple(t.key, ring, tile)
        }
    }

    /** "Next: Thanksgiving, Nov 26", or why there is none, for under the Holidays button. */
    private fun holidaysSummaryText(context: android.content.Context): String {
        if (!BackgroundSettings.getHolidayVideos(context)) return "Off"
        val today = java.time.LocalDate.now()
        val soon = HolidayStore.load(context).minByOrNull { Holidays.nextDate(it.rule, today) } ?: return "No holidays yet"
        return "Next: ${soon.name}, ${Holidays.shortDate(Holidays.nextDate(soon.rule, today))}"
    }

    private fun setupVideoChoices(view: View) {
        val context = requireContext()
        val group = view.findViewById<View>(R.id.video_choice_group)
        val row = view.findViewById<android.widget.LinearLayout>(R.id.video_choice_row)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // Downloading videos is for the people running the TV, not the kids.
        val getVideos = view.findViewById<View>(R.id.opt_get_videos)
        val isKid = com.portal.tv.repo.ProfileManager.getActive(context)?.isKids == true
        getVideos.visibility = if (isKid) View.GONE else View.VISIBLE
        getVideos.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, VideoBrowserFragment())
                .addToBackStack("videos")
                .commit()
        }

        // Three tiles: Random (a new video each day), the video in use, and Select, which opens a window to choose
        // from all of them (there may be many once videos have been downloaded).
        class VideoTile(val thumb: android.widget.ImageView, val ring: android.widget.FrameLayout,
                        val tile: android.widget.LinearLayout, val name: TextView)
        fun buildTile(label: String): VideoTile {
            val thumb = android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1A1A2E.toInt())
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, px(10).toFloat())
                    }
                }
            }
            // The ring around the thumbnail marks the current choice.
            val ring = android.widget.FrameLayout(context).apply {
                setPadding(px(3), px(3), px(3), px(3))
                addView(thumb, android.widget.FrameLayout.LayoutParams(px(150), px(84)))
            }
            val name = TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, px(4), 0, 0)
            }
            val tile = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(px(8), px(8), px(8), px(6))
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                contentDescription = label
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(8) }
                addView(ring)
                addView(name, android.widget.LinearLayout.LayoutParams(px(156), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            row.addView(tile)
            return VideoTile(thumb, ring, tile, name)
        }
        fun card(tile: VideoTile, words: String) {
            // No preview frame: a plain card that says what it does.
            tile.ring.addView(TextView(context).apply {
                text = words
                textSize = 16f
                setTextColor(resources.getColor(R.color.text_primary, null))
                gravity = android.view.Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(px(150), px(84)))
        }

        val random = buildTile("Random").also { card(it, "A new video\neach day") }
        val inUse = buildTile("")
        val sound = soundBadge(context)
        inUse.ring.addView(sound, android.widget.FrameLayout.LayoutParams(px(22), px(22), android.view.Gravity.TOP or android.view.Gravity.START)
            .apply { setMargins(px(9), px(9), 0, 0) })
        val select = buildTile("Select").also { card(it, "Browse all\nvideos") }
        var inUseAsset = ""

        /** Shows the video in use (today's, when the choice is Random) on the middle tile. */
        fun showInUse() {
            val asset = BackgroundRepository.selectedVideo(context)
            inUseAsset = asset
            val label = BackgroundRepository.availableVideos(context).firstOrNull { it.asset == asset }?.label
                ?: BackgroundRepository.labelFor(asset)
            val today = BackgroundRepository.isRandom(context)
            inUse.name.text = if (today) "Today: $label" else label
            inUse.tile.contentDescription = if (today) "Today's video, $label" else label
            inUse.thumb.setImageDrawable(null)
            sound.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                VideoThumbnails.load(context, asset)?.let { if (inUseAsset == asset) inUse.thumb.setImageBitmap(it) }
                if (VideoThumbnails.hasAudio(context, asset) && inUseAsset == asset) sound.visibility = View.VISIBLE
            }
        }

        fun refresh() {
            // The saved choice, which may be Random rather than the video it picked today.
            val randomOn = BackgroundRepository.isRandom(context)
            group.visibility = View.VISIBLE
            for ((tile, on) in listOf(random to randomOn, inUse to !randomOn, select to false)) {
                tile.ring.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(13).toFloat()
                    setStroke(px(if (on) 3 else 0), android.graphics.Color.WHITE)
                }
            }
        }

        random.tile.setOnClickListener {
            BackgroundSettings.setVideo(context, BackgroundRepository.RANDOM)
            refresh()
            onBackgroundChanged?.invoke()
        }
        inUse.tile.setOnClickListener {
            BackgroundSettings.setVideo(context, inUseAsset)
            showInUse()
            refresh()
            onBackgroundChanged?.invoke()
        }
        select.tile.setOnClickListener {
            VideoPickerDialog.show(
                context, viewLifecycleOwner, BackgroundRepository.availableVideos(context), inUseAsset
            ) { picked ->
                BackgroundSettings.setVideo(context, picked.asset)
                showInUse()
                refresh()
                onBackgroundChanged?.invoke()
            }
        }
        showInUse()
        // Holidays have a page of their own; this is the way in, with the next one for a glance.
        val holidaysButton = view.findViewById<View>(R.id.opt_holidays)
        val holidaysSummary = view.findViewById<TextView>(R.id.holidays_summary)
        val kidHere = com.portal.tv.repo.ProfileManager.isActiveKids(context)
        holidaysButton.visibility = if (kidHere) View.GONE else View.VISIBLE
        holidaysSummary.visibility = if (kidHere) View.GONE else View.VISIBLE
        holidaysSummary.text = holidaysSummaryText(context)
        holidaysButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, HolidaysFragment())
                .addToBackStack("holidays")
                .commit()
        }

        // Which birthday video, when there is more than one.
        val birthdayGroup = view.findViewById<View>(R.id.birthday_video_group)
        val birthdayRow = view.findViewById<android.widget.LinearLayout>(R.id.birthday_video_row)
        val birthdayVideos = BackgroundRepository.birthdayVideos(context)
        val birthdayTiles = addPickerTiles(
            birthdayRow,
            listOf(PickTile(null, "Automatic", null)) + birthdayVideos.map { PickTile(it.asset, it.label, it.asset) }
        )
        fun styleBirthdayVideos() {
            birthdayGroup.visibility = if (birthdayVideos.size > 1 && !com.portal.tv.repo.ProfileManager.isActiveKids(context))
                View.VISIBLE else View.GONE
            val chosen = BackgroundSettings.getBirthdayVideo(context).takeIf { c -> birthdayVideos.any { it.asset == c } }
            birthdayTiles.forEach { (file, ring, _) ->
                val on = file == chosen
                ring.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(13).toFloat()
                    setStroke(px(if (on) 3 else 0), android.graphics.Color.WHITE)
                }
            }
        }
        birthdayTiles.forEach { (file, _, tile) ->
            tile.setOnClickListener {
                BackgroundSettings.setBirthdayVideo(context, file ?: "")
                styleBirthdayVideos()
                onBackgroundChanged?.invoke()
            }
        }
        styleBirthdayVideos()

        val weatherOn = view.findViewById<TextView>(R.id.opt_weatherbg_on)
        val weatherOff = view.findViewById<TextView>(R.id.opt_weatherbg_off)
        fun styleWeather() {
            val on = BackgroundSettings.getWeatherBackground(context)
            for ((tile, selected) in listOf(weatherOn to on, weatherOff to !on)) {
                tile.setBackgroundResource(if (selected) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                tile.setTextColor(resources.getColor(if (selected) R.color.text_primary else R.color.text_secondary, null))
            }
        }
        weatherOn.setOnClickListener { BackgroundSettings.setWeatherBackground(context, true); styleWeather() }
        weatherOff.setOnClickListener { BackgroundSettings.setWeatherBackground(context, false); styleWeather() }
        styleWeather()

        // Piggyback on the shader picker's refresh so both follow the background mode.
        val previous = refreshShaderColors
        refreshShaderColors = { previous?.invoke(); refresh() }
        refresh()
    }
}
