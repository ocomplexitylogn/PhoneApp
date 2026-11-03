package app.cushion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.cushion.R
import app.cushion.core.format.Fmt
import app.cushion.core.model.Expiration
import app.cushion.core.model.OptionChain
import app.cushion.core.model.PutCall
import app.cushion.core.model.Quote
import app.cushion.core.net.ApiException
import app.cushion.core.repo.ChartData
import app.cushion.core.repo.Live
import app.cushion.core.repo.buildChart
import app.cushion.core.repo.poll
import app.cushion.core.schwab.ChartRange
import app.cushion.core.screen.ContractMetrics
import app.cushion.core.screen.SymbolContext
import app.cushion.core.screen.earningsBeforeExpiry
import app.cushion.core.screen.isSelloff
import app.cushion.core.screen.metricsFor
import app.cushion.data.AppGraph
import app.cushion.ui.components.Card
import app.cushion.ui.components.CircleButton
import app.cushion.ui.components.ContractRow
import app.cushion.ui.components.MoveTriangle
import app.cushion.ui.components.Notice
import app.cushion.ui.components.PremiumPill
import app.cushion.ui.components.PrimaryButton
import app.cushion.ui.components.PriceChart
import app.cushion.ui.components.ProbabilityRing
import app.cushion.ui.components.ScreenGutter
import app.cushion.ui.components.SegmentedToggle
import app.cushion.ui.components.SmallChip
import app.cushion.ui.components.StatRow
import app.cushion.ui.components.TopBar
import app.cushion.ui.components.moveColor
import app.cushion.ui.components.sideLabel
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_OTM_ROWS = 40
private const val MAX_ITM_ROWS = 10

@OptIn(ExperimentalCoroutinesApi::class)
class TickerViewModel(
    private val graph: AppGraph,
    val symbol: String,
    initialExpiry: LocalDate?,
) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    val range = MutableStateFlow(ChartRange.D1)
    val side = MutableStateFlow(PutCall.PUT)
    val showItm = MutableStateFlow(false)
    private val picked = MutableStateFlow(initialExpiry)

    val quote: StateFlow<Live<Quote>> = poll({ graph.quoteIntervalMs() }) {
        graph.repository.quote(symbol) ?: throw ApiException.Http(404, "$symbol wasn't found")
    }.stateIn(viewModelScope, sharing, Live())

    val chart: StateFlow<Live<ChartData>> = range.flatMapLatest { r ->
        poll({ if (r == ChartRange.D1) 60_000L else 300_000L }) {
            buildChart(graph.repository.history(symbol, r), r, graph.repository.marketHoursToday())
        }
    }.stateIn(viewModelScope, sharing, Live())

    val expirations: StateFlow<Live<List<Expiration>>> = poll({ 60L * 60 * 1000 }) {
        graph.repository.expirations(symbol)
    }.stateIn(viewModelScope, sharing, Live())

    val selectedExpiry: StateFlow<LocalDate?> = combine(picked, expirations) { choice, list ->
        choice ?: list.value?.firstOrNull()?.date
    }.distinctUntilChanged().stateIn(viewModelScope, sharing, initialExpiry)

    val chain: StateFlow<Live<OptionChain>> = selectedExpiry.flatMapLatest { date ->
        if (date == null) {
            flowOf(Live<OptionChain>())
        } else {
            poll({ graph.quoteIntervalMs() * CHAIN_SLOWDOWN }) { graph.repository.fullChain(symbol, date) }
        }
    }.stateIn(viewModelScope, sharing, Live())

    /** ATR and earnings for this symbol, which enrich the detail sheet. */
    val context: StateFlow<Live<SymbolContext>> = poll({ 30L * 60 * 1000 }) {
        val q = graph.repository.quote(symbol) ?: throw ApiException.Http(404, "$symbol wasn't found")
        graph.repository.contextFor(q, graph.settings.current.screen.maxDte)
    }.stateIn(viewModelScope, sharing, Live())

    fun pickExpiry(date: LocalDate) {
        picked.value = date
    }

    private companion object {
        const val CHAIN_SLOWDOWN = 4L
    }
}

