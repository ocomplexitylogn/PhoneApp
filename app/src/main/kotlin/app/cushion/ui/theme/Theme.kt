package app.cushion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.cushion.R

/** Sampled directly from the mock screens. */
object Palette {
    val Ink = Color(0xFF111310)
    val Card = Color(0xFF242624)
    val Raised = Color(0xFF3A3C39)
    val Control = Color(0xFF3F413E)
    val NavTrack = Color(0xFF141413)
    val NavSelected = Color(0xFF000000)
    val Lime = Color(0xFFAEE57E)
    val Green = Color(0xFF76C250)
    val Orange = Color(0xFFDA663A)
    val Amber = Color(0xFFD6A34E)
    val Text = Color(0xFFF2F4F0)
    val Secondary = Color(0xFFD7D9D5)
    val Muted = Color(0xFF9EA09C)
    val Line = Color(0xFF8A8B89)
    val PillFill = Color(0xFF2D3928)
    val PillBorder = Color(0xFF4C7438)
}

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

/** Tabular figures so prices don't shimmer as they update. */
private val base = TextStyle(fontFamily = Figtree, color = Palette.Text, fontFeatureSettings = "tnum")

object Type {
    val Screen = base.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val Display = base.copy(fontSize = 46.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val Section = base.copy(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
    val CardTitle = base.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    val Row = base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val Label = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val Body = base.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    val Small = base.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal)
    val SmallStrong = base.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    val Tiny = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val Ring = base.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

private val scheme = darkColorScheme(
    primary = Palette.Lime,
    onPrimary = Palette.Ink,
    secondary = Palette.Green,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Card,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.Secondary,
    surfaceContainerLow = Palette.Card,
    outline = Palette.Raised,
    error = Palette.Orange,
)

@Composable
fun CushionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
