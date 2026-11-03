package app.cushion.core.repo

import app.cushion.core.earnings.EarningsSnapshot
import app.cushion.core.earnings.NasdaqEarnings
import app.cushion.core.earnings.earningsWeek
import app.cushion.core.earnings.sortedForDisplay
import app.cushion.core.math.atr
import app.cushion.core.math.atrPercentile
import app.cushion.core.math.realizedVol
import app.cushion.core.math.sma
import app.cushion.core.model.EarningsEvent
import app.cushion.core.model.Expiration
import app.cushion.core.model.Instrument
import app.cushion.core.model.MarketHours
import app.cushion.core.model.OptionChain
import app.cushion.core.model.PriceHistory
import app.cushion.core.model.Quote
import app.cushion.core.net.ApiException
import app.cushion.core.schwab.ChartRange
import app.cushion.core.schwab.SchwabClient
import app.cushion.core.screen.EarningsInfo
import app.cushion.core.screen.Idea
import app.cushion.core.screen.Regime
import app.cushion.core.screen.ScreenConfig
import app.cushion.core.screen.Screener
import app.cushion.core.screen.SymbolContext
import app.cushion.core.screen.Universe
import app.cushion.core.screen.sortedFor
import app.cushion.core.screen.IdeaSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class ScanResult(
    val ideas: List<Idea>,
    val vix: Double?,
    val regime: Regime,
    val scannedAtMs: Long,
    /** Symbols that returned no idea, and why. */
    val rejections: Map<String, String>,
    /** Symbols whose data couldn't be fetched. */
    val failures: Map<String, String>,
    val earningsAvailable: Boolean,
) {
    /** Screened ideas for the Browse chips, biggest dollar premium first, as in the mock. */
    val topPremiums: List<Idea>
        get() = ideas.filterNot { it.blocked }.sortedFor(IdeaSort.PREMIUM)
}

