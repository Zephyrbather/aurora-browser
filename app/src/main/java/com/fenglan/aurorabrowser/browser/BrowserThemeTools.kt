package com.fenglan.aurorabrowser.browser

import android.graphics.Color
import com.fenglan.aurorabrowser.model.BrowserThemeMode
import java.util.Calendar

data class BrowserThemePalette(
    val surface: Int,
    val surfaceAlt: Int,
    val text: Int,
    val textMuted: Int,
    val line: Int,
    val accent: Int,
    val homeOverlay: Int,
    val gradientStart: Int? = null,
    val gradientEnd: Int? = null,
    val gradientMid: Int? = null,
)

enum class TimeBasedThemePeriod(val label: String, val isDark: Boolean, val hourRange: IntRange) {
    MORNING("晨光", false, 6..11),
    AFTERNOON("午后", false, 12..17),
    EVENING("暮色", false, 18..21),
    NIGHT("星夜", true, 22..23),
    NIGHT_LATE("深夜", true, 0..5),
}

object BrowserThemeTools {
    fun isEffectivelyDark(mode: BrowserThemeMode): Boolean {
        return when (mode) {
            BrowserThemeMode.TIME_BASED -> isTimeBasedDark()
            BrowserThemeMode.CHERRY, BrowserThemeMode.SUNSET -> false
            else -> mode.isDark
        }
    }

    fun paletteFor(mode: BrowserThemeMode): BrowserThemePalette {
        return when (mode) {
            BrowserThemeMode.LIGHT -> lightPalette
            BrowserThemeMode.DARK -> darkPalette
            BrowserThemeMode.EYE_CARE -> eyeCarePalette
            BrowserThemeMode.PAPER -> paperPalette
            BrowserThemeMode.GRAPHITE -> graphitePalette
            BrowserThemeMode.TIME_BASED -> timeBasedPalette()
            BrowserThemeMode.AURORA -> auroraGradientPalette
            BrowserThemeMode.SUNSET -> sunsetGradientPalette
            BrowserThemeMode.OCEAN -> oceanGradientPalette
            BrowserThemeMode.CHERRY -> cherryGradientPalette
            BrowserThemeMode.NEBULA -> nebulaGradientPalette
            BrowserThemeMode.CUSTOM_IMAGE -> customImagePalette
        }
    }

    fun timeBasedPeriod(): TimeBasedThemePeriod {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return TimeBasedThemePeriod.entries.firstOrNull { hour in it.hourRange }
            ?: TimeBasedThemePeriod.NIGHT_LATE
    }

    fun timeBasedPalette(): BrowserThemePalette {
        return when (timeBasedPeriod()) {
            TimeBasedThemePeriod.MORNING -> morningPalette
            TimeBasedThemePeriod.AFTERNOON -> afternoonPalette
            TimeBasedThemePeriod.EVENING -> eveningPalette
            TimeBasedThemePeriod.NIGHT, TimeBasedThemePeriod.NIGHT_LATE -> nightPalette
        }
    }

    fun isTimeBasedDark(): Boolean {
        return timeBasedPeriod().isDark
    }

    fun millisUntilNextPeriod(): Long {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)

        val boundaries = listOf(6, 12, 18, 22)
        val nextBoundary = boundaries.firstOrNull { it > hour }
            ?: (boundaries.first() + 24)

        val targetHour = if (nextBoundary >= 24) nextBoundary - 24 else nextBoundary
        val dayOffset = if (nextBoundary >= 24) 1 else 0

        val target = Calendar.getInstance().apply {
            if (dayOffset > 0) add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return target.timeInMillis - now.timeInMillis
    }

    private val lightPalette = BrowserThemePalette(
        surface = Color.parseColor("#F7F8FA"),
        surfaceAlt = Color.parseColor("#EEF2F6"),
        text = Color.parseColor("#111827"),
        textMuted = Color.parseColor("#6B7280"),
        line = Color.parseColor("#D0D7DE"),
        accent = Color.parseColor("#0F766E"),
        homeOverlay = Color.parseColor("#B3F7F8FA"),
    )

