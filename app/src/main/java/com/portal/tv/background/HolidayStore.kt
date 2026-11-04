package com.portal.tv.background

import android.content.Context
import com.portal.tv.settings.BackgroundSettings
import com.portal.tv.video.DownloadedVideos
import java.time.LocalDate

/**
 * Keeps a profile's holidays and finds the video to play today. Until a profile first changes its holidays it has the
 * built-in four (Halloween, Thanksgiving, Christmas, New Year's), each with the bundled videos for it, the one that
 * profile had chosen, and any downloads made for it; from then on the saved list is used as it is.
 */
object HolidayStore {

    private const val FOLDER = "backgrounds/special"

    /** A video that can be given to a holiday: its path under the backgrounds folder, and its name. */
    data class VideoRef(val path: String, val label: String)

    private fun normalize(name: String) = name.lowercase().replace(Regex("[-_\\s]"), "")

    private fun specials(context: Context): List<String> =
        (context.assets.list(FOLDER) ?: emptyArray()).filter { it.endsWith(".mp4", ignoreCase = true) }.sorted()

    /** Every video on the TV that a holiday can use: the regular ones, the holiday ones that come with Portal, and downloads. */
    fun allVideos(context: Context): List<VideoRef> {
        val regular = BackgroundRepository.orderVideoNames((context.assets.list("backgrounds") ?: emptyArray()).toList())
            .map { VideoRef(it, BackgroundRepository.labelFor(it)) }
        val special = specials(context).map { VideoRef("special/$it", BackgroundRepository.labelFor(it)) }
        val downloaded = DownloadedVideos.all(context).map { VideoRef(it.file, it.title.ifEmpty { it.file }) }
        return (regular + special + downloaded).distinctBy { it.path }
    }

    fun labelOf(context: Context, path: String): String =
        allVideos(context).firstOrNull { it.path == path }?.label ?: BackgroundRepository.labelFor(path.substringAfterLast('/'))

    /** The profile's holidays, each with the videos that are still on the TV. */
    fun load(context: Context): List<Holidays.Holiday> {
        val valid = allVideos(context).map { it.path }.toSet()
        val tagged = DownloadedVideos.all(context).groupBy({ DownloadedVideos.holidayOf(it) }, { it.file })
        val saved = BackgroundSettings.getHolidaysJson(context)?.let { Holidays.fromJson(it) }
        val base = saved ?: defaults(context)
        return base.map { h -> h.copy(videos = (h.videos + tagged[h.id].orEmpty()).distinct().filter { it in valid }) }
    }

    fun save(context: Context, holidays: List<Holidays.Holiday>) {
        BackgroundSettings.setHolidaysJson(context, Holidays.toJson(holidays))
    }

    /** The built-in holidays that aren't in [holidays] now, to offer putting back. */
    fun removedBuiltIns(holidays: List<Holidays.Holiday>): List<Holidays.Holiday> =
        Holidays.builtIns.filter { b -> holidays.none { it.id == b.id } }

    /** The name of the holiday [id] for this profile, or null if it has none by that id. */
    fun nameOf(context: Context, id: String): String? =
        (load(context) + Holidays.builtIns + Holidays.suggestions).firstOrNull { it.id == id }?.name

    /** The videos that come with Portal for the holiday [id], found by their file names (halloween1.mp4 and so on). */
    fun bundledVideos(context: Context, id: String): List<String> =
        specials(context).filter { normalize(it).startsWith(id) }.map { "special/$it" }

    private fun defaults(context: Context): List<Holidays.Holiday> {
        return Holidays.builtIns.map { h ->
            val chosen = BackgroundSettings.getHolidayVideo(context, h.id)?.let { c ->
                if (DownloadedVideos.isDownloaded(c)) c else "special/$c"
            }
            val own = bundledVideos(context, h.id)
            h.copy(videos = if (chosen != null && (chosen in own || DownloadedVideos.isDownloaded(chosen))) listOf(chosen) else own)
        }
    }

    /** The holiday video for [today], as a path under the backgrounds folder, or null when no holiday is due. */
    fun forDate(context: Context, today: LocalDate = LocalDate.now()): String? {
        val holiday = Holidays.active(load(context), today) ?: return null
        return Holidays.pick(holiday.videos, today)
    }
}
