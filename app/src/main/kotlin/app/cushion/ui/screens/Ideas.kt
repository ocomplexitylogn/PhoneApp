package app.cushion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import app.cushion.R
import app.cushion.core.format.Fmt
import app.cushion.core.repo.ScanResult
import app.cushion.core.repo.ScanState
import app.cushion.core.screen.Idea
import app.cushion.core.screen.IdeaFlag
import app.cushion.core.screen.IdeaSort
import app.cushion.core.screen.sortedFor
import app.cushion.data.AppGraph
import app.cushion.ui.Routes
import app.cushion.ui.components.Card
import app.cushion.ui.components.CircleButton
import app.cushion.ui.components.ContractRow
import app.cushion.ui.components.Notice
import app.cushion.ui.components.ScreenGutter
import app.cushion.ui.components.SmallChip
import app.cushion.ui.components.TopBar
import app.cushion.ui.components.sideLabel
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Keeps the shared scan fresh while the screen is visible. */
@Composable
fun KeepScanFresh(graph: AppGraph) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                graph.scans.refresh()
                delay(60_000)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeasScreen(graph: AppGraph, nav: NavHostController) {
    KeepScanFresh(graph)
    val scan by graph.scans.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val shortlist by graph.shortlist.items.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(IdeaSort.SCORE) }
    val added = remember(shortlist) { shortlist.map { it.contractSymbol }.toSet() }

    val result = scan.result
    val allIdeas = result?.ideas.orEmpty()
    val blockedCount = allIdeas.count { it.blocked }
    val visible = remember(allIdeas, sort, settings.showBlocked) {
        allIdeas.filter { settings.showBlocked || !it.blocked }.sortedFor(sort)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Ideas",
            action = { CircleButton(R.drawable.ic_search, "Browse and search", { nav.navigate(Routes.BROWSE) }) },
        )
        PullToRefreshBox(
            isRefreshing = scan.running && result != null,
            onRefresh = { graph.scans.refresh(force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (result != null) {
                    item(key = "regime") { RegimeCard(result, scan) }
                    item(key = "sort") {
                        SortBar(
                            sort = sort,
                            onSort = { sort = it },
                            showBlocked = settings.showBlocked,
                            blockedCount = blockedCount,
                            onToggleBlocked = { graph.settings.update { s -> s.copy(showBlocked = !s.showBlocked) } },
                        )
                    }
                }
                items(visible, key = { it.contract.symbol }) { idea ->
                    IdeaCard(
                        idea = idea,
                        added = idea.contract.symbol in added,
                        onOpen = { nav.navigate(Routes.ticker(idea.contract.underlying, idea.contract.expiration)) },
                        onAdd = { graph.shortlist.add(idea.metrics) },
                    )
                }
                if (visible.isEmpty()) {
                    item(key = "empty") { EmptyIdeas(scan, blockedCount, onRetry = { graph.scans.refresh(force = true) }) }
                }
                if (result != null && result.failures.isNotEmpty()) {
                    item(key = "failures") {
                        Text(
                            "Couldn't load ${result.failures.keys.sorted().joinToString(", ")}",
                            style = Type.Small,
                            color = Palette.Muted,
                        )
                    }
                }
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

@Composable
private fun RegimeCard(result: ScanResult, scan: ScanState) {
    Card(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.vix?.let { "VIX ${Fmt.decimal(it)}" } ?: "VIX unavailable", style = Type.Label)
            SmallChip(result.regime.label, color = Palette.Lime, background = Palette.PillFill)
            Box(Modifier.weight(1f))
            Text("Target ${Fmt.decimal(result.regime.targetDelta)}Δ", style = Type.Small, color = Palette.Secondary)
        }
        val time = Instant.ofEpochMilli(result.scannedAtMs).atZone(ZoneId.systemDefault()).format(timeFormat)
        val status = if (scan.running) "Scanning ${scan.done} of ${scan.total}" else "Updated $time"
        val calendar = if (result.earningsAvailable) "" else " · Earnings calendar unavailable"
        Text(status + calendar, style = Type.Tiny, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SortBar(
    sort: IdeaSort,
    onSort: (IdeaSort) -> Unit,
    showBlocked: Boolean,
    blockedCount: Int,
    onToggleBlocked: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IdeaSort.entries.forEach { option ->
            FilterPill(option.label, option == sort) { onSort(option) }
        }
        if (blockedCount > 0) {
            FilterPill("Blocked $blockedCount", showBlocked, onToggleBlocked)
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Palette.Lime else Palette.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = Type.SmallStrong, color = if (selected) Palette.Ink else Palette.Secondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdeaCard(idea: Idea, added: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    val m = idea.metrics
    val c = idea.contract
    Card(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 14.dp)) {
        ContractRow(
            metrics = m,
            onOpen = onOpen,
            onAdd = onAdd,
            added = added,
            title = c.underlying,
            subtitle = "${Fmt.strike(c.strike)} ${sideLabel(c.putCall)} · ${Fmt.monthDay(c.expiration)}",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("To strike", Fmt.percent(m.distancePct))
            Metric("Yield /yr", Fmt.oneDecimalPercent(m.annualYieldPct))
            Metric("Cushion", m.cushionSigma?.let(Fmt::sigma) ?: "–")
            Metric("DTE", c.dte.toString())
        }
        if (idea.flags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                idea.flags.forEach { flag -> FlagChip(flag) }
            }
        }
    }
}

@Composable
fun FlagChip(flag: IdeaFlag) {
    val warn = flag.blocks || flag == IdeaFlag.OVER_NAME_CAP || flag == IdeaFlag.EARNINGS_UNVERIFIED
    SmallChip(flag.label, color = if (warn) Palette.Orange else Palette.Secondary)
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = Type.Tiny, color = Palette.Muted)
        Text(value, style = Type.SmallStrong)
    }
}

@Composable
private fun EmptyIdeas(scan: ScanState, blockedCount: Int, onRetry: () -> Unit) {
    val result = scan.result
    when {
        scan.running && scan.total > 0 -> Notice("Scanning ${scan.done} of ${scan.total}", "Checking every name against your rules.")
        scan.running -> Notice("Starting scan")
        scan.error != null -> Column(Modifier.clickable(onClick = onRetry)) {
            Notice("Couldn't run the scan", "${scan.error} Tap to try again.")
        }
        result != null -> {
            val reasons = result.rejections.values.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString("\n") { "${it.value} × ${it.key}" }
            val blocked = if (blockedCount > 0) "$blockedCount blocked by earnings or a selloff.\n" else ""
            Notice("Nothing passes your rules right now", (blocked + reasons).ifBlank { null })
        }
        else -> Notice("No scan yet", "Pull down to run one.")
    }
}
