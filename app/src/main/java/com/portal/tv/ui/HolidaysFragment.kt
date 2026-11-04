package com.portal.tv.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.portal.tv.R
import com.portal.tv.background.HolidayStore
import com.portal.tv.background.Holidays
import com.portal.tv.background.Holidays.Holiday
import com.portal.tv.background.Holidays.Rule
import com.portal.tv.background.VideoThumbnails
import com.portal.tv.repo.ProfileManager
import com.portal.tv.settings.BackgroundSettings
import com.portal.tv.video.DownloadedVideos
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The holidays whose videos play on the home screen. One tile per holiday, with its next date and how many videos it
 * has; OK on a tile opens that holiday (its videos, its date, removing it), and the last tile adds one. A switch turns
 * holiday videos on or off for the profile without losing the list.
 */
class HolidaysFragment : Fragment() {

    private lateinit var content: LinearLayout
    private lateinit var holidays: List<Holiday>
    private val soft = 0xFFB0B0C0.toInt()
    private val accent = 0xFF8A83FF.toInt()

    private val d get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply { setBackgroundColor(resources.getColor(R.color.bg_primary, null)) }
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(96), px(56), px(96), px(40))
        }
        scroll.addView(content)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
        render()
    }

    private fun reload() { holidays = HolidayStore.load(requireContext()) }

    /** Saves [list] as the profile's holidays and redraws the page. */
    private fun commit(list: List<Holiday>) {
        HolidayStore.save(requireContext(), list)
        reload()
        render()
    }

    private fun text(s: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = resources.getFont(R.font.inter)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun roundOutline(r: Int) = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, px(r).toFloat()) }
    }

    private fun chip(s: String, bg: Int) = text(s, 12f, Color.WHITE, true).apply {
        setPadding(px(8), px(3), px(8), px(3))
        background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setColor(bg) }
    }

    private fun thumbInto(iv: ImageView, path: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            VideoThumbnails.load(requireContext(), path)?.let { iv.setImageBitmap(it) }
        }
    }

    private fun dashedBox(widthDp: Int, heightDp: Int, words: String, size: Float): FrameLayout {
        val box = FrameLayout(requireContext()).apply {
            background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(0x22FFFFFF)
                setStroke(px(2), 0x886C63FF.toInt(), px(8).toFloat(), px(6).toFloat())
            }
        }
        box.addView(text(words, size, accent, true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return FrameLayout(requireContext()).apply {
            setPadding(px(3), px(3), px(3), px(3))
            addView(box, FrameLayout.LayoutParams(px(widthDp), px(heightDp)))
        }
    }

    // ---------- The page ----------

    private fun render() {
        val context = requireContext()
        val today = LocalDate.now()
        val on = BackgroundSettings.getHolidayVideos(context)
        content.removeAllViews()
        content.addView(text("Holidays", 40f, bold = true))
        val profile = ProfileManager.getActive(context)
        content.addView(text(if (profile != null) "Saved for ${profile.name}'s profile" else "", 18f, soft).apply { setPadding(0, px(6), 0, px(18)) })

        val onOff = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, value) in listOf("On" to true, "Off" to false)) {
            onOff.addView(text(label, 20f, if (on == value) Color.WHITE else soft).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                setBackgroundResource(if (on == value) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                setOnClickListener { BackgroundSettings.setHolidayVideos(context, value); render() }
            }, LinearLayout.LayoutParams(px(72), px(48)).apply { marginEnd = px(8) })
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Play a holiday's videos on its days", 16f, soft), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(onOff)
        content.addView(head)

        val soon = holidays.minByOrNull { Holidays.nextDate(it.rule, today) }
        content.addView(text(
            when {
                !on -> "Holiday videos are off. Your holidays are kept for when you turn them back on."
                soon == null -> "No holidays yet"
                else -> {
                    val date = Holidays.nextDate(soon.rule, today)
                    "Next: ${soon.name}, ${Holidays.shortDate(date)} (in ${ChronoUnit.DAYS.between(today, date)} days)"
                }
            }, 16f, soft
        ).apply { setPadding(0, px(14), 0, px(10)) })

        val grid = GridLayout(context).apply { columnCount = 4 }
        holidays.sortedBy { Holidays.nextDate(it.rule, today) }.forEach { grid.addView(holidayTile(it, it === soon && on, today)) }
        grid.addView(addTile())
        content.addView(grid)
        // Off dims the holidays only; the switch stays as it is so it can be turned back on.
        grid.alpha = if (on) 1f else 0.45f
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    private fun holidayTile(h: Holiday, isNext: Boolean, today: LocalDate): View {
        val context = requireContext()
        val card = FrameLayout(context).apply { setPadding(px(3), px(3), px(3), px(3)) }
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1A1A2E.toInt())
            clipToOutline = true
            outlineProvider = roundOutline(10)
        }
        card.addView(thumb, FrameLayout.LayoutParams(px(156), px(88)))
        h.videos.firstOrNull()?.let { thumbInto(thumb, it) }
        card.addView(View(context).apply {
            setBackgroundColor(0x55000000)
            clipToOutline = true
            outlineProvider = roundOutline(10)
        }, FrameLayout.LayoutParams(px(156), px(88)))
        card.addView(text(Holidays.shortDate(Holidays.nextDate(h.rule, today)), 22f, bold = true),
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(px(12), 0, 0, px(8)) })
        val count = if (h.videos.isEmpty()) chip("Add a video", 0xFFD9822B.toInt())
        else chip(if (h.videos.size == 1) "1 video" else "${h.videos.size} videos", 0xAA000000.toInt())
        card.addView(count, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, px(8), px(8), 0) })
        if (isNext) card.addView(chip("NEXT", 0xFF6C63FF.toInt()),
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(px(8), px(8), 0, 0) })
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(8), px(8), px(8), px(6))
            setBackgroundResource(R.drawable.bg_settings_tile)
            isFocusable = true
            contentDescription = "${h.name}, ${Holidays.shortDate(Holidays.nextDate(h.rule, today))}, " +
                if (h.videos.isEmpty()) "no videos" else "${h.videos.size} videos"
            layoutParams = GridLayout.LayoutParams().apply { setMargins(0, 0, px(8), px(8)) }
            addView(card)
            addView(text(h.name, 15f, soft).apply { gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0); maxLines = 1 },
                LinearLayout.LayoutParams(px(162), ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { showDetail(h.id) }
        }
    }

    private fun addTile(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(8), px(8), px(8), px(6))
        setBackgroundResource(R.drawable.bg_settings_tile)
        isFocusable = true
        contentDescription = "Add holiday"
        layoutParams = GridLayout.LayoutParams().apply { setMargins(0, 0, px(8), px(8)) }
        addView(dashedBox(156, 88, "+", 40f))
        addView(text("Add holiday", 15f, soft).apply { gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0) })
        setOnClickListener { showAdd() }
    }

    // ---------- One holiday ----------

    private fun showDetail(id: String) {
        val context = requireContext()
        val h = holidays.firstOrNull { it.id == id } ?: return
        val today = LocalDate.now()
        lateinit var dialog: PortalDialog
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, px(4)) }
        val dateLine = if (h.rule is Rule.Fixed) Holidays.ruleText(h.rule)
        else "${Holidays.ruleText(h.rule)} · ${Holidays.shortDate(Holidays.nextDate(h.rule, today))} this year"
        body.addView(text(dateLine, 16f, soft))
        body.addView(text(Holidays.playsText(h, today), 16f, soft).apply { setPadding(0, px(2), 0, px(14)) })
        body.addView(text(if (h.videos.size > 1) "Videos (a different one each day)" else "Videos", 16f, Color.WHITE, true)
            .apply { setPadding(0, 0, 0, px(8)) })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        h.videos.forEach { path ->
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1A1A2E.toInt())
                clipToOutline = true
                outlineProvider = roundOutline(10)
            }
            thumbInto(thumb, path)
            val card = FrameLayout(context).apply { setPadding(px(3), px(3), px(3), px(3)) }
            card.addView(thumb, FrameLayout.LayoutParams(px(150), px(84)))
            card.addView(chip("✕ Remove", 0xAA000000.toInt()),
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, px(6), px(6), 0) })
            val name = HolidayStore.labelOf(context, path)
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(6), px(6), px(6), px(4))
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                contentDescription = "$name, remove from ${h.name}"
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) }
                addView(card)
                addView(text(name, 13f, soft).apply { setPadding(0, px(2), 0, 0); maxLines = 1 },
                    LinearLayout.LayoutParams(px(156), ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener {
                    dialog.dismiss()
                    removeVideo(h, path)
                    showDetail(id)
                }
            })
        }
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(6), px(6), px(6), px(4))
            setBackgroundResource(R.drawable.bg_settings_tile)
            isFocusable = true
            contentDescription = "Add a video to ${h.name}"
            addView(dashedBox(150, 84, "+ Add video", 16f))
            addView(text(" ", 13f))
            setOnClickListener { dialog.dismiss(); showVideoPicker(id) }
        })
        body.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        if (h.videos.isEmpty()) body.addView(text("Nothing plays on this holiday until you add a video.", 14f, 0xFFD9822B.toInt())
            .apply { setPadding(0, px(10), 0, 0) })

        dialog = PortalDialog.Builder(context)
            .setTitle(h.name)
            .setView(body)
            .setWidthDp(760)
            .setPositiveButton("Done", null)
            .setNegativeButton("Change date…") { _, _ -> showForm(h) }
            .setNeutralButton("Remove holiday") { _, _ -> confirmRemove(h) }
            .create()
        dialog.show()
    }

    private fun removeVideo(h: Holiday, path: String) {
        val context = requireContext()
        // A download made for this holiday would otherwise come straight back, so it goes back to the ordinary videos.
        DownloadedVideos.find(context, path)?.takeIf { DownloadedVideos.holidayOf(it) == h.id }
            ?.let { DownloadedVideos.setUse(context, path, DownloadedVideos.USE_BACKGROUND) }
        commit(holidays.map { if (it.id == h.id) it.copy(videos = it.videos - path) else it })
    }

    private fun confirmRemove(h: Holiday) {
        val context = requireContext()
        PortalDialog.Builder(context)
            .setDestructive()
            .setTitle("Remove ${h.name}?")
            .setMessage("Its videos stay on the TV. You can add ${h.name} back later.")
            .setPositiveButton("Remove") { _, _ ->
                DownloadedVideos.all(context).filter { DownloadedVideos.holidayOf(it) == h.id }
                    .forEach { DownloadedVideos.setUse(context, it.file, DownloadedVideos.USE_BACKGROUND) }
                commit(holidays.filter { it.id != h.id })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Choosing a holiday's videos ----------

    private fun showVideoPicker(id: String) {
        val context = requireContext()
        val h = holidays.firstOrNull { it.id == id } ?: return
        val chosen = h.videos.toMutableList()
        val grid = GridLayout(context).apply { columnCount = 4 }
        HolidayStore.allVideos(context).forEach { video ->
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1A1A2E.toInt())
                clipToOutline = true
                outlineProvider = roundOutline(10)
            }
            thumbInto(thumb, video.path)
            val card = FrameLayout(context).apply {
                setPadding(px(3), px(3), px(3), px(3))
                isFocusable = true
                setBackgroundResource(R.drawable.bg_video_card)
                contentDescription = video.label
            }
            card.addView(thumb, FrameLayout.LayoutParams(px(170), px(96)))
            card.addView(text(video.label, 13f).apply { setPadding(px(8), 0, 0, px(6)); maxLines = 1 },
                FrameLayout.LayoutParams(px(160), -2, Gravity.BOTTOM or Gravity.START))
            val mark = ImageView(context).apply {
                setImageResource(R.drawable.ic_dialog_check)
                setBackgroundResource(R.drawable.bg_video_badge)
                setPadding(px(5), px(5), px(5), px(5))
                visibility = if (video.path in chosen) View.VISIBLE else View.GONE
            }
            card.addView(mark, FrameLayout.LayoutParams(px(26), px(26), Gravity.TOP or Gravity.END).apply { setMargins(0, px(8), px(8), 0) })
            card.setOnClickListener {
                if (video.path in chosen) chosen.remove(video.path) else chosen.add(video.path)
                mark.visibility = if (video.path in chosen) View.VISIBLE else View.GONE
            }
            grid.addView(card, GridLayout.LayoutParams().apply { setMargins(px(3), px(3), px(3), px(3)) })
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text("Choose as many as you like. A different one plays each day.", 15f, soft).apply { setPadding(0, 0, 0, px(10)) })
        // The dialog measures its view without a height limit, so the grid gets a box of its own and scrolls inside it.
        val rows = (HolidayStore.allVideos(context).size + 3) / 4
        val height = (rows * px(106) + px(8)).coerceAtMost((resources.displayMetrics.heightPixels * 0.5).toInt())
        body.addView(ScrollView(context).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        body.addView(text("Want more? Search/Manage Videos can download videos for this holiday.", 14f, soft).apply { setPadding(0, px(10), 0, 0) })

        fun saveChoice() {
            val before = h.videos.toSet()
            // A download made for this holiday that was taken off goes back to the ordinary videos.
            (before - chosen.toSet()).forEach { path ->
                DownloadedVideos.find(context, path)?.takeIf { DownloadedVideos.holidayOf(it) == h.id }
                    ?.let { DownloadedVideos.setUse(context, path, DownloadedVideos.USE_BACKGROUND) }
            }
            commit(holidays.map { if (it.id == h.id) it.copy(videos = chosen.toList()) else it })
        }
        PortalDialog.Builder(context)
            .setTitle("Videos for ${h.name}")
            .setView(body)
            .setWidthDp(860)
            .setBodyMaxHeightFraction(0.8f)
            .setPositiveButton("Done") { _, _ -> saveChoice(); showDetail(id) }
            .setNeutralButton("Search videos…") { _, _ ->
                saveChoice()
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, VideoBrowserFragment())
                    .addToBackStack("videos")
                    .commit()
            }
            .setNegativeButton("Cancel") { _, _ -> showDetail(id) }
            .setOnCancelListener { showDetail(id) }
            .show()
    }

    // ---------- Adding a holiday ----------

    private fun showAdd() {
        val today = LocalDate.now()
        val missing = HolidayStore.removedBuiltIns(holidays)
        val offered = (missing + Holidays.suggestions.filter { s -> holidays.none { it.id == s.id } })
            .sortedBy { Holidays.nextDate(it.rule, today) }
        val items = mutableListOf("Make your own holiday…")
        val actions = mutableListOf<() -> Unit>({ showForm(null) })
        offered.forEach { s ->
            items += "${s.name}  ·  ${Holidays.shortDate(Holidays.nextDate(s.rule, today))}" + if (s in missing) "  (put back)" else ""
            actions += {
                // A holiday put back brings the videos that come with Portal for it.
                commit(holidays + s.copy(videos = HolidayStore.bundledVideos(requireContext(), s.id)))
                showDetail(s.id)
            }
        }
        PortalDialog.Builder(requireContext())
            .setTitle("Add a holiday")
            .setMessage("Pick one to start from, or make your own.")
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The form for a name and a date. [existing] is set when changing a holiday's date. */
    private fun showForm(existing: Holiday?) {
        val context = requireContext()
        var weekday = existing?.rule is Rule.Nth
        var name = existing?.name ?: ""
        val fixed = existing?.rule as? Rule.Fixed
        val nth = existing?.rule as? Rule.Nth
        var month = fixed?.month ?: nth?.month ?: LocalDate.now().monthValue
        var day = fixed?.day ?: 1
        var n = nth?.n ?: 1
        var dow = nth?.weekday ?: 1
        var before = existing?.daysBefore ?: 1
        val easter = existing?.rule == Rule.Easter
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            body.removeAllViews()
            if (existing == null) {
                body.addView(EditText(context).apply {
                    hint = "Holiday name"
                    setText(name)
                    setSingleLine(true)
                    setPadding(px(16), px(14), px(16), px(14))
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) { name = s.toString() }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                })
            }
            if (easter) {
                body.addView(text("Easter Sunday, worked out each year.", 17f, soft).apply { setPadding(0, px(14), 0, px(10)) })
            } else {
                val seg = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(14), 0, px(10)) }
                for ((label, w) in listOf("Same date each year" to false, "A weekday of a month" to true)) {
                    seg.addView(text(label, 17f, if (weekday == w) Color.WHITE else soft).apply {
                        gravity = Gravity.CENTER
                        isFocusable = true
                        setBackgroundResource(if (weekday == w) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                        setOnClickListener { weekday = w; fill() }
                    }, LinearLayout.LayoutParams(px(230), px(46)).apply { marginEnd = px(8) })
                }
                body.addView(seg)

                val months = (1..12).map { Month.of(it).getDisplayName(TextStyle.FULL, Locale.US) }
                val fields = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, px(8)) }
                if (!weekday) {
                    fields.addView(stepper("Month", months, month - 1, 170) { month = it + 1; day = day.coerceAtMost(LocalDate.of(2025, month, 1).lengthOfMonth()) })
                    fields.addView(stepper("Day", (1..31).map { it.toString() }, day - 1, 90) { day = it + 1 })
                } else {
                    fields.addView(stepper("Which", listOf("1st", "2nd", "3rd", "4th", "Last"), n - 1, 90) { n = it + 1 })
                    fields.addView(stepper("Weekday", DayOfWeek.values().map { it.getDisplayName(TextStyle.FULL, Locale.US) }, dow - 1, 150) { dow = it + 1 })
                    fields.addView(stepper("Of", months, month - 1, 170) { month = it + 1 })
                }
                body.addView(fields)
                body.addView(text("Up and Down change a value.", 13f, 0xFF8888A0.toInt()))
            }
            val start = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(12), 0, 0); gravity = Gravity.CENTER_VERTICAL }
            start.addView(text("Starts", 17f, soft).apply { setPadding(0, 0, px(14), 0) })
            val labels = Holidays.startChoices.map { c ->
                when (c) { 0 -> "On the day"; 1 -> "1 day before"; 7 -> "A week before"; else -> "$c days before" }
            }
            start.addView(stepper(null, labels, Holidays.startChoices.indexOf(before).coerceAtLeast(0), 200) { before = Holidays.startChoices[it] })
            body.addView(start)
        }
        fill()

        PortalDialog.Builder(context)
            .setTitle(if (existing == null) "Make your own holiday" else "${existing.name}: change the date")
            .setView(body)
            .setWidthDp(760)
            .setBodyMaxHeightFraction(0.85f)
            .setPositiveButton("Save") { _, _ ->
                val rule = when {
                    easter -> Rule.Easter
                    weekday -> Rule.Nth(n, dow, month)
                    else -> Rule.Fixed(month, day)
                }
                if (existing != null) {
                    commit(holidays.map { if (it.id == existing.id) it.copy(rule = rule, daysBefore = before) else it })
                    showDetail(existing.id)
                } else {
                    val made = Holiday("c${System.currentTimeMillis()}", name.trim().ifEmpty { "My holiday" }, rule, before)
                    commit(holidays + made)
                    showDetail(made.id)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> existing?.let { showDetail(it.id) } }
            .show()
    }

    /** A value changed with Up and Down on the remote (wrapping): an up arrow above it and a down arrow below, so they line up. */
    private fun stepper(title: String?, values: List<String>, start: Int, widthDp: Int, onChange: (Int) -> Unit): View {
        val context = requireContext()
        var i = start.coerceIn(0, values.size - 1)
        val value = text(values[i], 20f).apply { gravity = Gravity.CENTER; maxLines = 1 }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            setBackgroundResource(R.drawable.bg_settings_tile)
            setPadding(0, px(6), 0, px(6))
            addView(text("▲", 14f, accent).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
            addView(value, LinearLayout.LayoutParams(-1, px(34)))
            addView(text("▼", 14f, accent).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
            fun step(by: Int) { i = (i + by + values.size) % values.size; value.text = values[i]; onChange(i) }
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { step(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { step(1); true }
                    else -> false
                }
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(10) }
            if (title != null) addView(text(title, 14f, soft).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(2)) },
                LinearLayout.LayoutParams(px(widthDp), -2))
            addView(box, LinearLayout.LayoutParams(px(widthDp), -2))
        }
    }
}
