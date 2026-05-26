package io.lmp.copilot.ui

import java.awt.Color
import javax.swing.UIManager

/**
 * Theme-aware color lookups. We deliberately do NOT install a custom Look-and-Feel
 * (that would clobber Burp's theme globally). Instead, every color in our UI is
 * derived from the active L&F via UIManager, with sensible fallbacks.
 *
 * This means our panels look native both in Burp's dark theme and its light theme.
 */
internal object Theme {

    /** Background color of the surrounding Burp UI. Lives behind the chat list, sidebar, etc. */
    val panelBg: Color
        get() = uiColor("Panel.background", fallbackLight = Color(0xF2, 0xF2, 0xF2), fallbackDark = Color(0x2B, 0x2B, 0x2B))

    /** Background of editable areas (text fields, editor panes). Usually has higher contrast vs panelBg. */
    val textBg: Color
        get() = uiColor("TextField.background", fallbackLight = Color.WHITE, fallbackDark = Color(0x3C, 0x3F, 0x41))

    /** Default text color. */
    val fg: Color
        get() = uiColor("Label.foreground", fallbackLight = Color(0x20, 0x20, 0x20), fallbackDark = Color(0xCC, 0xCC, 0xCC))

    /** A muted secondary text color, for labels like "no pinned context". */
    val mutedFg: Color
        get() = uiColor("Label.disabledForeground", fallbackLight = Color(0x80, 0x80, 0x80), fallbackDark = Color(0x88, 0x88, 0x88))

    /** A subtle separator / border line. */
    val border: Color
        get() = uiColor("Separator.foreground", fallbackLight = Color(0xD0, 0xD0, 0xD0), fallbackDark = Color(0x55, 0x55, 0x55))

    /** Slightly tinted background used for user bubbles. */
    val userBubbleBg: Color
        get() = tintTowards(textBg, accent(), 0.18f)

    /** Background for assistant bubbles — same as text background for max contrast against panelBg. */
    val assistantBubbleBg: Color
        get() = textBg

    /** Background for system / info bubbles. */
    val systemBubbleBg: Color
        get() = tintTowards(textBg, Color(0xFF, 0xC1, 0x07), 0.18f)

    /** Background for context chips in the pinned-context bar. */
    val chipBg: Color
        get() = tintTowards(panelBg, accent(), 0.22f)

    /** Border color for context chips. */
    val chipBorder: Color
        get() = tintTowards(panelBg, accent(), 0.45f)

    /** Accent color (used for the role stripe on user bubbles + chip tint). */
    fun accent(): Color =
        uiColor("Component.focusColor", fallbackLight = Color(0x2E, 0x86, 0xDE), fallbackDark = Color(0x4A, 0x90, 0xE2))

    /** Role stripe colors for the left edge of each message bubble. */
    val userStripe: Color get() = accent()
    val assistantStripe: Color get() = Color(0x2E, 0xA4, 0x4A)
    val systemStripe: Color get() = Color(0xFF, 0xA0, 0x00)

    /** Error text color (used inline when a stream fails). */
    val errorFg: Color
        get() = if (isDark(panelBg)) Color(0xFF, 0x6E, 0x6E) else Color(0xCC, 0x33, 0x33)

    // ---- helpers ----

    private fun uiColor(key: String, fallbackLight: Color, fallbackDark: Color): Color {
        val v = UIManager.getColor(key)
        if (v != null) return Color(v.red, v.green, v.blue) // strip any UIResource wrapper
        // Best-effort detection of dark mode via Panel.background brightness.
        val panel = UIManager.getColor("Panel.background")
        return if (panel != null && isDark(panel)) fallbackDark else fallbackLight
    }

    private fun isDark(c: Color): Boolean {
        // Perceived luminance using ITU-R BT.601
        val l = 0.299 * c.red + 0.587 * c.green + 0.114 * c.blue
        return l < 128
    }

    /** Blends [base] toward [target] by [factor] (0..1). */
    private fun tintTowards(base: Color, target: Color, factor: Float): Color {
        val f = factor.coerceIn(0f, 1f)
        val r = (base.red * (1 - f) + target.red * f).toInt().coerceIn(0, 255)
        val g = (base.green * (1 - f) + target.green * f).toInt().coerceIn(0, 255)
        val b = (base.blue * (1 - f) + target.blue * f).toInt().coerceIn(0, 255)
        return Color(r, g, b)
    }
}