/** Rows for the list: optional ITM strikes first, then OTM moving away from the money. */
private fun rowsFor(
    chain: OptionChain,
    spot: Double,
    side: PutCall,
    showItm: Boolean,
    atr: Double?,
    nowMs: Long,
): List<ContractMetrics> {
    val all = (if (side == PutCall.PUT) chain.puts else chain.calls).filter { !it.nonStandard }
    val isOtm = { strike: Double -> if (side == PutCall.PUT) strike < spot else strike > spot }
    val byDistance = compareBy<app.cushion.core.model.OptionContract> { kotlin.math.abs(it.strike - spot) }
    val otm = all.filter { isOtm(it.strike) }.sortedWith(byDistance).take(MAX_OTM_ROWS)
    val itm = if (showItm) all.filterNot { isOtm(it.strike) }.sortedWith(byDistance).take(MAX_ITM_ROWS).reversed() else emptyList()
    return (itm + otm).map { metricsFor(it, spot, chain.interestRate, atr, nowMs) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TickerScreen(graph: AppGraph, nav: NavHostController, symbol: String, initialExpiry: LocalDate?) {
    val vm = viewModel(key = "ticker-$symbol") { TickerViewModel(graph, symbol, initialExpiry) }
    val quote by vm.quote.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val expirations by vm.expirations.collectAsStateWithLifecycle()
    val selectedExpiry by vm.selectedExpiry.collectAsStateWithLifecycle()
    val chain by vm.chain.collectAsStateWithLifecycle()
    val side by vm.side.collectAsStateWithLifecycle()
    val showItm by vm.showItm.collectAsStateWithLifecycle()
    val context by vm.context.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val shortlist by graph.shortlist.items.collectAsStateWithLifecycle()
    val added = remember(shortlist) { shortlist.map { it.contractSymbol }.toSet() }
    var open by remember { mutableStateOf<ContractMetrics?>(null) }

    val inUniverse = symbol in settings.universe
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = symbol,
            onBack = { nav.popBackStack() },
            action = {
                CircleButton(
                    icon = if (inUniverse) R.drawable.ic_check else R.drawable.ic_add,
                    label = if (inUniverse) "In your scan list" else "Add to scan list",
                    onClick = {
                        graph.settings.update { s ->
                            s.copy(universe = if (symbol in s.universe) s.universe - symbol else s.universe + symbol)
                        }
                        graph.scans.invalidate()
                    },
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeaderCard(quote, chart, range, onRange = { vm.range.value = it })
            ChainCard(
                symbol = symbol,
                quote = quote.value,
                expirations = expirations,
                selected = selectedExpiry,
                onSelect = vm::pickExpiry,
                side = side,
                onSide = { vm.side.value = it },
                showItm = showItm,
                onToggleItm = { vm.showItm.value = !showItm },
                chain = chain,
                atr = context.value?.atr,
                added = added,
                onOpen = { open = it },
                onAdd = { graph.shortlist.add(it) },
            )
            Box(Modifier.height(4.dp))
        }
    }

    open?.let { metrics ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = sheet, containerColor = Palette.Card) {
            ContractSheet(
                metrics = metrics,
                context = context.value,
                added = metrics.contract.symbol in added,
                onAdd = {
                    graph.shortlist.add(metrics)
                    open = null
                },
            )
        }
    }
}

private val scrubTime = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
private val scrubDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

@Composable
private fun HeaderCard(
    quote: Live<Quote>,
    chart: Live<ChartData>,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
) {
    var scrub by remember { mutableStateOf<Int?>(null) }
    val q = quote.value
    val c = chart.value
    val point = scrub?.let { c?.points?.getOrNull(it) }
    val isDay = range == ChartRange.D1
    val change = when {
        point != null && c != null -> point.price - c.points.first().price
        isDay -> q?.change ?: 0.0
        else -> c?.change ?: 0.0
    }
    val changePct = when {
        point != null && c != null -> c.points.first().price.takeIf { it > 0.0 }?.let { change / it * 100.0 } ?: 0.0
        isDay -> q?.changePct ?: 0.0
        else -> c?.changePct ?: 0.0
    }
    val caption = when {
        point != null -> Instant.ofEpochMilli(point.timeMs).atZone(ZoneId.systemDefault())
            .format(if (range == ChartRange.D1 || range == ChartRange.W1) scrubTime else scrubDate)
        isDay -> "Today"
        else -> range.label
    }

    Card(contentPadding = PaddingValues(top = 20.dp, bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(
                q?.description?.uppercase() ?: " ",
                style = Type.Label,
                color = Palette.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (point?.price ?: q?.last)?.let(Fmt::money) ?: "–",
                style = Type.Display,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (q != null) {
                    MoveTriangle(change, 13.dp)
                    Text(
                        "${Fmt.signedNumber(change)} (${Fmt.percent(changePct)})",
                        style = Type.Row.copy(color = moveColor(change)),
                    )
                    Text(caption, style = Type.Body, color = Palette.Secondary)
                    if (!q.realtime) SmallChip("Delayed")
                }
            }
            quote.error?.let {
                Text(it, style = Type.Small, color = Palette.Orange, modifier = Modifier.padding(top = 4.dp))
            }
        }
        PriceChart(
            chart = c,
            change = if (isDay) (q?.change ?: 0.0) else (c?.change ?: 0.0),
            onScrub = { scrub = it },
            scrubIndex = scrub,
            modifier = Modifier.padding(top = 10.dp),
        )
        RangePicker(range, if (isDay) (q?.change ?: 0.0) else (c?.change ?: 0.0), onRange)
    }
}

@Composable
private fun RangePicker(selected: ChartRange, change: Double, onSelect: (ChartRange) -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .fillMaxWidth()
            .height(38.dp)
            .clip(CircleShape)
            .background(Palette.Raised),
    ) {
        ChartRange.entries.forEach { r ->
            val isSelected = r == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) moveColor(change) else Palette.Raised)
                    .clickable { onSelect(r) },
                contentAlignment = Alignment.Center,
            ) {
                Text(r.label, style = Type.Label, color = if (isSelected) Palette.Ink else Palette.Secondary)
            }
        }
    }
}

