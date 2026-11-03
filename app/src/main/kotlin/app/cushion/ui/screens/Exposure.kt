package app.cushion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cushion.core.format.Fmt
import app.cushion.core.screen.ExposureLine
import app.cushion.core.screen.exposureOf
import app.cushion.data.AppGraph
import app.cushion.ui.Routes
import app.cushion.ui.components.Card
import app.cushion.ui.components.Notice
import app.cushion.ui.components.StatRow
import app.cushion.ui.components.TopBar
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type

@Composable
fun ExposureScreen(graph: AppGraph, nav: NavHostController) {
    val items by graph.shortlist.items.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val report = remember(items, settings) {
        exposureOf(items, settings.accountValue, settings.perNameCapPct, settings.sectorCapPct)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Exposure")
        if (items.isEmpty()) {
            Notice("Nothing to measure yet", "This shows what your shortlist would do to your account if every put were sold.")
        } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Text("If all were sold", style = Type.CardTitle, modifier = Modifier.padding(bottom = 6.dp))
                StatRow("Collateral", Fmt.wholeMoney(report.totalCollateral))
                StatRow("Premium", Fmt.wholeMoney(report.totalPremium))
                report.pctOfAccount?.let { StatRow("Of account", Fmt.oneDecimalPercent(it)) }
            }
            if (settings.accountValue == null) {
                Column(Modifier.clickable { nav.navigate(Routes.SETTINGS) }) {
                    Text(
                        "Add your account value in Settings to check these against your caps.",
                        style = Type.Small,
                        color = Palette.Lime,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            LinesCard("By name", report.byName)
            LinesCard("By sector", report.byGroup)
            Box(Modifier.height(4.dp))
        }
        }
    }
}

@Composable
private fun LinesCard(title: String, lines: List<ExposureLine>) {
    Card {
        Text(title, style = Type.CardTitle, modifier = Modifier.padding(bottom = 6.dp))
        lines.forEach { line ->
            Column(Modifier.padding(vertical = 7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(line.label, style = Type.Label)
                    Text(
                        line.pctOfAccount?.let { "${Fmt.oneDecimalPercent(it)} of ${Fmt.decimal(line.capPct, 0)}% cap" }
                            ?: Fmt.wholeMoney(line.collateral),
                        style = Type.Small,
                        color = if (line.overCap) Palette.Orange else Palette.Secondary,
                    )
                }
                line.pctOfAccount?.let { pct ->
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Palette.Raised),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((pct / line.capPct).coerceIn(0.0, 1.0).toFloat())
                                .clip(CircleShape)
                                .background(if (line.overCap) Palette.Orange else Palette.Green),
                        )
                    }
                }
            }
        }
    }
}
