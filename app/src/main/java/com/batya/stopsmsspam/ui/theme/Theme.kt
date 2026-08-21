package com.batya.stopsmsspam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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
