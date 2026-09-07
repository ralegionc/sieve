package dev.sieve.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF1F6F6B)
private val TealLight = Color(0xFF6FD9D2)
private val Sand = Color(0xFF4B5D53)
private val Rust = Color(0xFFB3261E)
private val RustLight = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6EFEA),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Sand,
    tertiary = Color(0xFF4A6268),
    error = Rust,
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFDBE5E2),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504C),
    onPrimaryContainer = Color(0xFFB6EFEA),
    secondary = Color(0xFFB1CCC0),
    tertiary = Color(0xFFB1CBD1),
    error = RustLight,
    background = Color(0xFF0E1513),
    surface = Color(0xFF0E1513),
    surfaceVariant = Color(0xFF3F4946),
)

@Composable
fun SieveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Material You where it exists; the hand-picked palette everywhere else.
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
