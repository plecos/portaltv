package com.portal.tv.screensaver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * The in-app screen saver, shown over the home screen after the idle time chosen in Settings.
 * [com.portal.tv.PortalActivity] closes it on the first key press.
 */
class ScreensaverFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ScreensaverView(requireContext())
}
