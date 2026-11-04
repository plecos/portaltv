package com.portal.tv.background

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The holidays whose videos play on the home screen (Video background only): each has a name, a rule for its date,
 * how many days before it the videos start, and the videos that play (one a day, in turn). Free of Android types so it
 * can be unit tested; [HolidayStore] keeps them on the TV.
 */
object Holidays {

    /** How a holiday's date is worked out for a year. */
    sealed class Rule {
        /** The same date each year. A 29 February holiday falls on 28 February in other years. */
        data class Fixed(val month: Int, val day: Int) : Rule()

        /** A weekday of a month: [n] is 1 to 4 for the first to fourth, or 5 for the last. [weekday] is 1 (Monday) to 7 (Sunday). */
        data class Nth(val n: Int, val weekday: Int, val month: Int) : Rule()

        /** Easter Sunday, which moves from year to year. */
        object Easter : Rule()
    }

    /** [videos] are paths relative to the backgrounds folder, such as "special/halloween1.mp4" or "dl_nasa_123.mp4". */
    data class Holiday(
        val id: String,
        val name: String,
        val rule: Rule,
        val daysBefore: Int = 1,
        val videos: List<String> = emptyList()
    )

    /** The days before a holiday that it can start, as offered when making one. */
    val startChoices = listOf(0, 1, 2, 3, 7)

    /** The holidays Portal starts with. */
    val builtIns = listOf(
        Holiday("halloween", "Halloween", Rule.Fixed(10, 31)),
        Holiday("thanksgiving", "Thanksgiving", Rule.Nth(4, 4, 11)),
        Holiday("christmas", "Christmas", Rule.Fixed(12, 25)),
        Holiday("newyear", "New Year's", Rule.Fixed(1, 1))
    )

    /** Holidays offered when adding one, besides the built-in ones. */
    val suggestions = listOf(
        Holiday("valentines", "Valentine's Day", Rule.Fixed(2, 14)),
        Holiday("stpatricks", "St. Patrick's Day", Rule.Fixed(3, 17)),
        Holiday("easter", "Easter", Rule.Easter),
        Holiday("mothers_day", "Mother's Day", Rule.Nth(2, 7, 5)),
        Holiday("memorial_day", "Memorial Day", Rule.Nth(5, 1, 5)),
        Holiday("fathers_day", "Father's Day", Rule.Nth(3, 7, 6)),
        Holiday("independence_day", "Independence Day", Rule.Fixed(7, 4)),
        Holiday("labor_day", "Labor Day", Rule.Nth(1, 1, 9))
    )

    // ---------- Dates ----------

    fun dateIn(rule: Rule, year: Int): LocalDate = when (rule) {
        is Rule.Fixed -> {
            val first = LocalDate.of(year, rule.month, 1)
            first.withDayOfMonth(rule.day.coerceIn(1, first.lengthOfMonth()))
        }
        is Rule.Nth -> {
            val first = LocalDate.of(year, rule.month, 1)
            val day = DayOfWeek.of(rule.weekday)
            if (rule.n >= 5) first.with(TemporalAdjusters.lastInMonth(day))
            else first.with(TemporalAdjusters.dayOfWeekInMonth(rule.n, day))
        }
        Rule.Easter -> easter(year)
    }

    /** Easter Sunday by the Gregorian computus. */
    internal fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** The first date of the holiday that is [today] or later. */
    fun nextDate(rule: Rule, today: LocalDate): LocalDate =
        listOf(today.year, today.year + 1).map { dateIn(rule, it) }.first { !it.isBefore(today) }

    /** Whether [today] is one of the days the holiday's videos play: its date and the days before it. */
    fun isActive(holiday: Holiday, today: LocalDate): Boolean =
        // A window can cross New Year, so check last, this and next year's date.
        (today.year - 1..today.year + 1).any { year ->
            val date = dateIn(holiday.rule, year)
            !today.isBefore(date.minusDays(holiday.daysBefore.toLong())) && !today.isAfter(date)
        }

    /** The holiday to show on [today]: of those whose days include it and that have a video, the one that comes first. */
    fun active(holidays: List<Holiday>, today: LocalDate): Holiday? =
        holidays.filter { it.videos.isNotEmpty() && isActive(it, today) }
            .minByOrNull { nextDate(it.rule, today).toEpochDay() }

    /** One of the [videos], a different one each day; null when there are none. */
    fun pick(videos: List<String>, today: LocalDate): String? =
        if (videos.isEmpty()) null else videos[today.dayOfYear % videos.size]

    // ---------- Words ----------

    private fun monthName(month: Int) = Month.of(month).getDisplayName(TextStyle.FULL, Locale.US)
    private fun shortMonth(month: Int) = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.US)

    /** "Nov 26". */
    fun shortDate(date: LocalDate) = "${shortMonth(date.monthValue)} ${date.dayOfMonth}"

    private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; 5 -> "last"; else -> "${n}th" }

    /** How the date is worked out, in words: "Every Dec 25" or "The 4th Thursday of November". */
    fun ruleText(rule: Rule): String = when (rule) {
        is Rule.Fixed -> "Every ${shortMonth(rule.month)} ${rule.day}"
        is Rule.Nth -> "The ${ordinal(rule.n)} ${DayOfWeek.of(rule.weekday).getDisplayName(TextStyle.FULL, Locale.US)} of ${monthName(rule.month)}"
        Rule.Easter -> "Easter Sunday"
    }

    /** "Plays Nov 25 to Nov 26", or "Plays on Nov 26" when it starts on the day. */
    fun playsText(holiday: Holiday, today: LocalDate): String {
        val date = nextDate(holiday.rule, today)
        return if (holiday.daysBefore == 0) "Plays on ${shortDate(date)}"
        else "Plays ${shortDate(date.minusDays(holiday.daysBefore.toLong()))} to ${shortDate(date)}"
    }

    // ---------- Saving ----------

    fun toJson(holidays: List<Holiday>): String {
        val array = JSONArray()
        holidays.forEach { h ->
            val rule = JSONObject()
            when (val r = h.rule) {
                is Rule.Fixed -> rule.put("type", "fixed").put("month", r.month).put("day", r.day)
                is Rule.Nth -> rule.put("type", "nth").put("n", r.n).put("weekday", r.weekday).put("month", r.month)
                Rule.Easter -> rule.put("type", "easter")
            }
            array.put(
                JSONObject().put("id", h.id).put("name", h.name).put("rule", rule).put("before", h.daysBefore)
                    .put("videos", JSONArray(h.videos))
            )
        }
        return array.toString()
    }

    /** The holidays in [json], skipping any entry that can't be read; null if the text isn't a list at all. */
    fun fromJson(json: String): List<Holiday>? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val r = o.getJSONObject("rule")
                val rule = when (r.getString("type")) {
                    "fixed" -> Rule.Fixed(r.getInt("month").coerceIn(1, 12), r.getInt("day").coerceIn(1, 31))
                    "nth" -> Rule.Nth(r.getInt("n").coerceIn(1, 5), r.getInt("weekday").coerceIn(1, 7), r.getInt("month").coerceIn(1, 12))
                    "easter" -> Rule.Easter
                    else -> return@runCatching null
                }
                val videos = o.optJSONArray("videos")?.let { v -> (0 until v.length()).map { v.getString(it) } } ?: emptyList()
                Holiday(o.getString("id"), o.getString("name"), rule, o.optInt("before", 1).coerceIn(0, 14), videos.distinct())
            }.getOrNull()
        }
    }
}
