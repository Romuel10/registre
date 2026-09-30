package mg.registre.communautaire.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val LightColors = lightColorScheme(
    primary = Color(0xFF173B57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8F4),
    onPrimaryContainer = Color(0xFF0B2436),
    secondary = Color(0xFF496475),
    secondaryContainer = Color(0xFFDCE8EE),
    tertiary = Color(0xFF5C5A45),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7EDF1),
    outline = Color(0xFF72808A),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7CFE9),
    primaryContainer = Color(0xFF214C68),
    secondary = Color(0xFFB5CAD7),
    tertiary = Color(0xFFC9C6A5),
    background = Color(0xFF101417),
    surface = Color(0xFF171C20),
    surfaceVariant = Color(0xFF3E484E),
)

@Composable
fun RegistreTheme(
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val currentDensity = LocalDensity.current
    val scaledDensity = Density(
        density = currentDensity.density,
        fontScale = fontScale.coerceIn(0.85f, 1.6f),
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
            content = content,
        )
    }
}
