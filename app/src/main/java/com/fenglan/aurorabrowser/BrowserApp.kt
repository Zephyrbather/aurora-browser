package com.fenglan.aurorabrowser

import android.app.Application
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.BrowserThemeTools
import com.fenglan.aurorabrowser.browser.HlsDownloadWorker

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
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
