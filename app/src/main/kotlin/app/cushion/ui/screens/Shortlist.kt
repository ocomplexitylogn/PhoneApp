package app.cushion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.cushion.R
import app.cushion.core.format.Fmt
import app.cushion.core.model.Quote
import app.cushion.core.repo.Live
import app.cushion.core.repo.marketDate
import app.cushion.core.repo.poll
import app.cushion.core.screen.ShortlistItem
import app.cushion.data.AppGraph
import app.cushion.ui.Routes
import app.cushion.ui.components.Card
import app.cushion.ui.components.CircleButton
import app.cushion.ui.components.Notice
import app.cushion.ui.components.PremiumPill
import app.cushion.ui.components.ScreenGutter
import app.cushion.ui.components.SmallChip
import app.cushion.ui.components.TopBar
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ShortlistViewModel(private val graph: AppGraph) : ViewModel() {
    val quotes: StateFlow<Live<Map<String, Quote>>> = graph.shortlist.items
        .flatMapLatest { items ->
            val today = marketDate(System.currentTimeMillis())
            val symbols = items.filter { !it.expiration.isBefore(today) }.map { it.contractSymbol }
            poll({ graph.quoteIntervalMs() * 3 }) { graph.repository.quotes(symbols) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Live())
}

@Composable
fun ShortlistScreen(graph: AppGraph, nav: NavHostController) {
    val vm = viewModel { ShortlistViewModel(graph) }
    val items by graph.shortlist.items.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Shortlist")
        if (items.isEmpty()) {
            Notice("Your shortlist is empty", "Tap + on any strike to compare it here. Nothing on this list is an order.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.contractSymbol }) { item ->
                    ShortlistCard(
                        item = item,
                        quote = quotes.value?.get(item.contractSymbol),
                        onOpen = { nav.navigate(Routes.ticker(item.underlying, item.expiration)) },
                        onContracts = { graph.shortlist.setContracts(item.contractSymbol, it) },
                        onRemove = { graph.shortlist.remove(item.contractSymbol) },
                    )
                }
                quotes.error?.let { message ->
                    item(key = "error") { Text(message, style = Type.Small, color = Palette.Orange) }
                }
            }
        }
    }
}

@Composable
private fun ShortlistCard(
    item: ShortlistItem,
    quote: Quote?,
    onOpen: () -> Unit,
    onContracts: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val today = marketDate(System.currentTimeMillis())
    val expired = item.expiration.isBefore(today)
    Card(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.underlying, style = Type.Row)
                Text("${Fmt.strike(item.strike)} Put · ${Fmt.monthDay(item.expiration)}", style = Type.Small, color = Palette.Secondary)
            }
            if (expired) {
                SmallChip("Expired")
            } else {
                PremiumPill(quote?.mark?.let(Fmt::money) ?: "–")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Added at ${Fmt.money(item.premiumWhenAdded)}", style = Type.Small, color = Palette.Secondary)
                Text("Collateral ${Fmt.wholeMoney(item.collateral)}", style = Type.Small, color = Palette.Secondary)
            }
            CircleButton(R.drawable.ic_remove, "One fewer contract", { onContracts(item.contracts - 1) }, size = 30.dp, iconSize = 16.dp, background = Palette.Raised)
            Text("${item.contracts}", style = Type.Label)
            CircleButton(R.drawable.ic_add, "One more contract", { onContracts(item.contracts + 1) }, size = 30.dp, iconSize = 16.dp, background = Palette.Raised)
            CircleButton(R.drawable.ic_trash, "Remove", onRemove, size = 30.dp, iconSize = 16.dp, background = Palette.Raised, tint = Palette.Orange)
        }
    }
}
