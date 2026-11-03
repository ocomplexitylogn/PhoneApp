package app.cushion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import app.cushion.ui.CushionRoot
import app.cushion.ui.theme.CushionTheme
import app.cushion.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.Ink.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.Ink.toArgb()),
        )
        super.onCreate(savedInstanceState)
        val graph = (application as CushionApp).graph
        setContent {
            CushionTheme {
                CushionRoot(graph)
            }
        }
    }
}
