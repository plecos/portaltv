package com.portal.tv.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.R
import com.portal.tv.auth.GoogleDeviceAuth
import com.portal.tv.data.AppInfo
import com.portal.tv.data.Profile
import com.portal.tv.repo.AppRepository
import com.portal.tv.repo.ParentPin
import com.portal.tv.repo.ProfileCode
import com.portal.tv.repo.ProfileManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Who's watching?" profile picker.
 *
 * Shows all Portal profiles as avatars. Selecting one sets it as the active
 * profile and returns to the home screen, where that profile's usage data
 * and settings take over. New profiles are either plain Portal profiles or
 * linked to a Google account through the TV sign-in flow.
 *
 * Profiles can be marked Kids. While a kid profile is active, adding,
 * editing, deleting and switching to an adult profile all ask for the parent PIN.
 *
 * Remote controls:
 *  - D-pad: move between avatars, OK to select
 *  - OK on "Add New" opens the add-profile dialog
 *  - Hold OK, or Menu (⋮), on a profile opens rename/delete options (Google
 *    profiles can only be deleted)
 *  - Back: return to the home screen without changing profiles
 */
class ProfilePickerFragment : Fragment() {

    companion object {
        private const val ARG_LAUNCH = "launch"
        private const val ARG_ONBOARDING = "onboarding"

        /** The picker shown at startup: choosing a profile replaces it with the home screen. */
        fun forLaunch() = ProfilePickerFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_LAUNCH, true) }
        }

        /** The picker inside the first-run setup, where the first profile is created. */
        fun forOnboarding() = ProfilePickerFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_LAUNCH, true)
                putBoolean(ARG_ONBOARDING, true)
            }
        }
    }

    private val isLaunch get() = arguments?.getBoolean(ARG_LAUNCH) == true

    /** Shown by the first-run setup: the new profile goes back to the setup's last page, not the home screen. */
    private val isOnboarding get() = arguments?.getBoolean(ARG_ONBOARDING) == true

    private lateinit var profilesRecycler: RecyclerView
    private lateinit var emptyHint: TextView
    private var signInJob: Job? = null

    /** The Google account just confirmed with Google; it becomes a profile once it's also on the TV. */
    private var pendingGoogle: GoogleDeviceAuth.GoogleProfile? = null

    private val accountChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val google = pendingGoogle ?: return@registerForActivityResult
            pendingGoogle = null
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            when {
                result.resultCode != Activity.RESULT_OK || email.isNullOrEmpty() ->
                    toast("${google.email} must be on this TV to add it as a profile")
                !email.equals(google.email, ignoreCase = true) ->
                    toast("You added $email to the TV but signed in as ${google.email}. Try again.")
                else -> addGoogleProfile(google)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profilesRecycler = view.findViewById(R.id.profile_grid)
        profilesRecycler.layoutManager = GridLayoutManager(requireContext(), 4)
        emptyHint = view.findViewById(R.id.tv_empty_hint)
        render()

        // No way out until a profile is active.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isLaunch || ProfileManager.getActive(requireContext()) == null) {
                        toast("Choose or create a profile to continue")
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            })

        // First run: go straight into creating a profile.
        if (savedInstanceState == null && ProfileManager.getAll(requireContext()).isEmpty()) {
            showAddChoice()
        }
    }

    private fun render() {
        val profiles = ProfileManager.getAll(requireContext())
        profilesRecycler.adapter = ProfileAdapter(
            profiles = profiles,
            activeProfileId = ProfileManager.getActiveId(requireContext()),
            onSelect = { profile -> selectProfile(profile) },
            onAddNew = { requireParent { showAddChoice() } },
            onLongPress = { profile -> requireParent { showProfileOptions(profile) } }
        )
        emptyHint.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        // As many columns as there are tiles (up to five), so a few profiles sit together in the middle.
        (profilesRecycler.layoutManager as GridLayoutManager).spanCount = (profiles.size + 1).coerceAtMost(5)
    }

    private fun selectProfile(profile: Profile) {
        val context = requireContext()
        // The active profile is only trusted without its code once Portal has been unlocked.
        if (!isLaunch && profile.id == ProfileManager.getActiveId(context)) {
            activate(profile)
            return
        }
        // Leaving a kid profile for an adult one needs the parent PIN, then the profile's own code if it has one.
        val open = {
            if (ProfileCode.isSet(context, profile.id)) {
                PinDialogs.verifyProfile(context, profile) { activate(profile) }
            } else {
                activate(profile)
            }
        }
        if (profile.isKids) open() else requireParent(open)
    }

    private fun activate(profile: Profile) {
        ProfileManager.setActive(requireContext(), profile.id)
        if (isOnboarding) {
            // The setup has one more page to show before the profile opens.
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, OnboardingFragment.forReady())
                .commit()
            return
        }
        Toast.makeText(requireContext(), "Hello, ${profile.name}!", Toast.LENGTH_SHORT).show()
        finishPicking()
        // Choosing a profile opens the home screen with the same ring as a fresh start.
        IntroAnimation.play(requireActivity().findViewById(android.R.id.content), expectHome = true)
    }

    /** Leave the picker: to the home screen at startup, otherwise back to where we came from. */
    private fun finishPicking() {
        if (isLaunch) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, HomeFragment())
                .commit()
        } else {
            parentFragmentManager.popBackStack()
        }
    }

    /** Called once a new profile is fully set up; at startup it is selected right away. */
    private fun onProfileReady(profile: Profile) {
        if (isLaunch || ProfileManager.getActive(requireContext()) == null) activate(profile)
    }

    // ---------- Add ----------

    private fun showAddChoice() {
        PortalDialog.Builder(requireContext())
            .setTitle("Add Profile")
            .setItems(arrayOf("Portal profile", "Google account")) { _, which ->
                if (which == 0) showCreateDialog() else startGoogleSignIn()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateDialog() {
        val (box, input) = nameInput()
        val kids = kidsCheckBox()
        box.addView(kids)
        PortalDialog.Builder(requireContext())
            .setTitle("New Profile")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Name can't be empty")
                    return@setPositiveButton
                }
                val isKids = kids.isChecked
                withPinReady(isKids) {
                    val colorIndex = ProfileManager.getAll(requireContext()).size
                    val profile = ProfileManager.addProfile(
                        requireContext(), name, colorIndex, isKids
                    )
                    render()
                    showAvatarPicker(profile) {
                        if (isKids) showManageApps(profile) { onProfileReady(profile) }
                        else onProfileReady(profile)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Google sign-in ----------

    /**
     * Step 2 of adding a Google profile: make sure that same account is on this
     * TV, since apps use the TV's Google accounts. The chooser is restricted to
     * the account we just confirmed, so it normally returns without showing a
     * list, and offers to add the account if it isn't there yet.
     */
    private fun confirmOnTv(google: GoogleDeviceAuth.GoogleProfile) {
        // Google TV's account picker opens a screen inside Google's own launcher. With that launcher turned off
        // (which Portal's setup does so the Home button opens Portal) the picker fails and restarts over and over,
        // locking up the screen. So skip the check there; apps still use whatever accounts the TV has.
        if (tvAccountPickerUnavailable()) {
            toast("Portal didn't check that ${google.email} is on this TV, as the TV's account picker is switched off")
            addGoogleProfile(google)
            return
        }
        // Portal can't tell whether the account is already on the TV, and the system
        // screen that follows can look like an error, so say what is about to happen.
        PortalDialog.Builder(requireContext())
            .setTitle("Almost done")
            .setMessage(
                "Portal will check that ${google.email} is on this TV, because apps like " +
                    "YouTube and Play Store use the TV's Google accounts.\n\n" +
                    "If it isn't there yet, you'll be asked to sign in once more with " +
                    "this same account."
            )
            .setPositiveButton("Continue") { _, _ -> launchAccountChooser(google) }
            .setNegativeButton("Cancel") { _, _ ->
                toast("${google.email} was not added")
            }
            .setCancelable(false)
            .show()
    }

    /** True when Google's own launcher is installed but turned off, which breaks the TV's account picker. */
    private fun tvAccountPickerUnavailable(): Boolean = try {
        val setting = requireContext().packageManager.getApplicationEnabledSetting("com.google.android.apps.tv.launcherx")
        setting == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            setting == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            setting == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    } catch (e: IllegalArgumentException) {
        false   // not a Google TV, so the picker doesn't depend on it
    }

    private fun launchAccountChooser(google: GoogleDeviceAuth.GoogleProfile) {
        pendingGoogle = google
        val account = Account(google.email, "com.google")
        accountChooser.launch(
            AccountManager.newChooseAccountIntent(
                account, listOf(account), arrayOf("com.google"), null, null, null, null
            )
        )
    }

    /** Public builds carry no Google credentials, so the user enters their own OAuth client once. */
    private fun askGoogleCredentials() {
        val context = requireContext()
        fun field(hint: String) = EditText(context).apply {
            this.hint = hint
            setSingleLine(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val id = field("Client ID")
        val secret = field("Client secret")
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(id)
            addView(secret)
        }
        PortalDialog.Builder(context)
            .setTitle("Link a Google account (optional)")
            .setMessage(
                "A profile linked to a Google account uses your Google name and photo. Most people can choose " +
                    "Skip and make a Portal profile instead.\n\n" +
                    "To set this up, create an OAuth client of type \"TVs and Limited Input devices\" in Google " +
                    "Cloud (see the README) and enter it here. It stays on this TV, encrypted."
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (id.text.isBlank() || secret.text.isBlank()) {
                    toast("Both are needed")
                } else {
                    GoogleDeviceAuth.save(context, id.text.toString(), secret.text.toString())
                    startGoogleSignIn()
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    /** Step 1: confirm with Google (code on your phone), which gives the name, email and photo. */
    private fun startGoogleSignIn() {
        GoogleDeviceAuth.load(requireContext())
        if (!GoogleDeviceAuth.isConfigured) {
            askGoogleCredentials()
            return
        }

        // QR code on the left, instructions and the code on the right.
        val qr = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { marginEnd = dp(24) }
            visibility = View.GONE
        }
        val instructions = TextView(requireContext()).apply {
            text = "Contacting Google…"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(qr)
            addView(instructions)
        }
        val dialog = PortalDialog.Builder(requireContext())
            .setTitle("Add Google Account")
            .setWidthDp(720)
            .setView(content)
            .setNegativeButton("Cancel") { _, _ -> signInJob?.cancel() }
            .setOnCancelListener { signInJob?.cancel() }
            .show()

        signInJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val challenge = GoogleDeviceAuth.start()
                qr.setImageBitmap(QrCode.bitmap(challenge.verificationUrl, 440))
                qr.visibility = View.VISIBLE
                instructions.text = "Scan the code with your phone, or go to\n" +
                    "${challenge.verificationUrl}\n\nand enter this code:\n\n${challenge.userCode}"
                val google = GoogleDeviceAuth.awaitProfile(challenge)
                dialog.dismiss()
                confirmOnTv(google)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                toast(e.message ?: "Sign-in failed")
            }
        }
    }

    private fun addGoogleProfile(google: GoogleDeviceAuth.GoogleProfile) {
        val context = requireContext()
        if (ProfileManager.findByEmail(context, google.email) != null) {
            toast("${google.email} is already a profile")
            return
        }
        PortalDialog.Builder(context)
            .setTitle("Is this a kid profile?")
            .setMessage("${google.name.ifEmpty { google.email }} will only see apps you allow.")
            .setPositiveButton("Yes") { _, _ -> finishGoogleProfile(google, isKids = true) }
            .setNegativeButton("No") { _, _ -> finishGoogleProfile(google, isKids = false) }
            .setOnCancelListener { finishGoogleProfile(google, isKids = false) }
            .show()
    }

    private fun finishGoogleProfile(google: GoogleDeviceAuth.GoogleProfile, isKids: Boolean) {
        withPinReady(isKids) {
            val profile = ProfileManager.addGoogleProfile(
                requireContext(), google.name, google.email, google.photo, isKids
            )
            render()
            if (isKids) showManageApps(profile) { onProfileReady(profile) }
            else onProfileReady(profile)
        }
    }

    // ---------- Rename / delete ----------

    private fun showProfileOptions(profile: Profile) {
        // Google profiles take their name from the account, so they can't be renamed.
        val options = buildList {
            if (!profile.isGoogle) add("Rename" to { showRenameDialog(profile) })
            if (!profile.isGoogle) add("Change avatar" to { showAvatarPicker(profile) })
            if (profile.isKids) add("Manage apps" to { showManageApps(profile) })
            val hasCode = ProfileCode.isSet(requireContext(), profile.id)
            if (hasCode) {
                add("Change code" to { PinDialogs.changeProfileCode(requireContext(), profile) })
                add("Remove code" to { PinDialogs.removeProfileCode(requireContext(), profile) })
            } else {
                add("Set profile code" to { PinDialogs.createProfileCode(requireContext(), profile) })
            }
            add(
                (if (profile.isKids) "Make adult profile" else "Make kid profile") to
                    { toggleKids(profile) }
            )
            add("Delete" to { confirmDelete(profile) })
        }
        PortalDialog.Builder(requireContext())
            .setTitle(profile.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleKids(profile: Profile) {
        val makeKids = !profile.isKids
        withPinReady(makeKids) {
            // The avatar sets differ for adults and kids, so pick a fresh one.
            val updated = ProfileManager.modify(requireContext(), profile.id) {
                it.copy(isKids = makeKids, avatarId = null)
            } ?: return@withPinReady
            render()
            if (updated.isGoogle) {
                if (makeKids) showManageApps(updated)
            } else {
                showAvatarPicker(updated) { if (makeKids) showManageApps(updated) }
            }
        }
    }

    /** Lets a parent choose which apps a kid profile can see. */
    private fun showManageApps(profile: Profile, then: () -> Unit = {}) {
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = AppRepository(requireContext()).getLaunchableApps()
            val builder = PortalDialog.Builder(requireContext())
            val iconSize = dp(40)
            val list = ListView(builder.context).apply {
                choiceMode = ListView.CHOICE_MODE_MULTIPLE
                adapter = object : ArrayAdapter<AppInfo>(
                    builder.context, android.R.layout.simple_list_item_multiple_choice, apps
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row = super.getView(position, convertView, parent) as CheckedTextView
                        val app = getItem(position)!!
                        // An extension's screen is a screen of someone else's app: like any app a kid is allowed, it can open others.
                        row.text = if (com.portal.tv.extensions.ExtensionTileKey.isTile(app.packageName)) "${app.label}  ·  extension (can open other apps)" else app.label
                        // Each row gets its own copy so the shared icon keeps its bounds.
                        val icon = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
                        icon.setBounds(0, 0, iconSize, iconSize)
                        row.setCompoundDrawablesRelative(icon, null, null, null)
                        row.compoundDrawablePadding = dp(16)
                        row.minimumHeight = iconSize + dp(16)
                        return row
                    }
                }
                apps.forEachIndexed { i, app ->
                    setItemChecked(i, app.packageName in profile.allowedApps)
                }
            }
            builder
                .setTitle("Apps for ${profile.name}")
                .setView(boxed(list, apps.size))
                .setPositiveButton("Save") { _, _ ->
                    val allowed = apps.filterIndexed { i, _ -> list.isItemChecked(i) }
                        .map { it.packageName }.toSet()
                    ProfileManager.modify(requireContext(), profile.id) { it.copy(allowedApps = allowed) }
                    then()
                }
                .setNegativeButton("Cancel") { _, _ -> then() }
                .setOnCancelListener { then() }
                .show()
        }
    }

    /**
     * The dialog holds its view in a scroll view, which measures it without a height limit and so shrinks a list to about
     * one row. Putting the list in a box of set height shows its apps at once: as tall as its [rows] need, up to half the
     * screen (as much as the dialog allows), after which the list scrolls inside.
     */
    private fun boxed(list: ListView, rows: Int) = FrameLayout(list.context).apply {
        val wanted = rows * (dp(40) + dp(16) + 2)
        val height = wanted.coerceIn(dp(56), (resources.displayMetrics.heightPixels * 0.5).toInt())
        addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private fun showRenameDialog(profile: Profile) {
        val (box, input) = nameInput(profile.name)
        PortalDialog.Builder(requireContext())
            .setTitle("Rename Profile")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                ProfileManager.modify(requireContext(), profile.id) { it.copy(name = name) }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        PortalDialog.Builder(requireContext())
            .setTitle("Delete ${profile.name}?")
            .setDestructive()
            .setMessage("This profile's usage history and settings will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                ProfileManager.deleteProfile(requireContext(), profile.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Avatars ----------

    /** Grid of built-in avatars for this profile's set (kids or adult). */
    private fun showAvatarPicker(profile: Profile, then: () -> Unit = {}) {
        val context = requireContext()
        lateinit var dialog: PortalDialog
        val grid = GridLayout(context).apply {
            columnCount = 4
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AvatarArt.options(profile.isKids).forEach { avatar ->
            grid.addView(ImageView(context).apply {
                setImageDrawable(AvatarArt.drawable(avatar.id))
                contentDescription = avatar.label
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_app_tile)
                val pad = dp(8)
                setPadding(pad, pad, pad, pad)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(96); height = dp(96)
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.12f else 1f)
                        .scaleY(if (hasFocus) 1.12f else 1f).setDuration(120).start()
                }
                setOnClickListener {
                    ProfileManager.modify(context, profile.id) { it.copy(avatarId = avatar.id) }
                    dialog.dismiss()
                    render()
                    then()
                }
            })
        }
        dialog = PortalDialog.Builder(context)
            .setTitle(if (profile.isKids) "Choose a kids avatar" else "Choose an avatar")
            .setView(ScrollView(context).apply { addView(grid) })
            .setNegativeButton("Skip") { _, _ -> then() }
            .setOnCancelListener { then() }
            .show()
    }

    // ---------- Parent PIN ----------

    /** Runs [action] now, or after the parent PIN if a kid profile is active. */
    private fun requireParent(action: () -> Unit) {
        if (!ProfileManager.isActiveKids(requireContext()) || !ParentPin.isSet(requireContext())) {
            action()
        } else {
            PinDialogs.verify(requireContext(), onVerified = action)
        }
    }

    /** Runs [action], first making sure a parent PIN exists when [needsPin] is true. */
    private fun withPinReady(needsPin: Boolean, action: () -> Unit) {
        if (!needsPin || ParentPin.isSet(requireContext())) action()
        else PinDialogs.create(requireContext(), onSet = action)
    }

    private fun kidsCheckBox() = CheckBox(requireContext()).apply {
        text = "Kid profile (only allowed apps)"
        setPadding(dp(8), dp(12), dp(8), dp(8))
    }

    private fun nameInput(initial: String = ""): Pair<LinearLayout, EditText> {
        val input = EditText(requireContext()).apply {
            hint = "Profile name"
            setText(initial)
            setSingleLine(true)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(input)
        }
        return box to input
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun dp(d: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, d.toFloat(), resources.displayMetrics
    ).toInt()
}
