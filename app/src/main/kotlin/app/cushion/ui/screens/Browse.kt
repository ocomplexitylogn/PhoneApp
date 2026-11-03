package app.cushion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.cushion.R
import app.cushion.core.format.Fmt
import app.cushion.core.model.EarningsEvent
import app.cushion.core.model.Instrument
import app.cushion.core.model.Quote
import app.cushion.core.repo.ChartData
import app.cushion.core.repo.Live
import app.cushion.core.repo.buildChart
import app.cushion.core.repo.poll
import app.cushion.core.schwab.ChartRange
import app.cushion.data.AppGraph
import app.cushion.ui.Routes
import app.cushion.ui.components.MoveTriangle
import app.cushion.ui.components.ScreenGutter
import app.cushion.ui.components.SectionHeader
import app.cushion.ui.components.Sparkline
import app.cushion.ui.components.TopBar
import app.cushion.ui.components.ValueChip
import app.cushion.ui.components.moveColor
import app.cushion.ui.components.sideLabel
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

private data class Tile(val symbol: String, val title: String)

private val marketTiles = listOf(Tile("SPY", "S&P 500"), Tile("QQQ", "Nasdaq 100"), Tile("DIA", "Dow Jones"))
private val cryptoTiles = listOf(Tile("IBIT", "Bitcoin"), Tile("ETHA", "Ethereum"), Tile("SOLZ", "Solana"))

private const val CHIPS_PER_SECTION = 16

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowseViewModel(private val graph: AppGraph) : ViewModel() {
    private val symbols = (marketTiles + cryptoTiles).map { it.symbol }
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    val query = MutableStateFlow("")

    val quotes: StateFlow<Live<Map<String, Quote>>> =
        poll({ graph.quoteIntervalMs() }) { graph.repository.quotes(symbols) }
            .stateIn(viewModelScope, sharing, Live())

    val charts: StateFlow<Live<Map<String, ChartData>>> = poll({ 5L * 60 * 1000 }) {
        val hours = graph.repository.marketHoursToday()
        symbols.associateWith { buildChart(graph.repository.sparkline(it), ChartRange.D1, hours) }
    }.stateIn(viewModelScope, sharing, Live())

    val earnings: StateFlow<Live<List<EarningsEvent>>> =
        poll({ 6L * 60 * 60 * 1000 }) { graph.repository.earningsThisWeek() }
            .stateIn(viewModelScope, sharing, Live())

    val results: StateFlow<List<Instrument>> = query
        .debounce(300)
        .flatMapLatest { text ->
            flow {
                emit(
                    try {
                        graph.repository.search(text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    },
                )
            }.flowOn(Dispatchers.Default)
        }
        .stateIn(viewModelScope, sharing, emptyList())
}

@Composable
fun BrowseScreen(graph: AppGraph, nav: NavHostController) {
    KeepScanFresh(graph)
    val vm = viewModel { BrowseViewModel(graph) }
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()
    val earnings by vm.earnings.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val scan by graph.scans.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Browse", onBack = { nav.popBackStack() })
        SearchField(
            value = query,
            onChange = { vm.query.value = it },
            onSubmit = {
                val symbol = query.trim().uppercase()
                if (symbol.isNotEmpty()) nav.navigate(Routes.ticker(symbol))
            },
        )
        if (query.isNotBlank()) {
            LazyColumn(contentPadding = PaddingValues(ScreenGutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.symbol }) { instrument ->
                    SearchResult(instrument) { nav.navigate(Routes.ticker(instrument.symbol)) }
                }
            }
        } else LazyColumn(
            contentPadding = PaddingValues(top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "market") {
                SectionHeader(R.drawable.ic_market, "Market", Modifier.padding(horizontal = ScreenGutter))
            }
            item(key = "market-row") {
                TileRow(marketTiles, quotes, charts) { nav.navigate(Routes.ticker(it)) }
            }
            item(key = "crypto") {
                SectionHeader(R.drawable.ic_crypto, "Crypto", Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp))
            }
            item(key = "crypto-row") {
                TileRow(cryptoTiles, quotes, charts) { nav.navigate(Routes.ticker(it)) }
            }
            item(key = "premiums") {
                SectionHeader(
                    R.drawable.ic_flame,
                    "Top premiums",
                    Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp),
                    onSeeAll = { nav.popBackStack(Routes.IDEAS, inclusive = false) },
                )
            }
            item(key = "premium-chips") {
                val ideas = scan.result?.topPremiums.orEmpty().take(CHIPS_PER_SECTION)
                when {
                    ideas.isNotEmpty() -> TwoRowChips(ideas) { idea ->
                        val c = idea.contract
                        ValueChip(c.underlying, "${sideLabel(c.putCall)} ${Fmt.money(idea.metrics.premium)}", {
                            nav.navigate(Routes.ticker(c.underlying, c.expiration))
                        })
                    }
                    scan.running -> SectionNote("Scanning for ideas")
                    else -> SectionNote(scan.error ?: "Nothing passes your rules right now")
                }
            }
            item(key = "earnings") {
                SectionHeader(
                    R.drawable.ic_calendar,
                    "Earnings this week",
                    Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp),
                    onSeeAll = { nav.navigate(Routes.EARNINGS) },
                )
            }
            item(key = "earnings-chips") {
                val events = earnings.value.orEmpty().take(CHIPS_PER_SECTION)
                when {
                    events.isNotEmpty() -> TwoRowChips(events) { e ->
                        ValueChip(e.symbol, Fmt.monthDay(e.date), { nav.navigate(Routes.ticker(e.symbol)) }, Palette.Secondary)
                    }
                    earnings.loading -> SectionNote("Loading calendar")
                    else -> SectionNote("Earnings calendar unavailable")
                }
            }
        }
    }
}

