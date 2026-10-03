package com.termux.terminal.compose

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * Expressive M3 theme wrapper for all Compose screens.
 *
 * Uses dynamic color on API 31+ and the expressive light scheme /
 * standard dark scheme below that (material3 1.3.2 only ships
 * `expressiveLightColorScheme`). The terminal palette derivation
 * keeps working via `MaterialTheme.colorScheme`.
 *
 * When [terminalTypeface] is provided, every typography style uses it as its
 * font family so the whole app UI mirrors the terminal font (see
 * [TerminalFontLoader]). With {@code null} the default M3 typeface is kept.
 *
 * @param darkTheme Whether to use the dark color scheme.
 * @param dynamicColor Whether to use dynamic (wallpaper) color on API 31+.
 * @param terminalTypeface Terminal typeface mirrored by the app UI, or {@code null}
 * for the default M3 typeface.
 * @param content The themed content.
 */
@Composable
fun TermuxExpressiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    terminalTypeface: Typeface? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = remember(terminalTypeface) {
            if (terminalTypeface == null) Typography()
            else Typography().withDefaultFontFamily(FontFamily(terminalTypeface))
        },
        content = content
    )
}

/**
 * Copy of this [Typography] with every style using [fontFamily].
 *
 * @param fontFamily The font family applied to all styles.
 * @return The copied [Typography].
 */
private fun Typography.withDefaultFontFamily(fontFamily: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily)
)
