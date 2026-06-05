package com.fenglan.aurorabrowser.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ThemeManager {
    @Volatile
    var currentPalette: BrowserThemePalette = BrowserThemeTools.paletteFor(
        com.fenglan.aurorabrowser.model.BrowserThemeMode.LIGHT
    )
        private set

    fun update(palette: BrowserThemePalette) {
        currentPalette = palette
    }

    fun applyToActivity(activity: Activity) {
        val p = currentPalette
        activity.window?.decorView?.setBackgroundColor(p.surface)
        val window: Window? = activity.window
        if (window != null) {
            val isDark = BrowserThemeTools.isEffectivelyDark(
                com.fenglan.aurorabrowser.model.BrowserThemeMode.entries.firstOrNull { BrowserThemeTools.paletteFor(it) == p }
                    ?: com.fenglan.aurorabrowser.model.BrowserThemeMode.LIGHT
            )
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDark
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = !isDark
        }
    }

    fun tintView(view: View, color: Int) {
        when (view) {
            is ImageView -> view.imageTintList = ColorStateList.valueOf(color)
            is TextView -> view.setTextColor(color)
        }
    }

    fun applyCardTheme(card: MaterialCardView) {
        val p = currentPalette
        card.setCardBackgroundColor(p.surfaceAlt)
        card.strokeColor = p.line
    }

    fun gradientDrawableIfAvailable(): GradientDrawable? {
        val p = currentPalette
        return if (p.gradientStart != null && p.gradientEnd != null) {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (p.gradientMid != null) intArrayOf(p.gradientStart, p.gradientMid, p.gradientEnd)
                else intArrayOf(p.gradientStart, p.gradientEnd),
            )
        } else null
    }

    fun styleAlertDialog(context: android.content.Context, builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder {
        val p = currentPalette
        return builder.setBackground(
            GradientDrawable().apply {
                setColor(p.surface)
                cornerRadius = 48f
                setStroke(2, p.line)
            }
        )
    }
}
