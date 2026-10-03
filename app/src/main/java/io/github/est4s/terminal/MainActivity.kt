package io.github.est4s.terminal

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Pocket Terminal\nv${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n\n> build pipeline works_"
            typeface = Typeface.MONOSPACE
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF2BD6"))
            setBackgroundColor(Color.parseColor("#0D0221"))
        })
    }
}

