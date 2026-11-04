package com.portal.tv.ui

import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.portal.tv.R

/**
 * Dresses the kid lock screen for why it is up: bedtime is a night sky with a sleepy moon, and a day's time being
 * used up is a bright sky with a friendly sun. Picks the scene, the text colours and the unlock button to match.
 */
object KidLockStyle {

    fun apply(lock: View, night: Boolean) {
        lock.findViewById<View>(R.id.kid_lock_scene).visibility = if (night) View.VISIBLE else View.GONE
        lock.findViewById<View>(R.id.kid_lock_scene_day).visibility = if (night) View.GONE else View.VISIBLE
        lock.setBackgroundColor(if (night) 0xFF0A0F2C.toInt() else 0xFF4DA9F2.toInt())

        // White on the night sky; a deep blue on the pale, bright one.
        val title = if (night) Color.WHITE else 0xFF1F2F66.toInt()
        val text = if (night) 0xFFD5DAF8.toInt() else 0xFF2E3F7A.toInt()
        val shadow = if (night) 0x99000000.toInt() else 0x80FFFFFF.toInt()
        lock.findViewById<TextView>(R.id.kid_lock_title).apply {
            setTextColor(title)
            setShadowLayer(10f, 0f, if (night) 3f else 1f, shadow)
        }
        lock.findViewById<TextView>(R.id.kid_lock_message).apply {
            setTextColor(text)
            setShadowLayer(8f, 0f, if (night) 2f else 1f, shadow)
        }
        lock.findViewById<TextView>(R.id.kid_lock_time).setTextColor(text)
        lock.findViewById<View>(R.id.kid_lock_parent)
            .setBackgroundResource(if (night) R.drawable.bg_unlock_button else R.drawable.bg_unlock_button_day)
    }
}
