package com.fenglan.aurorabrowser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.BrowserThemeTools
import com.fenglan.aurorabrowser.browser.HlsDownloadWorker

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HlsDownloadWorker.createChannel(this)
        val settings = BrowserPreferences(this).getSettings()
        AppCompatDelegate.setDefaultNightMode(
            if (BrowserThemeTools.isEffectivelyDark(settings.themeMode)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            },
        )
    }
}
