package com.fenglan.aurorabrowser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fenglan.aurorabrowser.browser.BrowserPreferences

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = BrowserPreferences(this).getSettings()
        AppCompatDelegate.setDefaultNightMode(
            if (settings.themeMode.isDark) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            },
        )
    }
}