    private val darkPalette = BrowserThemePalette(
        surface = Color.parseColor("#12161B"),
        surfaceAlt = Color.parseColor("#1A2027"),
        text = Color.parseColor("#E5E7EB"),
        textMuted = Color.parseColor("#9CA3AF"),
        line = Color.parseColor("#2D3748"),
        accent = Color.parseColor("#3AB7A8"),
        homeOverlay = Color.parseColor("#AA0C1015"),
    )

    private val eyeCarePalette = BrowserThemePalette(
        surface = Color.parseColor("#EEF4E7"),
        surfaceAlt = Color.parseColor("#F8FAF0"),
        text = Color.parseColor("#304032"),
        textMuted = Color.parseColor("#677567"),
        line = Color.parseColor("#C8D3C0"),
        accent = Color.parseColor("#6A8F59"),
        homeOverlay = Color.parseColor("#A6E9F0DF"),
    )

    private val paperPalette = BrowserThemePalette(
        surface = Color.parseColor("#F6F1E6"),
        surfaceAlt = Color.parseColor("#FBF8F1"),
        text = Color.parseColor("#2F2A24"),
        textMuted = Color.parseColor("#756A5D"),
        line = Color.parseColor("#D7CCBE"),
        accent = Color.parseColor("#A56B3F"),
        homeOverlay = Color.parseColor("#B8F6F1E6"),
    )

    private val graphitePalette = BrowserThemePalette(
        surface = Color.parseColor("#171A1F"),
        surfaceAlt = Color.parseColor("#22262D"),
        text = Color.parseColor("#F2F4F7"),
        textMuted = Color.parseColor("#A7AFBA"),
        line = Color.parseColor("#353B45"),
        accent = Color.parseColor("#D2B48C"),
        homeOverlay = Color.parseColor("#B814171C"),
    )

    // ── Time-based palettes ──────────────────────────────────────────

    /** 6:00–11:59 — warm morning gold */
    private val morningPalette = BrowserThemePalette(
        surface = Color.parseColor("#FFF8F0"),
        surfaceAlt = Color.parseColor("#FFF0E0"),
        text = Color.parseColor("#3E2E20"),
        textMuted = Color.parseColor("#8B7355"),
        line = Color.parseColor("#E8D5C0"),
        accent = Color.parseColor("#D4873A"),
        homeOverlay = Color.parseColor("#B8FFF5EB"),
    )

    /** 12:00–17:59 — bright sky blue */
    private val afternoonPalette = BrowserThemePalette(
        surface = Color.parseColor("#F2F7FC"),
        surfaceAlt = Color.parseColor("#E6F0F8"),
        text = Color.parseColor("#1A2E3F"),
        textMuted = Color.parseColor("#5A7A96"),
        line = Color.parseColor("#C8DAE8"),
        accent = Color.parseColor("#2E7DB5"),
        homeOverlay = Color.parseColor("#B3EEF5FD"),
    )

    /** 18:00–21:59 — sunset amber/violet */
    private val eveningPalette = BrowserThemePalette(
        surface = Color.parseColor("#FBF5F0"),
        surfaceAlt = Color.parseColor("#F8EDE4"),
        text = Color.parseColor("#3D2B38"),
        textMuted = Color.parseColor("#8C6D7A"),
        line = Color.parseColor("#E0CCBE"),
        accent = Color.parseColor("#C45B3C"),
        homeOverlay = Color.parseColor("#B8F8F2EC"),
    )

    /** 22:00–5:59 — deep indigo night */
    private val nightPalette = BrowserThemePalette(
        surface = Color.parseColor("#0F1119"),
        surfaceAlt = Color.parseColor("#181B28"),
        text = Color.parseColor("#D8DCF0"),
        textMuted = Color.parseColor("#7880A0"),
        line = Color.parseColor("#262A40"),
        accent = Color.parseColor("#8B9DCF"),
        homeOverlay = Color.parseColor("#A80A0C14"),
    )

