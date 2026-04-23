package com.fenglan.aurorabrowser.browser

import android.graphics.Color
import com.fenglan.aurorabrowser.model.BrowserThemeMode

data class BrowserThemePalette(
    val surface: Int,
    val surfaceAlt: Int,
    val text: Int,
    val textMuted: Int,
    val line: Int,
    val accent: Int,
    val homeOverlay: Int,
)

object BrowserThemeTools {
    fun paletteFor(mode: BrowserThemeMode): BrowserThemePalette {
        return when (mode) {
            BrowserThemeMode.LIGHT -> BrowserThemePalette(
                surface = Color.parseColor("#F7F8FA"),
                surfaceAlt = Color.parseColor("#EEF2F6"),
                text = Color.parseColor("#111827"),
                textMuted = Color.parseColor("#6B7280"),
                line = Color.parseColor("#D0D7DE"),
                accent = Color.parseColor("#0F766E"),
                homeOverlay = Color.parseColor("#B3F7F8FA"),
            )

            BrowserThemeMode.DARK -> BrowserThemePalette(
                surface = Color.parseColor("#12161B"),
                surfaceAlt = Color.parseColor("#1A2027"),
                text = Color.parseColor("#E5E7EB"),
                textMuted = Color.parseColor("#9CA3AF"),
                line = Color.parseColor("#2D3748"),
                accent = Color.parseColor("#3AB7A8"),
                homeOverlay = Color.parseColor("#AA0C1015"),
            )

            BrowserThemeMode.EYE_CARE -> BrowserThemePalette(
                surface = Color.parseColor("#EEF4E7"),
                surfaceAlt = Color.parseColor("#F8FAF0"),
                text = Color.parseColor("#304032"),
                textMuted = Color.parseColor("#677567"),
                line = Color.parseColor("#C8D3C0"),
                accent = Color.parseColor("#6A8F59"),
                homeOverlay = Color.parseColor("#A6E9F0DF"),
            )

            BrowserThemeMode.PAPER -> BrowserThemePalette(
                surface = Color.parseColor("#F6F1E6"),
                surfaceAlt = Color.parseColor("#FBF8F1"),
                text = Color.parseColor("#2F2A24"),
                textMuted = Color.parseColor("#756A5D"),
                line = Color.parseColor("#D7CCBE"),
                accent = Color.parseColor("#A56B3F"),
                homeOverlay = Color.parseColor("#B8F6F1E6"),
            )

            BrowserThemeMode.GRAPHITE -> BrowserThemePalette(
                surface = Color.parseColor("#171A1F"),
                surfaceAlt = Color.parseColor("#22262D"),
                text = Color.parseColor("#F2F4F7"),
                textMuted = Color.parseColor("#A7AFBA"),
                line = Color.parseColor("#353B45"),
                accent = Color.parseColor("#D2B48C"),
                homeOverlay = Color.parseColor("#B814171C"),
            )
        }
    }
}
