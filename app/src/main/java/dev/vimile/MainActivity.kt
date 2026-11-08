package dev.vimile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal launcher screen: explains the app and jumps to Accessibility settings. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }

        layout.addView(TextView(this).apply {
            text = "Vimile needs the Accessibility permission to read the screen " +
                "and handle keyboard shortcuts.\n\nOpen Settings → Accessibility → " +
                "Vimile and turn it on."
            textSize = 16f
        })

        layout.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        setContentView(layout)
    }
}
