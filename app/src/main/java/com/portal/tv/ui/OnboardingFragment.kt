package com.portal.tv.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.portal.tv.R
import com.portal.tv.adb.SelfSetup
import com.portal.tv.repo.Onboarding
import com.portal.tv.repo.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup. The TV is set up from the TV itself: turn on developer options and Wireless debugging, then
 * Portal connects to its own debug service (the TV asks once) and does what scripts/install.sh does from a
 * computer. Anything that already holds, or that can't be done that way, is handled by the steps after it:
 * the home screen, alerts over apps and voice search. Then the weather location and the first profile.
 *
 * Steps whose result already holds are skipped. Nothing else can be skipped, but every step moves on once
 * it has been answered, so a declined permission doesn't block the setup.
 */
class OnboardingFragment : Fragment() {

    companion object {
        private const val ARG_RERUN = "rerun"
        private const val ARG_READY = "ready"
        private val STOCK_LAUNCHERS = listOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher"
        )

        /** Pages that only exist to reach a result, and so are left out when it already holds. */
        private val SKIPPABLE = setOf(Step.DEVMODE, Step.DEBUGGING, Step.CONNECT, Step.HOME, Step.OVERLAY, Step.MIC)

        private const val STATE_STEP = "step"
        private const val STATE_ANSWERED = "answered"

        /** The setup shown at first run; it ends at the profile picker. */
        fun forFirstRun() = OnboardingFragment()

