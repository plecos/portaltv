package net.getportaltv.weather.page

import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import net.getportaltv.weather.R

/**
 * The weather page that comes with Portal. Portal opens it when the time and weather panel is pressed (the extension declares it
 * with `<weather-page>`). It shows the weather Portal holds, the alerts and the radar, all asked of Portal through [HostClient];
 * Back returns to Portal's home screen.
 */
class WeatherPageActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply {
            id = R.id.container
            setBackgroundColor(resources.getColor(R.color.page_background, theme))
        })
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, WeatherPageFragment()).commit()
        }
    }
}
