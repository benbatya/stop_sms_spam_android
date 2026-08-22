package com.batya.stopsmsspam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * A success green for the "they confirmed" badge.
 *
 * Material 3 has no success role, and the tertiary slot this previously borrowed is derived from
 * the wallpaper under dynamic colour - on the test devices it came out purple. A confirmed
 * opt-out is the one piece of good news in the list, so it should read as good news whatever the
 * wallpaper happens to be, which means naming the colour rather than borrowing a slot.
 */
object ConfirmedColors {
    val container: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1B5E20) else Color(0xFFC8E6C9)

    val onContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFC8E6C9) else Color(0xFF1B5E20)
}

@Composable
fun StopSpamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color is available unconditionally: this app's floor is Android 12.
    val context = LocalContext.current
    val colorScheme =
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    MaterialTheme(colorScheme = colorScheme, content = content)
}
