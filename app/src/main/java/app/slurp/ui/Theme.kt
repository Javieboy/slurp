package app.slurp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Mint = Color(0xFF7DF9C3)
private val MintDim = Color(0xFF2F6B55)
private val Ink = Color(0xFF0E1414)
private val Slate = Color(0xFF17201F)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = MintDim,
    onPrimaryContainer = Color(0xFFD6FFEE),
    background = Ink,
    onBackground = Color(0xFFE4ECEA),
    surface = Slate,
    onSurface = Color(0xFFE4ECEA),
    surfaceVariant = Color(0xFF1F2A29),
    onSurfaceVariant = Color(0xFF9FB3AF),
    error = Color(0xFFFF8A80),
    onError = Ink,
)

private val LightColors = lightColorScheme(
    primary = MintDim,
    onPrimary = Color.White,
    background = Color(0xFFF7FAF9),
    surface = Color.White,
)

@Composable
fun SlurpTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
