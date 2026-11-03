package app.cushion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.cushion.core.format.Fmt
import app.cushion.core.model.EarningsTiming
import app.cushion.data.AppGraph
import app.cushion.ui.Routes
import app.cushion.ui.components.Notice
import app.cushion.ui.components.ScreenGutter
import app.cushion.ui.components.TopBar
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type

@Composable
fun EarningsScreen(graph: AppGraph, nav: NavHostController) {
    val vm = viewModel { BrowseViewModel(graph) }
    val earnings by vm.earnings.collectAsStateWithLifecycle()
    val days = remember(earnings.value) { earnings.value.orEmpty().groupBy { it.date } }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Earnings this week", onBack = { nav.popBackStack() })
        when {
            days.isEmpty() && earnings.loading -> Notice("Loading calendar")
            days.isEmpty() -> Notice("Earnings calendar unavailable", earnings.error ?: "Nothing is scheduled this week.")
            else -> LazyColumn(contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 20.dp)) {
                days.forEach { (date, events) ->
                    item(key = date.toString()) {
                        Text(
                            "${Fmt.weekday(date)}, ${Fmt.monthDay(date)}",
                            style = Type.CardTitle,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(events, key = { "${date}-${it.symbol}" }) { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate(Routes.ticker(e.symbol)) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.symbol, style = Type.Row)
                                e.name?.let {
                                    Text(it, style = Type.Small, color = Palette.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(
                                when (e.timing) {
                                    EarningsTiming.BEFORE_OPEN -> "Before open"
                                    EarningsTiming.AFTER_CLOSE -> "After close"
                                    EarningsTiming.UNKNOWN -> "Time not set"
                                },
                                style = Type.Small,
                                color = Palette.Muted,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