@Composable
private fun ChainCard(
    symbol: String,
    quote: Quote?,
    expirations: Live<List<Expiration>>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    side: PutCall,
    onSide: (PutCall) -> Unit,
    showItm: Boolean,
    onToggleItm: () -> Unit,
    chain: Live<OptionChain>,
    atr: Double?,
    added: Set<String>,
    onOpen: (ContractMetrics) -> Unit,
    onAdd: (ContractMetrics) -> Unit,
) {
    Card {
        Text("Option chain", style = Type.CardTitle)
        Text("Chance each strike expires worthless", style = Type.Body, color = Palette.Secondary)
        SegmentedToggle(
            options = listOf("Calls", "Puts"),
            selected = if (side == PutCall.CALL) 0 else 1,
            onSelect = { onSide(if (it == 0) PutCall.CALL else PutCall.PUT) },
            modifier = Modifier.padding(top = 16.dp),
        )
        Text("Expiration", style = Type.Label, color = Palette.Secondary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(expirations.value.orEmpty(), key = { it.date.toString() }) { e ->
                ExpiryChip(e.date, e.date == selected) { onSelect(e.date) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_target), contentDescription = null, tint = Palette.Lime, modifier = Modifier.size(20.dp))
            Text(
                quote?.let { "Stock ${Fmt.money(it.last)}" } ?: "Stock –",
                style = Type.Label,
                color = Palette.Lime,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleItm).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (showItm) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = Palette.Secondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (showItm) "Hide in the money" else "Show in the money",
                    style = Type.Label,
                    color = Palette.Secondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        val loaded = chain.value
        when {
            quote != null && loaded != null -> {
                if (loaded.isDelayed) SmallChip("Delayed quotes", Modifier.padding(vertical = 4.dp))
                val rows = remember(loaded, quote.last, side, showItm, atr) {
                    rowsFor(loaded, quote.last, side, showItm, atr, System.currentTimeMillis())
                }
                if (rows.isEmpty()) {
                    Notice("No strikes listed", "Try another expiration.")
                }
                rows.forEachIndexed { index, metrics ->
                    if (index > 0) HorizontalDivider(color = Palette.Raised, thickness = 1.dp)
                    ContractRow(
                        metrics = metrics,
                        onOpen = { onOpen(metrics) },
                        onAdd = if (side == PutCall.PUT) ({ onAdd(metrics) }) else null,
                        added = metrics.contract.symbol in added,
                    )
                }
                chain.error?.let { Text(it, style = Type.Small, color = Palette.Orange) }
            }
            chain.error != null -> Notice("Couldn't load options", chain.error)
            expirations.error != null -> Notice("Couldn't load expirations", expirations.error)
            expirations.value?.isEmpty() == true -> Notice("No options for $symbol")
            else -> Notice("Loading chain")
        }
    }
}

@Composable
private fun ExpiryChip(date: LocalDate, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Palette.Lime else Palette.Ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(Fmt.monthDay(date), style = Type.Row, color = if (selected) Palette.Ink else Palette.Text)
        Text(Fmt.weekday(date), style = Type.Small, color = if (selected) Palette.Ink else Palette.Secondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContractSheet(metrics: ContractMetrics, context: SymbolContext?, added: Boolean, onAdd: () -> Unit) {
    val c = metrics.contract
    val warnings = buildList {
        if (context != null) {
            if (c.putCall == PutCall.PUT && isSelloff(context)) add("Selloff")
            val event = context.earnings.next
            if (event != null && earningsBeforeExpiry(event, c.expiration)) add("Earnings ${Fmt.monthDay(event.date)}")
            if (context.isLeveraged) add("${Fmt.decimal(context.leverage, 0)}× ETF")
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenGutter)
            .padding(bottom = 16.dp)
            .navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${c.underlying} ${Fmt.strike(c.strike)} ${sideLabel(c.putCall)}", style = Type.CardTitle)
                Text("${Fmt.monthDay(c.expiration)} · ${c.dte} days", style = Type.Body, color = Palette.Secondary)
            }
            ProbabilityRing(metrics.chanceWorthless)
            PremiumPill(Fmt.money(metrics.premium))
        }
        if (warnings.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                warnings.forEach { SmallChip(it, color = Palette.Orange) }
            }
        }
        Box(Modifier.height(10.dp))
        StatRow("Bid / Ask", "${Fmt.money(c.bid)} / ${Fmt.money(c.ask)}")
        StatRow("Breakeven", Fmt.money(metrics.breakeven))
        if (c.putCall == PutCall.PUT) {
            StatRow("Collateral", Fmt.wholeMoney(metrics.collateral))
            StatRow("Yield", "${Fmt.percent(metrics.yieldPct)} · ${Fmt.oneDecimalPercent(metrics.annualYieldPct)} /yr")
            StatRow("Cushion", metrics.cushionSigma?.let { "${Fmt.sigma(it)} · ${Fmt.decimal(metrics.cushionAtr ?: 0.0)} ATR" } ?: "–")
        }
        StatRow("Delta", metrics.absDelta?.let { Fmt.decimal(it) } ?: "–")
        StatRow("Theta / day", c.theta?.let { Fmt.money(it) } ?: "–")
        StatRow("θ/Δ breakeven", metrics.thetaDeltaBreakeven?.let { "${Fmt.money(it)} /day" } ?: "–")
        StatRow("Implied vol", c.iv?.let { Fmt.oneDecimalPercent(it * 100.0) } ?: "–")
        StatRow("Open interest", Fmt.compact(c.openInterest))
        StatRow("Volume", Fmt.compact(c.volume))
        if (c.putCall == PutCall.PUT) {
            PrimaryButton(if (added) "On your shortlist" else "Add to shortlist", onAdd, Modifier.padding(top = 14.dp), enabled = !added)
        }
    }
}
