package net.getportaltv.sample

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** A full-screen message, which is all these example screens are. */
abstract class MessageActivity(private val message: String) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = message
            textSize = 36f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(16, 16, 32))
            gravity = Gravity.CENTER
        })
    }
}

class LightsActivity : MessageActivity("Lights: a tile from the sample extension")
class CamerasActivity : MessageActivity("Cameras: a tile from the sample extension")
class SettingsActivity : MessageActivity("Settings of the sample extension")
