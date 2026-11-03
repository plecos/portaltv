package com.portal.tv.background

import android.content.Context
import com.portal.tv.video.DownloadedVideos
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Holiday videos in assets/backgrounds/special/, shown on the home screen (Video background
 * only) on the day before each holiday and the day itself. They are never offered in
 * Settings and never played by the screen saver.
 *
 * A video belongs to a holiday by its file name: halloween*.mp4, thanksgiving*.mp4,
 * christmas*.mp4 and new-years*.mp4 (case, dashes and underscores are ignored, so new_year.mp4
 * works too). With several files for one holiday, one is picked per day.
 */
object SeasonalVideos {

    private const val FOLDER_NAME = "special"
    private const val FOLDER = "backgrounds/$FOLDER_NAME"

    private class Holiday(
        val prefix: String,
        val daysBefore: Long,
        val dateIn: (year: Int) -> LocalDate
    )

    private val holidays = listOf(
        Holiday("halloween", 1) { LocalDate.of(it, 10, 31) },
        Holiday("thanksgiving", 1) { year ->
            // Fourth Thursday of November.
            LocalDate.of(year, 11, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        },
        Holiday("christmas", 1) { LocalDate.of(it, 12, 25) },
        Holiday("newyear", 1) { LocalDate.of(it, 1, 1) }
    )

    /**
     * The holiday video to show on [today], as a path relative to assets/backgrounds (for
     * example "special/halloween1.mp4"), or null when no holiday is due or its video is missing.
     */
    fun forDate(context: Context, today: LocalDate = LocalDate.now()): String? {
        val prefix = holidayFor(today) ?: return null
        val bundled = (context.assets.list(FOLDER) ?: emptyArray()).toList()
        val downloaded = DownloadedVideos.withUse(context, DownloadedVideos.USE_HOLIDAY + prefix).map { it.file }
        // A video the profile chose for this holiday wins over the daily rotation.
        val chosen = com.portal.tv.settings.BackgroundSettings.getHolidayVideo(context, prefix)
        if (chosen != null) {
            if (chosen in downloaded) return chosen
            if (chosen in bundled && holidayOfFile(chosen) == prefix) return "special/$chosen"
        }
        val pool = matching(bundled, prefix).map { "special/$it" } + downloaded
        return if (pool.isEmpty()) null else pool[today.dayOfYear % pool.size]
    }

    /** The holiday videos bundled with the app, then the ones downloaded for a holiday, as file names. */
    fun availableFiles(context: Context): List<String> =
        (context.assets.list(FOLDER) ?: emptyArray()).filter { it.endsWith(".mp4", ignoreCase = true) }
            .filter { holidayOfFile(it) != null }.sorted() +
            DownloadedVideos.all(context).filter { DownloadedVideos.holidayOf(it) != null }.map { it.file }

    /** The holiday a file from [availableFiles] belongs to, whether bundled or downloaded. */
    fun holidayOf(context: Context, file: String): String? =
        if (DownloadedVideos.isDownloaded(file)) DownloadedVideos.find(context, file)?.let { DownloadedVideos.holidayOf(it) }
        else holidayOfFile(file)

    /** Where a file from [availableFiles] is, relative to the backgrounds folder. */
    fun pathOf(file: String) = if (DownloadedVideos.isDownloaded(file)) file else "$FOLDER_NAME/$file"

    /** The name to show for a file from [availableFiles]. */
    fun labelOf(context: Context, file: String): String =
        if (DownloadedVideos.isDownloaded(file)) DownloadedVideos.find(context, file)?.title ?: file
        else BackgroundRepository.labelFor(file)

    private fun matching(files: List<String>, prefix: String) = files
        .filter { it.endsWith(".mp4", ignoreCase = true) }
        .filter { normalize(it).startsWith(prefix) }
        .sorted()

    /** The holiday a file belongs to (its prefix, such as "halloween"), judged from its name; null if none. */
    fun holidayOfFile(name: String): String? {
        val n = normalize(name)
        return holidays.firstOrNull { n.startsWith(it.prefix) }?.prefix
    }

    /** The holiday (as its file-name prefix) whose window contains [today], or null. */
    internal fun holidayFor(today: LocalDate): String? =
        // A window can cross New Year, so check last, this and next year's holidays.
        holidays.firstOrNull { h ->
            (today.year - 1..today.year + 1).any { year ->
                val date = h.dateIn(year)
                !today.isBefore(date.minusDays(h.daysBefore)) && !today.isAfter(date)
            }
        }?.prefix

    /** One of the [files] belonging to the holiday [prefix], varying by day; null when none. */
    internal fun pickFile(files: List<String>, prefix: String, today: LocalDate): String? {
        val matching = files
            .filter { it.endsWith(".mp4", ignoreCase = true) }
            .filter { normalize(it).startsWith(prefix) }
            .sorted()
        if (matching.isEmpty()) return null
        return matching[today.dayOfYear % matching.size]
    }

    private fun normalize(name: String) = name.lowercase().replace(Regex("[-_\\s]"), "")
}
