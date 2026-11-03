package com.portal.tv.ui

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.portal.tv.repo.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Search for a city or zip code, pick one, and make it the weather location. */
object LocationPicker {
    fun show(fragment: Fragment, onChosen: () -> Unit) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val input = EditText(context).apply {
            hint = "City or zip code"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        // Shown in place of nothing while a search runs, so the dialog doesn't look like it gave up.
        val progress = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, pad, 0, 0)
            addView(ProgressBar(context).apply { isIndeterminate = true },
                LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt()))
            addView(TextView(context).apply {
                text = "Searching…"
                textSize = 16f
                setPadding(pad, 0, 0, 0)
            })
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(input)
            addView(progress)
        }

        var job: Job? = null
        val dialog = PortalDialog.Builder(context)
            .setTitle("Weather location")
            .setView(box)
            .setPositiveButton("Search", null)   // set below, so a tap doesn't close the dialog
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()

        val search = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        fun busy(on: Boolean) {
            progress.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            input.isEnabled = !on
            search.isEnabled = !on
        }
        search.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.isEmpty()) return@setOnClickListener
            busy(true)
            job = fragment.viewLifecycleOwner.lifecycleScope.launch {
                val results = WeatherRepository.searchCities(query)
                busy(false)
                when {
                    results == null ->
                        Toast.makeText(context, "Couldn't search: check the connection", Toast.LENGTH_LONG).show()
                    results.isEmpty() ->
                        Toast.makeText(context, "No places match \"$query\"", Toast.LENGTH_LONG).show()
                    else -> {
                        dialog.dismiss()
                        PortalDialog.Builder(context)
                            .setTitle("Choose a place")
                            .setItems(results.map { it.label }.toTypedArray()) { _, which ->
                                WeatherRepository.setManualCity(context, results[which])
                                onChosen()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search.performClick(); true } else false
        }
    }
}
