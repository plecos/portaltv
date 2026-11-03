package com.portal.tv.ui

import android.content.Context
import com.portal.tv.data.Profile
import com.portal.tv.repo.KidsSchedule
import com.portal.tv.repo.ScheduleLogic

/** The dialogs for setting a kid profile's schedule, and for a parent to unlock a locked profile. */
object KidsScheduleDialogs {

    private val LIMITS = listOf(0, 30, 60, 90, 120, 180)

    /** Evening times offered for bedtime, 6:00 PM to 11:30 PM. */
    private val BEDTIMES = (18 * 60..23 * 60 + 30 step 30).toList()

    /** Morning times offered for waking, 5:00 AM to 10:00 AM. */
    private val WAKE_TIMES = (5 * 60..10 * 60 step 30).toList()

    private const val DEFAULT_WAKE = 7 * 60

    /** The editor for [profile]'s schedule. The caller has already checked the parent PIN. */
    fun edit(context: Context, profile: Profile, onChanged: () -> Unit) {
        val c = KidsSchedule.config(context, profile.id)
        val items = mutableListOf<Pair<String, () -> Unit>>()

        items += "Daily limit: ${KidsSchedule.limitLabel(c.limitMinutes)}" to {
            pick(context, "Daily limit", LIMITS.map { KidsSchedule.limitLabel(it) }, LIMITS.indexOf(c.limitMinutes),
                onCancel = { edit(context, profile, onChanged) }) {
                KidsSchedule.setLimit(context, profile.id, LIMITS[it])
                changed(context, profile, onChanged)
            }
        }
        items += "Bedtime: ${if (c.hasBedtime) "starts ${KidsSchedule.timeLabel(c.bedStart)}" else "Off"}" to {
            val labels = listOf("Off") + BEDTIMES.map { KidsSchedule.timeLabel(it) }
            val current = if (c.hasBedtime) BEDTIMES.indexOf(c.bedStart) + 1 else 0
            pick(context, "Bedtime starts", labels, current,
                onCancel = { edit(context, profile, onChanged) }) { index ->
                if (index == 0) KidsSchedule.setBedtime(context, profile.id, ScheduleLogic.OFF, ScheduleLogic.OFF)
                else KidsSchedule.setBedtime(
                    context, profile.id, BEDTIMES[index - 1], if (c.hasBedtime) c.bedEnd else DEFAULT_WAKE
                )
                changed(context, profile, onChanged)
            }
        }
        if (c.hasBedtime) {
            items += "Wake-up time: ${KidsSchedule.timeLabel(c.bedEnd)}" to {
                pick(context, "Apps come back at", WAKE_TIMES.map { KidsSchedule.timeLabel(it) },
                    WAKE_TIMES.indexOf(c.bedEnd), onCancel = { edit(context, profile, onChanged) }) {
                    KidsSchedule.setBedtime(context, profile.id, c.bedStart, WAKE_TIMES[it])
                    changed(context, profile, onChanged)
                }
            }
        }
        if (!c.isEmpty) {
            items += "Remove all limits" to {
                KidsSchedule.setLimit(context, profile.id, 0)
                KidsSchedule.setBedtime(context, profile.id, ScheduleLogic.OFF, ScheduleLogic.OFF)
                changed(context, profile, onChanged)
            }
        }

        PortalDialog.Builder(context)
            .setTitle("${profile.name}'s schedule")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Done", null)
            .show()
    }

    /** After a change, tell the caller and come back to the editor so several things can be set. */
    private fun changed(context: Context, profile: Profile, onChanged: () -> Unit) {
        onChanged()
        edit(context, profile, onChanged)
    }

    private fun pick(
        context: Context, title: String, labels: List<String>, current: Int,
        onCancel: () -> Unit, onPick: (Int) -> Unit
    ) {
        PortalDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .show()
    }

    /**
     * A parent unlocks a locked kid profile with the PIN: skip tonight's bedtime, or add time to
     * today's limit.
     */
    fun parentUnlock(context: Context, profile: Profile, status: ScheduleLogic.Status, onChanged: () -> Unit) {
        PinDialogs.verify(context, "Parent PIN to unlock") {
            val options = mutableListOf<Pair<String, () -> Unit>>()
            when (status) {
                is ScheduleLogic.Status.Bedtime ->
                    options += "Skip bedtime tonight" to { KidsSchedule.skipBedtime(context, profile.id) }
                else -> listOf(15, 30, 60).forEach { minutes ->
                    options += "Add $minutes minutes today" to { KidsSchedule.addMinutes(context, profile.id, minutes) }
                }
            }
            PortalDialog.Builder(context)
                .setTitle("Unlock ${profile.name}")
                .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                    options[which].second()
                    onChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