/** Everything the screens read. Holds the caches and the scan orchestration. */
class MarketRepository(
    private val client: SchwabClient,
    private val earnings: NasdaqEarnings,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Timed<T>(val value: T, val atMs: Long)

    private val sparklines = ConcurrentHashMap<String, Timed<PriceHistory>>()
    private val dailies = ConcurrentHashMap<String, Timed<PriceHistory>>()
    private val hoursCache = ConcurrentHashMap<LocalDate, MarketHours>()
    @Volatile private var hoursRetryAtMs = 0L
    private val earningsMutex = Mutex()
    private var earningsCache: Timed<EarningsSnapshot>? = null
    private var earningsCacheTo: LocalDate? = null

    suspend fun quotes(symbols: List<String>): Map<String, Quote> = client.quotes(symbols)

    suspend fun quote(symbol: String): Quote? = client.quotes(listOf(symbol))[symbol]

    suspend fun history(symbol: String, range: ChartRange): PriceHistory = client.history(symbol, range)

    /** The intraday line on a Browse card. Slow-moving, so held for five minutes. */
    suspend fun sparkline(symbol: String): PriceHistory {
        sparklines[symbol]?.takeIf { nowMs() - it.atMs < SPARKLINE_TTL_MS }?.let { return it.value }
        val fresh = client.history(symbol, ChartRange.D1)
        sparklines[symbol] = Timed(fresh, nowMs())
        return fresh
    }

    private suspend fun daily(symbol: String): PriceHistory {
        dailies[symbol]?.takeIf { marketDate(it.atMs) == marketDate(nowMs()) }?.let { return it.value }
        val fresh = client.dailyHistory(symbol)
        dailies[symbol] = Timed(fresh, nowMs())
        return fresh
    }

    suspend fun expirations(symbol: String): List<Expiration> {
        val today = marketDate(nowMs())
        return client.expirations(symbol).filter { !it.date.isBefore(today) }
    }

    suspend fun fullChain(symbol: String, expiration: LocalDate): OptionChain = client.fullChain(symbol, expiration)

    suspend fun search(text: String): List<Instrument> {
        val q = text.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        // Regex metacharacters come from user typing, so only plain tickers are matched as a prefix.
        val safe = q.filter { it.isLetterOrDigit() || it == '.' }
        val bySymbol = if (safe.isEmpty()) emptyList() else client.instruments("$safe.*", "symbol-regex")
        val byName = if (q.length >= 3) client.instruments(q, "desc-search") else emptyList()
        return (bySymbol.sortedBy { it.symbol.length } + byName)
            .filter { it.assetType == null || it.assetType == "EQUITY" || it.assetType == "ETF" }
            .distinctBy { it.symbol }
            .take(MAX_SEARCH_RESULTS)
    }

    /** Today's hours. Falls back to the standard session if Schwab can't say. */
    suspend fun marketHoursToday(): MarketHours? {
        val today = marketDate(nowMs())
        hoursCache[today]?.let { return it }
        // Every poll asks for this, so a failure must not double the polling traffic.
        if (nowMs() < hoursRetryAtMs) return null
        val hours = try {
            client.marketHours(today)
        } catch (e: ApiException.AuthRequired) {
            throw e
        } catch (e: ApiException) {
            null
        }
        if (hours != null) hoursCache[today] = hours else hoursRetryAtMs = nowMs() + HOURS_RETRY_MS
        return hours
    }

    /** Everything worth showing under "Earnings this week". */
    suspend fun earningsThisWeek(): List<EarningsEvent> {
        val (from, to) = earningsWeek(marketDate(nowMs()))
        return earnings.range(from, to).events.sortedForDisplay()
    }

    private suspend fun earningsThrough(to: LocalDate): EarningsSnapshot = earningsMutex.withLock {
        val cached = earningsCache
        if (cached != null && earningsCacheTo?.isBefore(to) == false) {
            // A complete calendar lasts hours. A partial one gets retried soon.
            val complete = cached.value.coveredThrough?.isBefore(to) == false
            val ttl = if (complete) EARNINGS_TTL_MS else EARNINGS_RETRY_MS
            if (nowMs() - cached.atMs < ttl) return@withLock cached.value
        }
        val snapshot = earnings.range(marketDate(nowMs()), to)
        earningsCache = Timed(snapshot, nowMs())
        earningsCacheTo = to
        snapshot
    }

    private fun contextOf(quote: Quote, daily: PriceHistory, earnings: EarningsSnapshot): SymbolContext {
        val candles = daily.candles
        val closes = candles.map { it.close }
        // Use the completed bar five sessions ago, relative to the live price.
        val base = closes.getOrNull(closes.size - 6)
        return SymbolContext(
            quote = quote,
            atr = atr(candles),
            atrPercentile = atrPercentile(candles),
            realizedVol = realizedVol(candles),
            sma20 = sma(candles, 20),
            return5dPct = base?.takeIf { it > 0.0 }?.let { (quote.last - it) / it * 100.0 },
            leverage = Universe.leverageOf(quote.symbol, quote.leverageFactor),
            earnings = EarningsInfo(
                next = earnings.events.filter { it.symbol == quote.symbol }.minByOrNull { it.date },
                coveredThrough = earnings.coveredThrough,
            ),
            group = Universe.groupOf(quote.symbol),
        )
    }

    /** ATR and earnings for one symbol, for the Ticker screen's detail sheet. */
    suspend fun contextFor(quote: Quote, maxDte: Int): SymbolContext {
        val today = marketDate(nowMs())
        return contextOf(quote, daily(quote.symbol), earningsThrough(today.plusDays(maxDte.toLong())))
    }

    suspend fun scan(
        universe: List<String>,
        config: ScreenConfig,
        accountValue: Double?,
        perNameCapPct: Double,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = coroutineScope {
        val symbols = universe.distinct()
        val today = marketDate(nowMs())
        val allQuotes = client.quotes(symbols + VIX_SYMBOL)
        val vix = allQuotes[VIX_SYMBOL]?.last?.takeIf { it > 0.0 }
        // Without VIX, assume the tighter of the two common regimes rather than a calm one.
        val regime = vix?.let(Regime::fromVix) ?: Regime.ELEVATED
        val calendar = earningsThrough(today.plusDays(config.maxDte.toLong()))
        val screener = Screener(config, regime, accountValue, perNameCapPct, nowMs)

        val gate = Semaphore(SCAN_CONCURRENCY)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = symbols.map { symbol ->
            async {
                gate.withPermit {
                    try {
                        val quote = allQuotes[symbol] ?: error("No quote")
                        val chain = client.putChain(
                            symbol,
                            today.plusDays(config.minDte.toLong()),
                            today.plusDays(config.maxDte.toLong()),
                        )
                        val outcome = screener.screen(contextOf(quote, daily(symbol), calendar), chain)
                        Triple(symbol, outcome, null as String?)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException.AuthRequired) {
                        throw e
                    } catch (e: Exception) {
                        Triple(symbol, null, e.message ?: "Failed to load")
                    } finally {
                        onProgress(done.incrementAndGet(), symbols.size)
                    }
                }
            }
        }.awaitAll()

        ScanResult(
            ideas = results.flatMap { it.second?.ideas.orEmpty() }.sortedByDescending { it.score },
            vix = vix,
            regime = regime,
            scannedAtMs = nowMs(),
            rejections = results.mapNotNull { (s, o, _) -> o?.rejection?.let { s to it } }.toMap(),
            failures = results.mapNotNull { (s, _, f) -> f?.let { s to it } }.toMap(),
            earningsAvailable = calendar.coveredThrough != null,
        )
    }

    private companion object {
        const val VIX_SYMBOL = "\$VIX"
        const val SCAN_CONCURRENCY = 3
        const val SPARKLINE_TTL_MS = 5L * 60 * 1000
        const val EARNINGS_TTL_MS = 6L * 60 * 60 * 1000
        const val EARNINGS_RETRY_MS = 5L * 60 * 1000
        const val MAX_SEARCH_RESULTS = 20
        const val HOURS_RETRY_MS = 5L * 60 * 1000
    }
}