    // ── Gradient palettes ────────────────────────────────────────────

    private val auroraGradientPalette = BrowserThemePalette(
        surface = Color.parseColor("#0D1117"),
        surfaceAlt = Color.parseColor("#161B22"),
        text = Color.parseColor("#E6EDF3"),
        textMuted = Color.parseColor("#8B949E"),
        line = Color.parseColor("#30363D"),
        accent = Color.parseColor("#58D5A2"),
        homeOverlay = Color.parseColor("#990D1117"),
        gradientStart = Color.parseColor("#0F2027"),
        gradientMid = Color.parseColor("#203A43"),
        gradientEnd = Color.parseColor("#2C5364"),
    )

    private val sunsetGradientPalette = BrowserThemePalette(
        surface = Color.parseColor("#FFF5F0"),
        surfaceAlt = Color.parseColor("#FFEEE4"),
        text = Color.parseColor("#3E2723"),
        textMuted = Color.parseColor("#8D6E63"),
        line = Color.parseColor("#F0D5C8"),
        accent = Color.parseColor("#E65100"),
        homeOverlay = Color.parseColor("#B3FFF5F0"),
        gradientStart = Color.parseColor("#FF9A56"),
        gradientMid = Color.parseColor("#FF6F7D"),
        gradientEnd = Color.parseColor("#D6408F"),
    )

    private val oceanGradientPalette = BrowserThemePalette(
        surface = Color.parseColor("#0A1628"),
        surfaceAlt = Color.parseColor("#112240"),
        text = Color.parseColor("#CCD6F6"),
        textMuted = Color.parseColor("#8892B0"),
        line = Color.parseColor("#233554"),
        accent = Color.parseColor("#64FFDA"),
        homeOverlay = Color.parseColor("#990A1628"),
        gradientStart = Color.parseColor("#0F3460"),
        gradientMid = Color.parseColor("#16213E"),
        gradientEnd = Color.parseColor("#1A1A2E"),
    )

    private val cherryGradientPalette = BrowserThemePalette(
        surface = Color.parseColor("#FFF8F9"),
        surfaceAlt = Color.parseColor("#FFEEF1"),
        text = Color.parseColor("#4A1528"),
        textMuted = Color.parseColor("#9E576D"),
        line = Color.parseColor("#F5D0D9"),
        accent = Color.parseColor("#C2185B"),
        homeOverlay = Color.parseColor("#B3FFF8F9"),
        gradientStart = Color.parseColor("#FFB6C1"),
        gradientMid = Color.parseColor("#FF85A2"),
        gradientEnd = Color.parseColor("#FF4081"),
    )

    private val nebulaGradientPalette = BrowserThemePalette(
        surface = Color.parseColor("#0E0B16"),
        surfaceAlt = Color.parseColor("#1A1429"),
        text = Color.parseColor("#E0D6F0"),
        textMuted = Color.parseColor("#9585B5"),
        line = Color.parseColor("#2D2545"),
        accent = Color.parseColor("#BB86FC"),
        homeOverlay = Color.parseColor("#990E0B16"),
        gradientStart = Color.parseColor("#1A0533"),
        gradientMid = Color.parseColor("#2D1B69"),
        gradientEnd = Color.parseColor("#5B2C8E"),
    )

    val customImagePalette = BrowserThemePalette(
        surface = Color.parseColor("#12161B"),
        surfaceAlt = Color.parseColor("#1A2027"),
        text = Color.parseColor("#E5E7EB"),
        textMuted = Color.parseColor("#9CA3AF"),
        line = Color.parseColor("#2D3748"),
        accent = Color.parseColor("#3AB7A8"),
        homeOverlay = Color.parseColor("#66000000"),
    )
}
