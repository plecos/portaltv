package com.portal.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.portal.tv.R
import com.portal.tv.repo.WeatherRepository
import java.time.ZoneOffset

/**
 * The radar on its own, filling the screen, opened from the radar card on the weather page. It
 * starts at the zoom and place the card was showing. Back or the close button returns to the weather page.
 */
class RadarFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_radar, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val radar = view.findViewById<RadarView>(R.id.radar_full)
        val args = requireArguments()
        radar.show(
            args.getDouble(LAT),
            args.getDouble(LON),
            ZoneOffset.ofTotalSeconds(args.getInt(OFFSET)),
            args.getInt(ZOOM),
            args.getDouble(CLAT) to args.getDouble(CLON)
        )
        val play = view.findViewById<ImageView>(R.id.radar_play)
        radar.bindControls(view.findViewById(R.id.radar_zoom_in), view.findViewById(R.id.radar_zoom_out), play, view.findViewById(R.id.radar_layers),
            view.findViewById(R.id.radar_move), view.findViewById(R.id.radar_pin)
        ) { lat, lon -> WeatherRepository.setCustomSpot(requireContext(), lat, lon) }
        view.findViewById<View>(R.id.radar_close).setOnClickListener { parentFragmentManager.popBackStack() }
        play.requestFocus()
    }

    companion object {
        private const val LAT = "lat"
        private const val LON = "lon"
        private const val OFFSET = "offset"
        private const val ZOOM = "zoom"
        private const val CLAT = "clat"
        private const val CLON = "clon"

        fun of(latitude: Double, longitude: Double, utcOffsetSeconds: Int, zoom: Int, centerLat: Double, centerLon: Double) = RadarFragment().apply {
            arguments = Bundle().apply {
                putDouble(LAT, latitude)
                putDouble(LON, longitude)
                putInt(OFFSET, utcOffsetSeconds)
                putInt(ZOOM, zoom)
                putDouble(CLAT, centerLat)
                putDouble(CLON, centerLon)
            }
        }
    }
}