@Composable
private fun SectionNote(text: String) {
    Text(text, style = Type.Small, color = Palette.Muted, modifier = Modifier.padding(horizontal = ScreenGutter))
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Type.Body,
        cursorBrush = SolidColor(Palette.Lime),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 8.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(Palette.Card)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = Palette.Secondary,
                    modifier = Modifier.size(20.dp),
                )
                Box(Modifier.padding(start = 12.dp).weight(1f)) {
                    if (value.isEmpty()) Text("Search a stock name or symbol", style = Type.Body, color = Palette.Muted)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun SearchResult(instrument: Instrument, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Text(instrument.symbol, style = Type.Row)
        Text(instrument.description, style = Type.Small, color = Palette.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TileRow(
    tiles: List<Tile>,
    quotes: Live<Map<String, Quote>>,
    charts: Live<Map<String, ChartData>>,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenGutter),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        items(tiles, key = { it.symbol }) { tile ->
            MarketTile(tile, quotes.value?.get(tile.symbol), charts.value?.get(tile.symbol)) { onOpen(tile.symbol) }
        }
    }
}

@Composable
private fun MarketTile(tile: Tile, quote: Quote?, chart: ChartData?, onClick: () -> Unit) {
    val change = quote?.change ?: 0.0
    Column(
        modifier = Modifier
            .width(135.dp)
            .height(175.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Card)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(tile.title, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tile.symbol, style = Type.Small, color = Palette.Secondary)
        Sparkline(chart, change, Modifier.padding(top = 10.dp))
        Box(Modifier.weight(1f))
        Text(quote?.let { Fmt.money(it.last) } ?: "–", style = Type.Label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (quote != null) {
                MoveTriangle(change, 10.dp)
                Text(Fmt.unsignedPercent(quote.changePct), style = Type.SmallStrong, color = moveColor(change))
            }
        }
    }
}

/**
 * Two independently flowing rows that scroll together. Items alternate between rows,
 * so reading top-to-bottom then left-to-right follows the list's order, as in the mock.
 */
@Composable
private fun <T> TwoRowChips(items: List<T>, chip: @Composable (T) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.filterIndexed { i, _ -> i % 2 == 0 }.forEach { chip(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.filterIndexed { i, _ -> i % 2 == 1 }.forEach { chip(it) }
        }
    }
}