        /** The last page of the first-run setup, shown once the first profile exists. */
        fun forReady() = OnboardingFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_READY, true) }
        }

        /** The setup run again from Settings; it ends back where it was opened. */
        fun forRerun() = OnboardingFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_RERUN, true) }
        }
    }

    private enum class Step { WELCOME, DEVMODE, DEBUGGING, CONNECT, HOME, OVERLAY, MIC, LOCATION, PROFILE, WEATHER, READY }

    private val rerun get() = arguments?.getBoolean(ARG_RERUN) == true
    private var step = Step.WELCOME

    /**
     * The pages in order. A page whose result already holds is left out, and running the setup again has no
     * profile to create. The page being shown always stays in the list.
     */
    private val steps get() = Step.values().filter { s ->
        s == step || when {
            s == Step.PROFILE -> !rerun
            // Only offered when the weather extension that ships with Portal isn't installed yet.
            s == Step.WEATHER -> !weatherInstalled()
            s in SKIPPABLE -> !satisfied(s)
            else -> true
        }
    }

    /** Whether the weather extension is installed (looked up once, and again when it may have changed). */
    private var installedMemo: Boolean? = null
    private fun weatherInstalled(): Boolean =
        installedMemo ?: com.portal.tv.extensions.BundledExtension.isInstalled(requireContext()).also { installedMemo = it }

    /** The pages as they stood when the current one was shown, so the count and dots don't shift under the reader. */
    private var shown: List<Step> = emptyList()

    /** Show a page. */
    private fun goTo(s: Step) {
        step = s
        shown = steps
    }

    /** Steps the person has dealt with, by granting, declining or being told it can't be done here. */
    private val answered = HashSet<Step>()

    /** Set while the system's overlay screen is open, so coming back counts as an answer. */
    private var waitingOverlay = false

    /** The home-role request is open, so an answer that arrives while Portal is hidden still moves on. */
    private var homeRequested = false

    private val watcher = Handler(Looper.getMainLooper())
    private var watchTries = 0

    /**
     * The result of the role request only reaches Portal once it is back on screen, which is too late: Android
     * has already sent the old home screen to the front. Portal's process keeps running, so ask it directly.
     */
    private val watchHome = object : Runnable {
        override fun run() {
            if (!homeRequested || !isAdded) return
            if (roleHeld()) {
                Log.d("Portal", "setup: Portal became the home app")
                afterHomeGranted()
            } else if (++watchTries < 150) {
                watcher.postDelayed(this, 400)
            }
        }
    }

    /** Pages whose Settings screen has been opened. Coming back counts as an answer, so the setup can't get stuck. */
    private val opened = HashSet<Step>()

    /** The page whose setting Portal is waiting to see change while Settings is open. */
    private var watching: Step? = null
    private var watchSettingTries = 0
    private val watchSetting = object : Runnable {
        override fun run() {
            val s = watching ?: return
            if (!isAdded) return
            if (satisfied(s)) {
                Log.d("Portal", "setup: $s is now set")
                watching = null
                answered += s
                bringToFront()
                if (step == s) next()
            } else if (++watchSettingTries < 600) {
                watcher.postDelayed(this, 400)
            }
        }
    }

    /** Where the connection to the TV's own debug service has got to. */
    private enum class Connect { IDLE, WORKING, FAILED, DONE }
    private var connect = Connect.IDLE
    private var connectMessage = ""

    /** Extra line under the status, for when a step can't be done from the app. */
    private var note = ""
    private var waitingInstallPermission = false

    private lateinit var stepText: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    private lateinit var dots: LinearLayout

    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        answered += Step.HOME
        Log.d("Portal", "setup: home role request done, held=${roleHeld()}")
        if (roleHeld()) afterHomeGranted() else { homeRequested = false; render() }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        answered += Step.MIC
        render()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_onboarding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stepText = view.findViewById(R.id.onboarding_step)
        title = view.findViewById(R.id.onboarding_title)
        body = view.findViewById(R.id.onboarding_body)
        status = view.findViewById(R.id.onboarding_status)
        primary = view.findViewById(R.id.onboarding_primary)
        secondary = view.findViewById(R.id.onboarding_secondary)
        dots = view.findViewById(R.id.onboarding_dots)

        // The profile exists. The weather extension is offered next, unless it is already there.
        if (arguments?.getBoolean(ARG_READY) == true) step = if (weatherInstalled()) Step.READY else Step.WEATHER
        else if (!rerun) Onboarding.savedStep(requireContext())?.let { saved ->
            step = Step.values().firstOrNull { it.name == saved } ?: Step.WELCOME
            // Only the weather choice is kept. A permission answered earlier may no longer hold, so those
            // steps start fresh and show their button again.
            if (Step.LOCATION.name in Onboarding.savedAnswered(requireContext())) answered += Step.LOCATION
        }
        savedInstanceState?.let { state ->
            step = Step.values()[state.getInt(STATE_STEP)]
            state.getIntArray(STATE_ANSWERED)?.forEach { answered += Step.values()[it] }
        }

        shown = steps

        // Told when the install finishes (it ends with Android's own screen, so it lands later): show it as done.
        com.portal.tv.extensions.BundledExtension.onInstalled = {
            view.post {
                installedMemo = true
                if (isAdded && ::primary.isInitialized) render()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        // The profile exists by now, so the first run can't go back past this page.
                        step == Step.READY && !rerun -> Unit
                        // The profile exists too, so the first run doesn't go back to creating it.
                        step == Step.WEATHER && !rerun -> Unit
                        steps.indexOf(step) > 0 -> {
                            // Going back forgets this step's answer, and the earlier one's, so each can be redone.
                            val earlier = steps[steps.indexOf(step) - 1]
                            answered -= step
                            answered -= earlier
                            homeRequested = false
                            watching = null
                            watcher.removeCallbacks(watchHome)
                            watcher.removeCallbacks(watchSetting)
                            goTo(earlier)
                            note = ""
                            render()
                        }
                        rerun -> parentFragmentManager.popBackStack()
                        // First run: there is nowhere to go back to.
                    }
                }
            })

        render()
    }

    override fun onDestroyView() {
        com.portal.tv.extensions.BundledExtension.onInstalled = null
        watcher.removeCallbacks(watchHome)
        watcher.removeCallbacks(watchSetting)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (waitingOverlay) {
            waitingOverlay = false
            answered += Step.OVERLAY
        }
        if (opened.isNotEmpty()) { answered += opened; opened.clear() }
        if (homeRequested && step == Step.HOME && roleHeld()) { afterHomeGranted(); return }
        // Back from Android's install screen: it may have been installed, or the person may have cancelled.
        if (step == Step.WEATHER) {
            installedMemo = null
            // Still not installed on coming back: the person cancelled, so drop the "confirm it" note.
            if (!weatherInstalled()) note = ""
            // Back from Android's "Install unknown apps" page: if Portal is allowed now, carry straight on to the install.
            if (waitingInstallPermission) {
                waitingInstallPermission = false
                if (com.portal.tv.extensions.CatalogInstaller.canInstall(requireContext())) { installWeather(); return }
                note = "Portal still isn't allowed to install apps. Choose Install to try again, or Skip."
            }
        }
        if (::primary.isInitialized) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step.ordinal)
        outState.putIntArray(STATE_ANSWERED, answered.map { it.ordinal }.toIntArray())
    }

    // ---------- What each step knows ----------

    private fun roleManager() = requireContext().getSystemService(RoleManager::class.java)
        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }

    private fun roleHeld() = roleManager()?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /**
     * Google TV's Home button starts its own launcher by name, without asking which app holds the home role, so
     * holding the role isn't enough while that launcher is on. Only turning it off (from a computer) fixes that.
     * These are the same packages scripts/install.sh turns off.
     */
    private fun stockLauncherOn(): Boolean {
        val packages = requireContext().packageManager
        return STOCK_LAUNCHERS.any { name ->
            try { packages.getApplicationInfo(name, 0).enabled } catch (e: PackageManager.NameNotFoundException) { false }
        }
    }

    private fun canOverlay() = Settings.canDrawOverlays(requireContext())
    private fun micGranted() =
        requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun micAvailable() = SpeechRecognizer.isRecognitionAvailable(requireContext())

    private fun settingOn(name: String) = Settings.Global.getInt(requireContext().contentResolver, name, 0) == 1
    private fun devModeOn() = settingOn(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
    private fun wirelessDebuggingOn() = settingOn("adb_wifi_enabled")

    private fun homeWorks() = roleHeld() && !stockLauncherOn()
    private fun micFine() = micGranted() || !micAvailable()

    /** Everything the connection to the TV is there to set up. */
    private fun allSet() = homeWorks() && canOverlay() && micFine()

    /** Whether a skippable page has already got what it is for. */
    private fun satisfied(s: Step) = when (s) {
        Step.DEVMODE -> devModeOn() || allSet()
        Step.DEBUGGING -> wirelessDebuggingOn() || allSet()
        Step.CONNECT -> allSet()
        Step.HOME -> homeWorks()
        Step.OVERLAY -> canOverlay()
        Step.MIC -> micFine()
        else -> false
    }

    /** Whether the step has what it needs, so the button can say Continue. */
    private fun isDone(s: Step) = when (s) {
        Step.WELCOME, Step.PROFILE, Step.READY -> true
        Step.WEATHER -> weatherInstalled() || s in answered
        Step.DEVMODE -> devModeOn() || s in answered
        Step.DEBUGGING -> wirelessDebuggingOn() || s in answered
        Step.CONNECT -> allSet() || s in answered
        Step.HOME -> roleHeld() || s in answered
        Step.OVERLAY -> canOverlay() || s in answered
        Step.MIC -> micGranted() || !micAvailable() || s in answered
        Step.LOCATION -> s in answered
    }

    // ---------- Drawing ----------

    private fun render() {
        val numbered = shown.filter { it != Step.WELCOME && it != Step.READY }
        stepText.text = if (step in numbered) "STEP ${numbered.indexOf(step) + 1} OF ${numbered.size}" else ""
        secondary.visibility = View.GONE
        val done = isDone(step)
        var good: Boolean? = null
        var statusText = ""

        when (step) {
            Step.WELCOME -> {
                title.text = "Welcome to Portal"
                body.text = "A few quick steps to get your TV ready, then you'll create your first profile."
                primary.text = "Get started"
            }
            Step.DEVMODE -> {
                title.text = "Turn on developer options"
                body.text = "Portal needs this once, to finish setting up your TV for you. Open About, then select " +
                    "Build seven times. Press Back to return here."
                good = devModeOn()
                statusText = if (devModeOn()) "Developer options are on" else "Developer options are off"
                val stuck = "Couldn't turn them on? Continue anyway, and Portal will try."
                if (!devModeOn() && done) note = stuck else if (note == stuck) note = ""
                primary.text = if (done) "Continue" else "Open About"
            }
            Step.DEBUGGING -> {
                title.text = "Turn on Wireless debugging"
                body.text = "In Developer options, turn on Wireless debugging. Portal only uses it to finish this " +
                    "setup, and you can turn it off afterwards. Press Back to return here."
                good = wirelessDebuggingOn()
                statusText = if (wirelessDebuggingOn()) "Wireless debugging is on" else "Wireless debugging is off"
                val stuck = "Not available? Wireless debugging needs a Wi-Fi connection. Continue anyway, and Portal will try."
                if (!wirelessDebuggingOn() && done) note = stuck else if (note == stuck) note = ""
                primary.text = if (done) "Continue" else "Open Developer options"
            }
            Step.CONNECT -> {
                title.text = "Let Portal set up your TV"
                body.text = "Portal will make itself your home screen and allow alerts and voice search. When the " +
                    "TV asks to allow debugging, choose Always allow."
                when {
                    allSet() -> {
                        good = true
                        statusText = "Done. Portal is your home screen, with alerts and voice search allowed."
                        note = "You can turn Wireless debugging off now."
                        primary.text = "Continue"
                    }
                    connect == Connect.WORKING -> {
                        statusText = connectMessage
                        primary.text = "Working…"
                    }
                    connect == Connect.FAILED -> {
                        good = false
                        statusText = connectMessage
                        primary.text = "Try again"
                        secondary.text = "Set up another way"
                        secondary.visibility = View.VISIBLE
                    }
                    connect == Connect.DONE -> {
                        statusText = connectMessage
                        primary.text = "Continue"
                    }
                    answered.contains(Step.CONNECT) -> primary.text = "Continue"
                    else -> primary.text = "Set up my TV"
                }
            }
            Step.HOME -> {
                title.text = "Make Portal your home screen"
                body.text = "So the Home button on your remote opens Portal."
                when {
                    roleHeld() && !stockLauncherOn() -> { good = true; statusText = "Portal is your home screen" }
                    roleHeld() -> {
                        good = false
                        statusText = "Portal is set, but the TV's own launcher still answers the Home button. " +
                            "To finish, run scripts/install.sh from a computer."
                    }
                    else -> { good = false; statusText = "Not your home screen yet" }
                }
                primary.text = if (done) "Continue" else "Set as home screen"
            }
            Step.OVERLAY -> {
                title.text = "Show alerts over other apps"
                body.text = "Severe weather warnings and kids' time limits appear on top of whatever app is open."
                good = canOverlay()
                statusText = if (canOverlay()) "Allowed" else "Not allowed yet"
                primary.text = if (done) "Continue" else "Allow"
            }
            Step.MIC -> {
                title.text = "Voice search"
                body.text = "Lets you speak to Portal's search with the microphone on your remote."
                if (!micAvailable()) {
                    statusText = "This TV has no speech recognizer, so voice search won't show"
                } else {
                    good = micGranted()
                    statusText = if (micGranted()) "Allowed" else "Not allowed yet"
                }
                primary.text = if (done) "Continue" else "Allow"
            }
            Step.LOCATION -> {
                title.text = "Where's your weather?"
                body.text = "Automatic uses the rough location of your internet connection. Choose a city for a precise forecast and local alerts."
                if (done) {
                    val city = WeatherRepository.manualCity(requireContext())
                    statusText = if (city != null) "Weather for $city" else "Weather from your connection's location"
                    good = true
                    primary.text = "Continue"
                } else {
                    primary.text = "Automatic"
                    secondary.text = "Choose a city…"
                    secondary.visibility = View.VISIBLE
                }
            }
            Step.PROFILE -> {
                title.text = "Create your profile"
                body.text = "Add the first profile. Choose a Portal profile, or sign in with a Google account."
                primary.text = "Create profile"
            }
            Step.WEATHER -> {
                title.text = "Get the weather"
                if (weatherInstalled()) {
                    body.text = "The weather extension is installed. Portal shows the weather, alerts and radar from it."
                    good = true
                    statusText = "Installed"
                    primary.text = "Continue"
                } else {
                    body.text = "To show weather, Portal needs this extension: Open-Meteo + NWS. It comes with Portal. It gets the " +
                        "forecast from Open-Meteo and US alerts from the National Weather Service, and is told roughly where your " +
                        "TV is (to within about 10 km). Android may first ask you to let Portal install apps (it calls them \"unknown " +
                        "apps\" because they don't come from the Play Store), then to confirm the install. You can also install it " +
                        "later from Settings, Weather."
                    primary.text = "Install"
                    secondary.text = "Skip"
                    secondary.visibility = View.VISIBLE
                }
            }
            Step.READY -> {
                if (rerun) {
                    title.text = "All set"
                    body.text = "Setup is finished. You can run it again from Settings, System."
                    primary.text = "Done"
                } else {
                    val name = com.portal.tv.repo.ProfileManager.getActive(requireContext())?.name
                    title.text = "You're all set"
                    body.text = if (name != null) "Welcome, $name. Portal is ready." else "Portal is ready."
                    primary.text = "Finish"
                }
            }
        }

        status.text = listOf(statusText, note).filter { it.isNotEmpty() }.joinToString("\n")
        status.setTextColor(
            when (good) {
                true -> 0xFF7DDC8F.toInt()
                false -> 0xFFFFC857.toInt()
                null -> resources.getColor(R.color.text_secondary, null)
            }
        )
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE

        if (!rerun) Onboarding.saveProgress(requireContext(), step.name, answered.filter { it == Step.LOCATION }.map { it.name }.toSet())
        primary.setOnClickListener { if (done) next() else act() }
        secondary.setOnClickListener {
            if (step == Step.CONNECT) {
                // Do it the long way: the steps after this one ask for each permission in turn.
                answered += Step.CONNECT
                connect = Connect.IDLE
                next()
            } else if (step == Step.WEATHER) {
                // Skipped: Portal doesn't offer it again at each start; the button in Settings, Weather still installs it.
                com.portal.tv.extensions.BundledExtension.setDeclined(requireContext(), true)
                answered += Step.WEATHER
                next()
            } else {
                LocationPicker.show(this) { answered += Step.LOCATION; render() }
            }
        }
        drawDots()
        primary.requestFocus()
    }

    private fun drawDots() {
        dots.removeAllViews()
        val size = (10 * resources.displayMetrics.density).toInt()
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (s in shown) {
            val dot = View(requireContext())
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (s == step) 0xFF6C63FF.toInt() else 0x33FFFFFF)
            }
            dots.addView(dot, LinearLayout.LayoutParams(size, size).apply { marginStart = gap; marginEnd = gap })
        }
    }

    // ---------- Doing ----------

    private fun act() {
        val context = requireContext()
        note = ""
        when (step) {
            Step.DEVMODE -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS, Step.DEVMODE,
                "Open Settings, System, About yourself, then select Build seven times.")
            Step.DEBUGGING -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Step.DEBUGGING,
                "Open Settings, System, Developer options yourself, then turn on Wireless debugging.")
            Step.CONNECT -> startConnecting()
            Step.HOME -> {
                val roles = roleManager()
                if (roles != null) {
                    homeRequested = true
                    watchTries = 0
                    watcher.postDelayed(watchHome, 400)
                    homeRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } else {
                    note = "This TV can't do this from an app. Run scripts/install.sh from a computer to set it up."
                    answered += Step.HOME
                    render()
                }
            }
            Step.OVERLAY -> {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                if (intent.resolveActivity(context.packageManager) != null) {
                    waitingOverlay = true
                    startActivity(intent)
                } else {
                    note = "This TV has no screen for this. From a computer, run:\nadb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
                    answered += Step.OVERLAY
                    render()
                }
            }
            Step.MIC -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            Step.WEATHER -> installWeather()
            Step.LOCATION -> {
                WeatherRepository.useAutomaticLocation(context)
                answered += Step.LOCATION
                render()
            }
            else -> next()
        }
    }

    /** Starts installing the weather extension. Android shows its own confirmation; coming back, this page shows whether it went in. */
    private fun installWeather() {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { com.portal.tv.extensions.BundledExtension.installIfNeeded(app, force = true) }
            if (!isAdded) return@launch
            when (result) {
                com.portal.tv.extensions.BundledExtension.Result.STARTED -> note = "Confirm the install on the screen Android shows."
                com.portal.tv.extensions.BundledExtension.Result.UP_TO_DATE -> { installedMemo = null; note = "" }
                com.portal.tv.extensions.BundledExtension.Result.NOT_ALLOWED -> {
                    // Setup normally allows this itself; if it couldn't, Android's own page for it is the way.
                    note = "Android's next screen is called \"Install unknown apps\". Select Portal's switch to turn it on, then press Back."
                    waitingInstallPermission = true
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
                    }.onFailure { waitingInstallPermission = false; note = "This TV has no screen for this. You can Skip and install it later." }
                }
                com.portal.tv.extensions.BundledExtension.Result.FOREIGN ->
                    note = "Another app is using the weather extension's name, so Portal left it alone. You can Skip."
                else -> note = "This copy of Portal doesn't have the weather extension. You can Skip."
            }
            render()
        }
    }

    /** Open a Settings screen, and keep an eye on the setting that page is for until it changes. */
    private fun openSettings(action: String, forStep: Step, fallback: String) {
        val context = requireContext()
        val intent = Intent(action)
        if (intent.resolveActivity(context.packageManager) != null) {
            try {
                startActivity(intent)
                opened += forStep
                watching = forStep
                watchSettingTries = 0
                watcher.removeCallbacks(watchSetting)
                watcher.postDelayed(watchSetting, 400)
                return
            } catch (e: Exception) {
                Log.d("Portal", "setup: couldn't open $action", e)
            }
        }
        note = fallback
        render()
    }

    /** Bring Portal back over Settings once the setting has changed. Android may refuse; Back still works. */
    private fun bringToFront() {
        val app = requireContext().applicationContext
        watcher.postDelayed({
            if (isAdded && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                Log.d("Portal", "setup: bringing Portal back to the front")
                try {
                    app.startActivity(
                        Intent(app, com.portal.tv.PortalActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                } catch (e: Exception) {
                    Log.d("Portal", "setup: couldn't bring Portal back", e)
                }
            }
        }, 300)
    }

    /** Connect to the TV's own debug service, then let it run the setup commands. */
    private fun startConnecting() {
        if (connect == Connect.WORKING) return
        val app = requireContext().applicationContext
        connect = Connect.WORKING
        connectMessage = "Look at your TV and choose Always allow when it asks about debugging…"
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            val connected = withContext(Dispatchers.IO) { SelfSetup.connect(app) }
            if (connected is SelfSetup.Result.Failed) {
                connect = Connect.FAILED
                connectMessage = "Couldn't connect: ${connected.why} Make sure Wireless debugging is on, and choose Always allow when the TV asks."
                render()
                return@launch
            }
            connectMessage = "Setting up your TV…"
            render()
            val applied = withContext(Dispatchers.IO) { SelfSetup.apply(app) }
            if (applied is SelfSetup.Result.Failed) {
                connect = Connect.FAILED
                connectMessage = "Setup stopped: ${applied.why}"
            } else {
                connect = Connect.DONE
                answered += Step.CONNECT
                if (!allSet()) {
                    // Some TVs take a moment to report the new home app; look again shortly.
                    connectMessage = "Finishing up…"
                    watcher.postDelayed({ if (isAdded) render() }, 1500)
                }
            }
            render()
            if (connect == Connect.DONE) bringToFront()
        }
    }

    /**
     * Android sends the old home screen to the front the moment Portal becomes the home app, hiding the
     * setup. Move on, and bring Portal back after the system has finished moving things about.
     */
    private fun afterHomeGranted() {
        if (!homeRequested) return
        homeRequested = false
        watcher.removeCallbacks(watchHome)
        answered += Step.HOME
        // Stay on this page when the TV's own launcher still wins, so the way to finish is on screen.
        if (step == Step.HOME && !stockLauncherOn()) next() else render()
        val app = requireContext().applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                Log.d("Portal", "setup: bringing Portal back to the front")
                app.startActivity(
                    Intent(app, com.portal.tv.PortalActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }, 1200)
    }

    private fun next() {
        note = ""
        when (step) {
            // The profile is made in the picker; it comes back to the last page when one exists.
            Step.PROFILE -> parentFragmentManager.beginTransaction()
                .replace(R.id.container, ProfilePickerFragment.forOnboarding())
                .commit()
            Step.READY -> finish()
            else -> { goTo(steps[steps.indexOf(step) + 1]); render() }
        }
    }

    private fun finish() {
        Onboarding.markDone(requireContext())
        if (rerun) {
            parentFragmentManager.popBackStack()
        } else {
            // Open the new profile the way choosing one does: the home screen under the ring.
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, HomeFragment())
                .commit()
            IntroAnimation.play(requireActivity().findViewById(android.R.id.content), expectHome = true)
        }
    }
}
