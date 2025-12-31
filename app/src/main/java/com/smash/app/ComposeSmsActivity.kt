package com.smash.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Required for default SMS app status.
 * Handles SMS compose intents (minimal implementation).
 */
class ComposeSmsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Minimal implementation - just finish
        // smash is not meant to be used as a regular SMS app
        finish()
    }
}
