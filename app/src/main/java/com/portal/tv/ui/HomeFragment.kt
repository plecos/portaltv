package com.portal.tv.ui

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.R
import com.portal.tv.background.BackgroundPlayer
import com.portal.tv.background.BackgroundRepository
import com.portal.tv.background.HolidayStore
import com.portal.tv.data.AppInfo
import com.portal.tv.repo.AppOrder
import com.portal.tv.kids.KidsMonitorService
import com.portal.tv.repo.AppRepository
import com.portal.tv.repo.KidsSchedule
import com.portal.tv.repo.ScheduleLogic
import com.portal.tv.repo.UsageTracker
import com.portal.tv.search.SearchDialogFragment
import com.portal.tv.search.SearchSettings
import com.portal.tv.repo.WeatherRepository
import com.portal.tv.settings.AppSettings
import com.portal.tv.settings.BackgroundSettings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var frequentRow: RecyclerView
    private lateinit var appRepository: AppRepository
    private var backgroundPlayer: BackgroundPlayer? = null
    private var timeTextView: android.widget.TextView? = null
    private var weatherTextView: android.widget.TextView? = null
    private var weatherProgress: View? = null
    private var weatherRetry: View? = null
    private var weatherRow: View? = null
    private var celestialView: android.widget.ImageView? = null
    private var overlayView: android.widget.ImageView? = null
    private var windArrowView: android.widget.ImageView? = null
    private var windTextView: android.widget.TextView? = null

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateClock()
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val weatherHandler = Handler(Looper.getMainLooper())
    private val weatherRunnable = object : Runnable {
        override fun run() {
            fetchWeather()
            refreshDailyBackground()
            weatherHandler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appRepository = AppRepository(requireContext())

        recyclerView = view.findViewById(R.id.app_grid)
        frequentRow = view.findViewById(R.id.frequent_row)

        timeTextView = view.findViewById(R.id.tv_time)
        weatherTextView = view.findViewById(R.id.tv_weather)
        weatherProgress = view.findViewById(R.id.weather_progress)
        weatherRetry = view.findViewById(R.id.btn_weather_retry)
        weatherRow = view.findViewById(R.id.weather_row)
        timeLeftView = view.findViewById(R.id.tv_time_left)
        showingWeather = false
        weatherNeedsRefresh = false
        weatherRetry?.setOnClickListener { fetchWeather() }
        celestialView = view.findViewById(R.id.iv_celestial)
        overlayView = view.findViewById(R.id.iv_weather_overlay)
        windArrowView = view.findViewById(R.id.iv_wind_arrow)
        windTextView = view.findViewById(R.id.tv_wind)
        alertBadge = view.findViewById(R.id.tv_alert)

        // On a fresh start the screen is built out of sight while the intro's ring waits. This screen
        // says it is not ready until its background is showing and its apps are listed.
        IntroAnimation.current?.let { intro ->
            intro.hold(BACKGROUND_READY)
            intro.hold(APPS_READY)
            intro.release(IntroAnimation.HOME)

            val d = resources.displayMetrics.density
            intro.addEntrance(view.findViewById(R.id.panel_weather), 520, 450, 0f, -30 * d)
            intro.addEntrance(view.findViewById(R.id.panel_actions), 580, 450, 0f, -30 * d)
            (recyclerView.parent?.parent as? View)?.let { intro.addEntrance(it, 640, 450, -40 * d, 0f) }
        }

        // Profile button
        val activeProfile = com.portal.tv.repo.ProfileManager.getActive(requireContext())
        view.findViewById<android.widget.TextView>(R.id.tv_profile_name).text =
            activeProfile?.name ?: "Profile"
        activeProfile?.let {
            view.findViewById<android.widget.ImageView>(R.id.iv_profile_avatar)
                .setImageDrawable(ProfileAvatar.drawable(requireContext(), it))
        }

        view.findViewById<View>(R.id.btn_profile).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, ProfilePickerFragment())
                .addToBackStack("profiles")
                .commit()
        }

        // The time and weather panel opens the weather page.
        view.findViewById<View>(R.id.panel_weather).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, WeatherFragment())
                .addToBackStack("weather")
                .commit()
        }

        // Kids get the settings button too, with only the background, app layout and weather units in it.
        view.findViewById<View>(R.id.btn_search).setOnClickListener {
            if (childFragmentManager.findFragmentByTag("search") == null) {
                SearchDialogFragment().show(childFragmentManager, "search")
            }
        }
        fun openSettings(settings: SettingsFragment) {
            settings.setBackgroundChangedListener {
                applyBackground(view)
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, settings)
                .addToBackStack("settings")
                .commit()
        }
        view.findViewById<View>(R.id.btn_settings).setOnClickListener { openSettings(SettingsFragment()) }
        // A newer version has been found: the Update button goes straight to the update in Settings.
        // (If only an extension has an update, it opens Settings at Extensions instead.)
        view.findViewById<View>(R.id.btn_update).setOnClickListener {
            val portalUpdate = com.portal.tv.update.Updates.updateAvailable(requireContext(), com.portal.tv.BuildConfig.VERSION_NAME)
            openSettings(if (portalUpdate) SettingsFragment.forUpdates() else SettingsFragment.forExtensions())
        }
        refreshUpdateButton()

        setupBackground(view)
    }

    /** Shows the Update button when the last check found a newer version. Kids can't open that part of Settings. */
    private fun refreshUpdateButton() {
        val context = context ?: return
        val button = view?.findViewById<View>(R.id.btn_update) ?: return
        val kid = com.portal.tv.repo.ProfileManager.getActive(context)?.isKids == true
        val portal = !kid && com.portal.tv.update.Updates.updateAvailable(context, com.portal.tv.BuildConfig.VERSION_NAME)
        button.visibility = if (portal) View.VISIBLE else View.GONE
        // An extension with a newer version in the catalog (from the copy kept on the TV) also earns the button.
        if (!kid && !portal) viewLifecycleOwner.lifecycleScope.launch {
            val any = withContext(Dispatchers.IO) { com.portal.tv.extensions.ExtensionUpdates.pending(context).isNotEmpty() }
            view?.findViewById<View>(R.id.btn_update)?.visibility = if (any) View.VISIBLE else View.GONE
        }
    }

    /**
     * Draws the buttons and text allowed extensions put in the top bar and under the clock. Kid profiles get none. The
     * scan is off the main thread; Portal's own buttons are never touched.
     */
    private fun refreshExtensionItems() {
        val context = context ?: return
        val bar = view?.findViewById<android.widget.LinearLayout>(R.id.ext_bar_items) ?: return
        val clock = view?.findViewById<android.widget.LinearLayout>(R.id.ext_clock_items) ?: return
        val kid = com.portal.tv.repo.ProfileManager.getActive(context)?.isKids == true
        viewLifecycleOwner.lifecycleScope.launch {
            val extensions = if (kid) emptyList() else withContext(Dispatchers.IO) { com.portal.tv.extensions.ExtensionRegistry.scan(context) }
            val fill = { area: com.portal.tv.extensions.ItemArea, into: android.widget.LinearLayout ->
                ExtensionItemViews.fill(
                    into,
                    com.portal.tv.extensions.ExtensionItems.pick(extensions, area),
                    onOpen = { openExtensionItem(it, area) },
                    onInfo = { showItemInfo(it) }
                )
            }
            fill(com.portal.tv.extensions.ItemArea.BAR, bar)
            fill(com.portal.tv.extensions.ItemArea.CLOCK, clock)
        }
    }

    private fun openExtensionItem(shown: com.portal.tv.extensions.ShownItem, area: com.portal.tv.extensions.ItemArea) {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // Checked again now, in case the extension changed since the screen was drawn.
            val intent = withContext(Dispatchers.IO) {
                com.portal.tv.extensions.ExtensionItems.resolve(context, shown.packageName, area, shown.item.id)
            }
            if (intent == null) {
                android.widget.Toast.makeText(context, "${shown.item.text} isn't allowed to open any more", android.widget.Toast.LENGTH_LONG).show()
                refreshExtensionItems()
                return@launch
            }
            runCatching { startActivity(intent) }
                .onFailure { android.widget.Toast.makeText(context, "Couldn't open ${shown.item.text}", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    /** Long press on an extension's item: whose it is, since it looks like part of Portal. */
    private fun showItemInfo(shown: com.portal.tv.extensions.ShownItem) {
        val context = context ?: return
        PortalDialog.Builder(context)
            .setTitle(shown.item.text)
            .setMessage("Added by the extension ${shown.extensionName} (${shown.packageName}). It isn't part of Portal. An adult can turn it off in Settings, Extensions.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupBackground(view: View) {
        applyBackground(view)
    }

    private var shaderView: ShaderBackgroundView? = null

    /** The background an extension draws, while that is what is chosen and it is working. */
    private var extensionBackground: ExtensionBackgroundView? = null

    /** What the background is showing now: "off", "shader", "video" or "birthday". */
    private var activeMode = "video"

    /** Names celebrated by the current background, to notice when the day rolls over. */
    private var appliedBirthdays: List<String> = emptyList()

    /** The holiday video the current background was built for, to notice when the date rolls over. */
    private var appliedSeasonal: String? = null

    /** The chosen video the background was built for; for Random it changes when the date does. */
    private var appliedChosen: String? = null

    /** Holiday video for today; only the Video background uses it. */
    private fun seasonalNow(): String? =
        if (BackgroundSettings.getMode(requireContext()) == "video" &&
            BackgroundSettings.getHolidayVideos(requireContext()))
            HolidayStore.forDate(requireContext()) else null

    private fun todaysBirthdays() =
        com.portal.tv.settings.BirthdayStore.on(requireContext()).map { it.name }

    /** Left on the home screen across midnight, the date changes what the daily videos should be. */
    private fun refreshDailyBackground() {
        val view = view ?: return
        val context = context ?: return
        if (todaysBirthdays() != appliedBirthdays || seasonalNow() != appliedSeasonal ||
            (activeMode == "video" && BackgroundRepository.selectedVideo(context) != appliedChosen)) {
            applyBackground(view)
        }
    }

    private fun applyBackground(view: View) {
        val root = view as FrameLayout
        val textureView = view.findViewById<TextureView>(R.id.bg_video)
        val dimOverlay = view.findViewById<View>(R.id.bg_dim)

        // On a birthday the birthday video replaces whatever background is chosen.
        val birthdays = todaysBirthdays()
        appliedBirthdays = birthdays
        val greeting = view.findViewById<android.widget.TextView>(R.id.tv_birthday)
        if (birthdays.isEmpty()) {
            greeting.visibility = View.GONE
        } else {
            greeting.text = "Happy Birthday, ${joinNames(birthdays)}!"
            greeting.visibility = View.VISIBLE
        }

        val mode = if (birthdays.isNotEmpty()) "birthday" else BackgroundSettings.getMode(requireContext())
        activeMode = mode
        Log.d("Portal", "applyBackground: mode=$mode")

        // Birthday video beats a holiday video, which beats the chosen video.
        val seasonal = if (mode == "video") seasonalNow() else null
        appliedSeasonal = seasonal
        val chosen = BackgroundRepository.selectedVideo(requireContext())
        appliedChosen = chosen
        val asset = if (mode == "birthday") BackgroundRepository.birthdayVideo(requireContext()) else seasonal ?: chosen

        // Very dark videos swallow the normal dark panels, so use lighter ones there. The video's
        // brightness is measured once, so the panels start normal and switch if it turns out dark.
        fun setPanels(dark: Boolean) {
            view.findViewById<View>(R.id.panel_weather).setBackgroundResource(
                if (dark) R.drawable.bg_header_panel_light_focusable else R.drawable.bg_header_panel_focusable
            )
            view.findViewById<View>(R.id.panel_actions).setBackgroundResource(
                if (dark) R.drawable.bg_header_panel_light else R.drawable.bg_header_panel
            )
            view.findViewById<View>(R.id.ext_clock_items).setBackgroundResource(
                if (dark) R.drawable.bg_header_panel_light else R.drawable.bg_header_panel
            )
        }
        setPanels(false)
        if (mode == "video") {
            val context = requireContext()
            lifecycleScope.launch {
                val dark = BackgroundRepository.isDark(context, asset)
                if (dark && activeMode == "video") setPanels(true)
            }
        }

        // Stop any existing player
        backgroundPlayer?.stop()
        backgroundPlayer = null

        // Remove shader view if it exists (pause first to stop the GL thread)
        shaderView?.let {
            it.onPause()
            root.removeView(it)
        }
        shaderView = null
        extensionBackground?.let {
            it.stop()
            root.removeView(it)
        }
        extensionBackground = null

        // The animated gradient: the "shader" background, and what an extension's background gives way to if it can't be used.
        fun showShader() {
            textureView.visibility = View.GONE
            val shader = ShaderBackgroundView(
                requireContext(),
                ShaderPalettes.find(BackgroundSettings.getShaderColor(requireContext())),
                ShaderStyles.find(BackgroundSettings.getShaderStyle(requireContext())),
                BackgroundSettings.getSpeed(requireContext()),
                BackgroundSettings.getShaderCycle(requireContext())
            )
            shader.onFirstFrame = { IntroAnimation.release(BACKGROUND_READY) }
            root.addView(shader, 1, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            shaderView = shader
            shader.onResume()
            Log.d("Portal", "shader view added and started")
            dimOverlay.visibility = View.GONE  // shader is already dark, no extra dim
        }

        when (mode) {
            "off" -> {
                textureView.visibility = View.GONE
                dimOverlay.visibility = View.GONE
                IntroAnimation.release(BACKGROUND_READY)
            }
            "shader" -> showShader()
            com.portal.tv.extensions.BackgroundChoice.MODE -> {
                // Drawn by an extension in its own process. It sits under the usual dim layer, so the screen stays readable.
                textureView.visibility = View.GONE
                dimOverlay.visibility = View.VISIBLE
                val context = requireContext()
                // The same dimming a video background gets (its brightness setting, 25% dark by default), not the layout's 50%,
                // which left an extension's picture too dark. The extension's own colours are left alone.
                dimOverlay.alpha = 1f - BackgroundSettings.getBrightness(context)
                val chosen = BackgroundSettings.getExtension(context)
                val profile = com.portal.tv.repo.ProfileManager.getActive(context)
                // A kid gets it only if a parent turned it on for them (the background view checks again before it draws).
                val kid = profile?.isKids == true && !(profile != null && BackgroundSettings.kidCanPickExtension(context, profile.id))
                if (chosen == null || kid || this.view == null) {
                    // The home screen's view is gone (Settings is open over it): the extension is started when it is back.
                    showShader()
                } else {
                    val bg = ExtensionBackgroundView(context)
                    // Until it shows its first picture the screen has Portal's own gradient, so there is never a blank.
                    showShader()
                    root.addView(bg, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    bg.onReady = {
                        // The extension's picture is on, so the gradient under it can stop.
                        shaderView?.let { it.onPause(); root.removeView(it) }
                        shaderView = null
                        dimOverlay.visibility = View.VISIBLE
                        dimOverlay.alpha = 1f - BackgroundSettings.getBrightness(context)
                        IntroAnimation.release(BACKGROUND_READY)
                    }
                    bg.onFailed = { reason ->
                        Log.w("Portal", "Extension background $chosen not used: $reason")
                        extensionBackground?.let { root.removeView(it) }
                        extensionBackground = null
                        if (shaderView == null) showShader()
                    }
                    extensionBackground = bg
                    bg.start(chosen, lifecycleScope)
                }
            }
            "video", "birthday" -> {
                lifecycleScope.launch {
                    // A holiday video that can't be read falls back to the chosen video.
                    var playAsset = asset
                    var ready = BackgroundRepository(requireContext(), playAsset).extractBundledVideo()
                    if (!ready && playAsset != chosen && mode == "video") {
                        Log.w("Portal", "Holiday video $playAsset unavailable, using $chosen")
                        playAsset = chosen
                        ready = BackgroundRepository(requireContext(), playAsset).extractBundledVideo()
                    }

                    if (!ready) {
                        Log.w("Portal", "No background video — falling back to solid background")
                        textureView.visibility = View.GONE
                        dimOverlay.visibility = View.GONE
                        IntroAnimation.release(BACKGROUND_READY)
                    } else {
                        textureView.visibility = View.VISIBLE
                        dimOverlay.visibility = View.VISIBLE
                        val player = BackgroundPlayer(requireContext(), textureView, dimOverlay, playAsset)
                        // The intro waits for the video's first picture before it opens.
                        player.onFirstFrame = { IntroAnimation.release(BACKGROUND_READY) }
                        backgroundPlayer = player
                        player.start(lifecycleScope)
                        Log.d("Portal", "BackgroundPlayer started")
                    }
                }
            }
        }
    }

    /** "A", "A & B", "A, B & C" */
    private fun joinNames(names: List<String>): String = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} & ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
    }

    /** The All Apps list as drawn, so the app menu can move an app within it. */
    private var shownGrid: List<AppInfo> = emptyList()

    private fun loadApps(focusPackage: String? = null) {
        lifecycleScope.launch {
            // Kid profiles only see the apps a parent has allowed.
            val active = com.portal.tv.repo.ProfileManager.getActive(requireContext())
            val installed = appRepository.getLaunchableApps().let { apps ->
                if (active?.isKids == true) apps.filter { it.packageName in active.allowedApps }
                else apps
            }
            // The user may have left the screen while the apps loaded.
            val root = view ?: return@launch
            val context = requireContext()
            val hidden = AppSettings.hiddenApps(context)
            val allApps = installed.filter { it.packageName !in hidden }
            // Kid profiles can't change what's on the home screen.
            val canManage = active?.isKids != true

            val layout = AppSettings.getAppLayout(context)
            val grouped = AppSettings.isGrouped(context)
            val showLabels = grouped && AppSettings.showGroupLabels(context)
            val frequentHeader = root.findViewById<View>(R.id.tv_frequent_header)
            val allHeader = root.findViewById<View>(R.id.tv_all_apps_header)

            // Frequently used (top 8 by launch count), only when grouping is on.
            val topPackages = if (grouped) UsageTracker.topApps(context, 8) else emptyList()
            val frequentApps = allApps.filter { it.packageName in topPackages }
                .sortedBy { topPackages.indexOf(it.packageName) }
            val showFrequent = frequentApps.isNotEmpty()
            frequentHeader.visibility = if (showFrequent && showLabels) View.VISIBLE else View.GONE
            frequentRow.visibility = if (showFrequent) View.VISIBLE else View.GONE
            if (showFrequent) {
                // Tiles and icons run along one row; a list stacks vertically.
                frequentRow.layoutManager = LinearLayoutManager(
                    context,
                    if (layout == AppSettings.LAYOUT_LIST) LinearLayoutManager.VERTICAL
                    else LinearLayoutManager.HORIZONTAL,
                    false
                )
                frequentRow.adapter = AppAdapter(frequentApps, layout, { app, icon -> launchApp(app, icon) },
                    if (canManage) { app -> showAppMenu(app, inFrequent = true) } else null)
            }

            // All apps (alphabetical; without grouping this is the one plain list).
            allHeader.visibility = if (showLabels) View.VISIBLE else View.GONE
            val gridApps = AppOrder.sort(
                allApps.filter { it.packageName !in topPackages }, AppSettings.appOrder(context)
            ) { it.packageName }
            shownGrid = gridApps
            val density = resources.displayMetrics.density
            val itemPx = (AppAdapter.itemWidthDp(layout) * density).toInt()
            val availablePx = resources.displayMetrics.widthPixels - (2 * 48 * density).toInt()
            val columns = if (layout == AppSettings.LAYOUT_LIST) 1 else maxOf(1, availablePx / itemPx)
            recyclerView.layoutManager = GridLayoutManager(context, columns)
            recyclerView.layoutParams = recyclerView.layoutParams.apply { width = columns * itemPx }
            recyclerView.adapter = AppAdapter(gridApps, layout, { app, icon -> launchApp(app, icon) },
                if (canManage) { app -> showAppMenu(app, inFrequent = false) } else null)

            // The first time the apps are listed, the intro can open.
            IntroAnimation.release(APPS_READY)

            // After moving an app, keep the selection on it.
            if (focusPackage != null) {
                val index = gridApps.indexOfFirst { it.packageName == focusPackage }
                if (index >= 0) {
                    recyclerView.scrollToPosition(index)
                    recyclerView.post {
                        recyclerView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    /** The menu opened by holding select on an app: move it, hide it, or manage the app itself. */
    private fun showAppMenu(app: AppInfo, inFrequent: Boolean) {
        val context = requireContext()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Open" to { launchApp(app) }

        if (!inFrequent) {
            val shown = shownGrid.map { it.packageName }
            val index = shown.indexOf(app.packageName)
            fun move(to: Int) {
                AppSettings.setAppOrder(
                    context, AppOrder.moveTo(shown, AppSettings.appOrder(context), app.packageName, to)
                )
                loadApps(focusPackage = app.packageName)
            }
            if (index > 0) {
                actions += "Move to start" to { move(0) }
                actions += "Move earlier" to { move(index - 1) }
            }
            if (index in 0 until shown.size - 1) actions += "Move later" to { move(index + 1) }
        }

        actions += "Hide from home screen" to {
            AppSettings.hideApp(context, app.packageName)
            loadApps()
        }
        // An extension's tile isn't an installed app of its own, so it has no app info to show.
        if (!com.portal.tv.extensions.ExtensionTileKey.isTile(app.packageName)) actions += "App info" to {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", app.packageName, null)
                )
            )
        }
        // System apps can't be uninstalled, so don't offer it.
        if (!app.isSystem) {
            actions += "Uninstall" to {
                startActivity(
                    Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", app.packageName, null))
                )
            }
        }

        PortalDialog.Builder(context)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens an app chosen in the search panel, with the same checks as launching from the list. */
    fun launchFromSearch(app: AppInfo) = launchApp(app)

    /** The search button is only there for profiles whose search level isn't Off. */
    private fun applySearchLevel() {
        val context = context ?: return
        val profile = com.portal.tv.repo.ProfileManager.getActive(context)
        val allowed = profile != null && SearchSettings.level(context, profile).findsApps
        view?.findViewById<View>(R.id.btn_search)?.visibility = if (allowed) View.VISIBLE else View.GONE
    }

    private var launchAnimation: LaunchAnimation? = null

    /** [from] is the icon's view in the grid, or null when the app was opened from search or a menu. */
    private fun launchApp(app: AppInfo, from: android.widget.ImageView? = null) {
        if (launchAnimation?.active == true) return
        // A tile from an extension opens one of the extension's own screens, if it is still allowed to.
        val isTile = com.portal.tv.extensions.ExtensionTileKey.isTile(app.packageName)
        val tile = if (isTile) com.portal.tv.extensions.ExtensionTiles.resolve(requireContext(), app.packageName) else null
        if (isTile && tile == null) {
            android.widget.Toast.makeText(requireContext(), "${app.label} isn't allowed to open any more", android.widget.Toast.LENGTH_LONG).show()
            loadApps()
            return
        }
        // A kid's bedtime or daily limit can stop the launch.
        val kid = com.portal.tv.repo.ProfileManager.getActive(requireContext())?.takeIf { it.isKids }
        if (kid != null) {
            val status = KidsSchedule.check(requireContext(), kid.id)
            if (status !is ScheduleLogic.Status.Allowed) {
                showScheduleLock(kid, status)
                return
            }
            KidsSchedule.startSession(requireContext(), kid.id)
            // Keep watching the clock, and show the time left over the app, while it's open.
            KidsSchedule.setSessionPackage(requireContext(), tile?.packageName ?: app.packageName)
            if (!KidsSchedule.config(requireContext(), kid.id).isEmpty) KidsMonitorService.start(requireContext())
        }
        UsageTracker.recordLaunch(requireContext(), app.packageName)
        // TV apps usually only have a leanback entry point.
        val pm = requireContext().packageManager
        val intent = tile?.intent ?: pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            val root = view as? android.view.ViewGroup
            // The app fades in as the animation ends, rather than cutting in.
            val options = android.app.ActivityOptions.makeCustomAnimation(
                requireContext(), android.R.anim.fade_in, android.R.anim.fade_out
            ).toBundle()
            if (root == null) {
                startActivity(intent, options)
            } else {
                val animation = launchAnimation ?: LaunchAnimation(root).also { launchAnimation = it }
                animation.start(app.icon, app.label, from) { startActivity(intent, options) }
            }
        }
    }

    // ---------- Kid schedule ----------

    private var timeLeftView: android.widget.TextView? = null

    private val scheduleHandler = Handler(Looper.getMainLooper())
    private val scheduleRunnable = object : Runnable {
        override fun run() {
            checkSchedule()
            scheduleHandler.postDelayed(this, 30_000L)
        }
    }

    /** Updates the "time left" line and locks the screen when a kid's bedtime or limit has arrived. */
    /**
     * A kid is back on Portal's screen, so the app they were in is in the background. If their time
     * ran out while they were in it, stop it so it doesn't keep playing. An app that insists on
     * running, such as one playing audio, may refuse.
     */
    private fun stopKidsApp() {
        val context = context ?: return
        val kid = com.portal.tv.repo.ProfileManager.getActive(context)?.takeIf { it.isKids } ?: return
        val pkg = KidsSchedule.sessionPackage(context) ?: return
        if (KidsSchedule.check(context, kid.id) is ScheduleLogic.Status.Allowed) return
        runCatching {
            (context.getSystemService(android.app.ActivityManager::class.java)).killBackgroundProcesses(pkg)
        }
    }

    private fun checkSchedule() {
        val context = context ?: return
        val label = timeLeftView ?: return
        val kid = com.portal.tv.repo.ProfileManager.getActive(context)?.takeIf { it.isKids }
        if (kid == null) {
            label.visibility = View.GONE
            hideScheduleLock()
            return
        }
        when (val status = KidsSchedule.check(context, kid.id)) {
            is ScheduleLogic.Status.Allowed -> {
                hideScheduleLock()
                val left = status.remainingMs
                label.text = if (left == null) "" else KidsSchedule.remainingLabel(left)
                label.visibility = if (left == null) View.GONE else View.VISIBLE
            }
            is ScheduleLogic.Status.Bedtime -> {
                label.text = "Bedtime until ${KidsSchedule.timeLabel(status.wakeMinute)}"
                label.visibility = View.VISIBLE
                showScheduleLock(kid, status)
            }
            ScheduleLogic.Status.LimitReached -> {
                label.text = "Time's up for today"
                label.visibility = View.VISIBLE
                showScheduleLock(kid, status)
            }
        }
    }

    private fun showScheduleLock(kid: com.portal.tv.data.Profile, status: ScheduleLogic.Status) {
        val root = view ?: return
        val context = context ?: return
        val (title, message) = when (status) {
            is ScheduleLogic.Status.Bedtime ->
                "Bedtime" to "Apps are off until ${KidsSchedule.timeLabel(status.wakeMinute)}."
            else -> "Time's up" to "You've used all of today's time. Ask a parent if you need more."
        }
        // Bedtime is a night sky with a sleepy moon; a day's time being used up is a bright sky with a friendly sun.
        KidLockStyle.apply(root.findViewById(R.id.kid_lock), night = status is ScheduleLogic.Status.Bedtime)
        root.findViewById<android.widget.TextView>(R.id.kid_lock_title).text = title
        root.findViewById<android.widget.TextView>(R.id.kid_lock_message).text = message
        root.findViewById<android.widget.TextView>(R.id.kid_lock_time).text =
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        root.findViewById<View>(R.id.kid_lock_parent).setOnClickListener {
            KidsScheduleDialogs.parentUnlock(context, kid, status) { checkSchedule() }
        }
        // Nothing behind the lock can be reached or seen: hide the content, stop the video.
        root.findViewById<View>(R.id.home_content).visibility = View.INVISIBLE
        backgroundPlayer?.stop()
        holdAudioFocus()
        val lock = root.findViewById<View>(R.id.kid_lock)
        if (lock.visibility != View.VISIBLE) {
            lock.visibility = View.VISIBLE
            // The lock opens the way a profile does: the ring draws on black and the screen opens through it.
            if (IntroAnimation.current != null) {
                // A profile is being opened onto the lock, so the ring has no home screen to wait for.
                IntroAnimation.release(BACKGROUND_READY)
                IntroAnimation.release(APPS_READY)
            } else {
                IntroAnimation.play(requireActivity().findViewById(android.R.id.content), expectHome = false)
            }
            // After layout, so focus lands on the button even when the lock appears as the screen opens.
            root.findViewById<View>(R.id.kid_lock_parent).apply { isFocusable = true; post { requestFocus() } }
        }
    }

    private var audioFocus: android.media.AudioFocusRequest? = null

    /**
     * While a kid is locked out, take audio focus so a player in the app they were using pauses
     * instead of playing on behind the lock. Players that ignore focus can't be stopped.
     */
    private fun holdAudioFocus() {
        val context = context ?: return
        if (audioFocus != null) return
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        context.getSystemService(android.media.AudioManager::class.java).requestAudioFocus(request)
        audioFocus = request
    }

    private fun releaseAudioFocus() {
        val request = audioFocus ?: return
        audioFocus = null
        context?.getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private fun hideScheduleLock() {
        releaseAudioFocus()
        val root = view ?: return
        val lock = root.findViewById<View>(R.id.kid_lock)
        if (lock.visibility != View.VISIBLE) return
        lock.visibility = View.GONE
        root.findViewById<View>(R.id.home_content).visibility = View.VISIBLE
        // The video was stopped behind the lock.
        if ((activeMode == "video" || activeMode == "birthday") && backgroundPlayer != null) {
            backgroundPlayer?.start(lifecycleScope)
        }
    }

    private fun updateClock() {
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        timeTextView?.text = format.format(Date())
    }

    private var weatherJob: Job? = null

    /** True once the panel shows weather (fresh or saved) rather than a loading bar or an error. */
    private var showingWeather = false

    /** True while the panel shows an error or saved weather, so a returning network should refetch. */
    private var weatherNeedsRefresh = false
    private var alertBadge: android.widget.TextView? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun fetchWeather() {
        if (weatherJob?.isActive == true) return
        val context = context ?: return
        weatherJob = lifecycleScope.launch {
            alertBadge?.let { badge ->
                if (AppSettings.showWeatherAlerts(context)) {
                    com.portal.tv.repo.WeatherAlerts.cached(context).firstOrNull()?.let { first ->
                        val all = com.portal.tv.repo.WeatherAlerts.cached(context)
                        badge.text = AlertStyle.badgeText(all)
                        badge.setTextColor(AlertStyle.textColor(first))
                        badge.background = AlertStyle.background(first, resources.displayMetrics.density * 12)
                        badge.visibility = View.VISIBLE
                    }
                }
            }
            // Show the last saved weather straight away, dimmed, while the refresh runs.
            if (!showingWeather) {
                WeatherRepository.cached(context)?.report?.let { bindWeather(it, stale = true) }
            }
            if (!showingWeather) {
                // Nothing to show yet: a loading bar until the first result.
                setWeatherErrorVisible(false)
                weatherTextView?.text = ""
                windArrowView?.visibility = View.GONE
                (celestialView?.parent as? View)?.visibility = View.GONE
                weatherProgress?.visibility = View.VISIBLE
            }
            try {
                val outcome = WeatherRepository.load(context)
                val report = outcome.report
                if (report != null) bindWeather(report, stale = outcome.stale)
                else showWeatherError(outcome.error ?: "Weather error")
            } finally {
                weatherProgress?.visibility = View.GONE
            }
            showAlerts()
        }
    }

    /** The most serious active weather alert as a coloured badge under the weather; hidden when there are none. */
    private suspend fun showAlerts() {
        val context = context ?: return
        val badge = alertBadge ?: return
        if (!AppSettings.showWeatherAlerts(context)) {
            badge.visibility = View.GONE
            return
        }
        fun show(alerts: List<com.portal.tv.repo.WeatherAlerts.Alert>) {
            showSevere(alerts.firstOrNull { it.isSevere }, alerts.count { it.isSevere })
            if (alerts.isEmpty()) {
                badge.visibility = View.GONE
            } else {
                badge.text = AlertStyle.badgeText(alerts)
                badge.setTextColor(AlertStyle.textColor(alerts.first()))
                badge.background = AlertStyle.background(alerts.first(), resources.displayMetrics.density * 12)
                badge.visibility = View.VISIBLE
            }
        }
        show(com.portal.tv.repo.WeatherAlerts.cached(context))   // straight away, then refreshed
        val alerts = com.portal.tv.repo.WeatherAlerts.load(context)
        if (isAdded) show(alerts)
    }

    /** The red banner for the most serious severe alert, or hidden when none is active. */
    private fun showSevere(alert: com.portal.tv.repo.WeatherAlerts.Alert?, count: Int) {
        val root = view ?: return
        val banner = root.findViewById<View>(R.id.severe_banner)
        val birthday = root.findViewById<View>(R.id.tv_birthday)
        val d = resources.displayMetrics.density
        if (alert == null || !AppSettings.showWeatherAlerts(requireContext())) {
            banner.visibility = View.GONE
            (birthday.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = (40 * d).toInt(); birthday.layoutParams = it }
            return
        }
        root.findViewById<android.widget.TextView>(R.id.severe_title).text =
            "⚠  " + alert.event.uppercase() + if (count > 1) "  +${count - 1}" else ""
        root.findViewById<android.widget.TextView>(R.id.severe_detail).text =
            listOf(AlertStyle.until(requireContext(), alert), alert.area).filter { it.isNotEmpty() }.joinToString("  ·  ")
        banner.visibility = View.VISIBLE
        // A birthday greeting sits just below it rather than behind it.
        (birthday.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = (118 * d).toInt(); birthday.layoutParams = it }
    }

    private fun bindWeather(r: WeatherRepository.Report, stale: Boolean) {
        val context = context ?: return
        setWeatherErrorVisible(false)
        val metric = AppSettings.isMetric(context)
        val tempDisplay = if (metric) "${Math.round(r.tempC)}°C"
        else "${Math.round(r.tempC * 9.0 / 5.0 + 32.0)}°F"
        val windDisplay = if (metric) "${Math.round(r.windKmh)} km/h" else "${Math.round(r.windKmh * 0.621371)} mph"

        // The sun or moon with the weather in front of it, drawn as one picture.
        celestialView?.setImageDrawable(WeatherGlyphDrawable(context, r.code, r.isDay))
        overlayView?.visibility = View.GONE

        weatherTextView?.text = tempDisplay
        // Wind: arrow points in the direction wind is blowing TOWARD (dir + 180)
        windArrowView?.rotation = ((r.windDirection + 180.0) % 360.0).toFloat()
        windTextView?.text = windDisplay

        weatherRow?.alpha = if (stale) 0.6f else 1f
        showingWeather = true
        weatherNeedsRefresh = stale
    }

    private fun showWeatherError(message: String) {
        showingWeather = false
        weatherNeedsRefresh = true
        weatherRow?.alpha = 1f
        setWeatherErrorVisible(true)
        weatherTextView?.text = message
    }

    /** Swaps the weather icon/wind readout for the retry button while an error is shown. */
    private fun setWeatherErrorVisible(error: Boolean) {
        val gone = if (error) View.GONE else View.VISIBLE
        celestialView?.parent?.let { (it as View).visibility = gone }
        windArrowView?.visibility = gone
        windTextView?.visibility = gone
        weatherRetry?.visibility = if (error) View.VISIBLE else View.GONE
        // The weather page has nothing to show on error, so let Retry take the focus instead.
        view?.findViewById<View>(R.id.panel_weather)?.isFocusable = !error
        if (error) weatherRetry?.requestFocus()
    }

    /** Refetches as soon as the network comes back, if the panel is showing an error or old data. */
    private fun watchNetwork() {
        val cm = context?.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Give DNS a moment to settle before trying.
                weatherHandler.postDelayed({
                    if (isAdded && weatherNeedsRefresh) fetchWeather()
                }, 2000)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w("Portal", "Network watch failed: ${e.message}")
        }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            context?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // already unregistered
        }
    }

    override fun onPause() {
        super.onPause()
        com.portal.tv.extensions.HostEvents.onItemsChanged = null
        com.portal.tv.extensions.HostEvents.onTilesChanged = null
        com.portal.tv.alerts.SevereAlertService.homeShowing = false
        backgroundPlayer?.stop()
        shaderView?.onPause()
        // An extension's background is let go of while the home screen isn't showing, so it isn't running for nothing.
        extensionBackground?.stop()
        launchAnimation?.appStarted()
        timeHandler.removeCallbacks(timeRunnable)
        weatherHandler.removeCallbacksAndMessages(null)
        scheduleHandler.removeCallbacks(scheduleRunnable)
        unwatchNetwork()
    }

    override fun onDestroyView() {
        launchAnimation?.cancel()
        launchAnimation = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        com.portal.tv.alerts.SevereAlertService.homeShowing = true
        // Back from an app: let the launch animation go.
        launchAnimation?.dismiss()
        updateClock()
        timeHandler.postDelayed(timeRunnable, 1000)
        applySearchLevel()
        refreshUpdateButton()
        refreshExtensionItems()
        com.portal.tv.extensions.HostEvents.onItemsChanged = { refreshExtensionItems() }
        com.portal.tv.extensions.HostEvents.onTilesChanged = { loadApps() }
        // Once a day, if the person hasn't turned it off, look for a newer version of Portal.
        viewLifecycleOwner.lifecycleScope.launch {
            if (com.portal.tv.update.UpdateChecks.checkIfDue(requireContext())) refreshUpdateButton()
            // The catalog's withdrawn list, kept fresh while extensions are installed.
            if (com.portal.tv.extensions.CatalogChecks.checkIfDue(requireContext())) {
                // A withdrawal may have switched something off, and a newer version may now be on offer.
                refreshExtensionItems()
                refreshUpdateButton()
                loadApps()
            }
        }
        // Rebuild the app lists so launches and installs made elsewhere show up.
        loadApps()
        fetchWeather()
        weatherHandler.postDelayed(weatherRunnable, 5 * 60 * 1000L)
        watchNetwork()
        // Back from an app: count the time it was open, then check the kid's schedule.
        KidsMonitorService.stop(requireContext())
        stopKidsApp()
        KidsSchedule.endSession(requireContext())
        KidsSchedule.setSessionPackage(requireContext(), null)
        scheduleRunnable.run()

        val view = view ?: return
        // A new day (or an edited birthday list) changes what the background should be.
        if (todaysBirthdays() != appliedBirthdays || seasonalNow() != appliedSeasonal ||
            (activeMode == "video" && BackgroundRepository.selectedVideo(requireContext()) != appliedChosen)) {
            applyBackground(view)
            return
        }
        val locked = view.findViewById<View>(R.id.kid_lock).visibility == View.VISIBLE
        if ((activeMode == "video" || activeMode == "birthday") && backgroundPlayer != null && !locked) {
            backgroundPlayer?.start(lifecycleScope)
        } else if (activeMode == "shader") {
            shaderView?.onResume()
        } else if (activeMode == com.portal.tv.extensions.BackgroundChoice.MODE) {
            // Starts the extension again (Portal's gradient shows until its first picture is back).
            applyBackground(view)
        }
    }

    private companion object {
        /** What the intro waits for before it opens: the background showing, and the apps listed. */
        const val BACKGROUND_READY = "background"
        const val APPS_READY = "apps"
    }
}
