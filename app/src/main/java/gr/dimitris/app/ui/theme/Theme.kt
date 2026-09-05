package gr.dimitris.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Scheme = lightColorScheme(
    primary = Palette.navy, onPrimary = Color.White,
    secondary = Palette.amber, onSecondary = Color.White,
    tertiary = Palette.teal, onTertiary = Color.White,
    background = Palette.cream, onBackground = Palette.ink,
    surface = Color.White, onSurface = Palette.ink,
    surfaceVariant = Palette.mist, onSurfaceVariant = Palette.ink,
    error = Palette.brick, onError = Color.White,
)

val DimitrisTypography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun DimitrisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = DimitrisTypography, content = content)
}
