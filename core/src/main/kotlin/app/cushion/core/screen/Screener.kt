package app.cushion.core.screen

import app.cushion.core.math.BlackScholes
import app.cushion.core.model.EarningsEvent
import app.cushion.core.model.EarningsTiming
import app.cushion.core.model.OptionChain
import app.cushion.core.model.OptionContract
import app.cushion.core.model.PutCall
import app.cushion.core.model.Quote
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

val MARKET_ZONE: ZoneId = ZoneId.of("America/New_York")

data class ScreenConfig(
    val minDte: Int = 7,
    val maxDte: Int = 45,
    val deltaTolerance: Double = 0.04,
    /** Minimum (spot - breakeven) / (ATR × √DTE). */
    val minCushionSigma: Double = 0.75,
    /** The same floor for leveraged ETFs, which need more room. */
    val minCushionSigmaLeveraged: Double = 1.0,
    val minOpenInterest: Long = 100,
    val maxSpreadPct: Double = 0.15,
    val minBid: Double = 0.05,
)

/** Numbers for one contract. ATR-based fields are null until daily history has loaded. */
data class ContractMetrics(
    val contract: OptionContract,
    val spot: Double,
    val premium: Double,
    /** Percent from spot to strike, signed: -6.82 means 6.82% below spot. */
    val distancePct: Double,
    val breakeven: Double,
    val collateral: Double,
    val yieldPct: Double,
    val annualYieldPct: Double,
    /** 0..1. N(d2) when IV is available, otherwise 1 - |delta|. */
    val chanceWorthless: Double?,
    /** Unsigned delta, modelled from IV if Schwab didn't send one. */
    val absDelta: Double?,
    /** Daily adverse move, in dollars, that one day of theta pays for. */
    val thetaDeltaBreakeven: Double?,
    /** (spot - breakeven) / ATR. */
    val cushionAtr: Double?,
    /** (spot - breakeven) / (ATR × √DTE). */
    val cushionSigma: Double?,
)

fun yearsToExpiry(expiration: LocalDate, nowMs: Long): Double {
    val close = expiration.atTime(LocalTime.of(16, 0)).atZone(MARKET_ZONE).toInstant()
    val minutes = Duration.between(Instant.ofEpochMilli(nowMs), close).toMinutes()
    return minutes.coerceAtLeast(1L) / (365.0 * 24.0 * 60.0)
}

fun metricsFor(
    contract: OptionContract,
    spot: Double,
    riskFreeRate: Double,
    atr: Double?,
    nowMs: Long,
): ContractMetrics {
    val isPut = contract.putCall == PutCall.PUT
    val premium = contract.mid
    val t = yearsToExpiry(contract.expiration, nowMs)
    val modelled = contract.iv?.let { BlackScholes.delta(contract.putCall, spot, contract.strike, t, riskFreeRate, it) }
    val absDelta = (contract.delta?.takeIf { it != 0.0 } ?: modelled)?.let(::abs)
    val chance = contract.iv
        ?.let { BlackScholes.probExpiresWorthless(contract.putCall, spot, contract.strike, t, riskFreeRate, it) }
        ?: absDelta?.let { 1.0 - it }
    val breakeven = if (isPut) contract.strike - premium else contract.strike + premium
    val days = contract.dte.coerceAtLeast(1)
    val yieldPct = if (contract.strike > 0.0) premium / contract.strike * 100.0 else 0.0
    val room = if (isPut) spot - breakeven else breakeven - spot
    return ContractMetrics(
        contract = contract,
        spot = spot,
        premium = premium,
        distancePct = if (spot > 0.0) (contract.strike - spot) / spot * 100.0 else 0.0,
        breakeven = breakeven,
        collateral = contract.strike * contract.multiplier,
        yieldPct = yieldPct,
        annualYieldPct = yieldPct * 365.0 / days,
        chanceWorthless = chance,
        absDelta = absDelta,
        thetaDeltaBreakeven = run {
            val theta = contract.theta
            if (theta == null || absDelta == null || absDelta <= 0.0) null else abs(theta) / absDelta
        },
        cushionAtr = atr?.takeIf { it > 0.0 }?.let { room / it },
        cushionSigma = atr?.takeIf { it > 0.0 }?.let { room / (it * sqrt(days.toDouble())) },
    )
}

/** Whatever is known about a symbol's earnings dates at scan time. */
data class EarningsInfo(
    val next: EarningsEvent?,
    /** The calendar was read through this date. Null if it couldn't be read at all. */
    val coveredThrough: LocalDate?,
)

data class SymbolContext(
    val quote: Quote,
    val atr: Double?,
    val atrPercentile: Double?,
    val realizedVol: Double?,
    val sma20: Double?,
    val return5dPct: Double?,
    val leverage: Double,
    val earnings: EarningsInfo,
    val group: String,
) {
    val isLeveraged: Boolean get() = leverage > 1.0
}

enum class IdeaFlag(val label: String, val blocks: Boolean) {
    EARNINGS_BEFORE_EXPIRY("Earnings before expiry", true),
    SELLOFF("Selloff", true),
    EARNINGS_UNVERIFIED("Earnings unverified", false),
    LEVERAGED("Leveraged ETF", false),
    HALF_SIZE("Half size", false),
    OVER_NAME_CAP("Over name cap", false),
}

data class Idea(
    val metrics: ContractMetrics,
    val group: String,
    val regime: Regime,
    val atr: Double?,
    val atrPercentile: Double?,
    val ivOverRv: Double?,
    val score: Double,
    val flags: Set<IdeaFlag>,
    val earningsDate: LocalDate?,
    /** Contracts that fit the per-name cap in this regime. Null without an account size. */
    val maxContracts: Int?,
) {
    val contract: OptionContract get() = metrics.contract
    val blocked: Boolean get() = flags.any { it.blocks }
}

/**
 * True on a sharp single-day drop (more than 1.5 ATR) or an established slide (below
 * the 20-day average and down more than 2 ATR over five sessions). Selling puts into
 * either is how a routine trade becomes an assignment at the highs.
 */
fun isSelloff(ctx: SymbolContext): Boolean {
    val atr = ctx.atr?.takeIf { it > 0.0 } ?: return false
    val sharpDrop = ctx.quote.change <= -1.5 * atr
    val spot = ctx.quote.last
    val slide = ctx.sma20 != null && ctx.return5dPct != null &&
        spot < ctx.sma20 && spot * ctx.return5dPct / 100.0 <= -2.0 * atr
    return sharpDrop || slide
}

/** True if the report lands while the option is still alive. */
fun earningsBeforeExpiry(event: EarningsEvent, expiration: LocalDate): Boolean =
    event.date.isBefore(expiration) ||
        (event.date == expiration && event.timing != EarningsTiming.AFTER_CLOSE)

data class SymbolScreen(
    val ideas: List<Idea>,
    /** Why this symbol produced nothing, for the empty state. */
    val rejection: String?,
)

class Screener(
    private val config: ScreenConfig,
    private val regime: Regime,
    private val accountValue: Double?,
    private val perNameCapPct: Double,
    private val nowMs: () -> Long,
) {
    /** The strikes in this regime's delta band that clear liquidity and cushion rules. */
    fun screen(ctx: SymbolContext, chain: OptionChain): SymbolScreen {
        val spot = ctx.quote.last.takeIf { it > 0.0 } ?: chain.underlyingPrice
        val low = regime.targetDelta - config.deltaTolerance
        val high = regime.targetDelta + config.deltaTolerance
        val minCushion = if (ctx.isLeveraged) config.minCushionSigmaLeveraged else config.minCushionSigma

        val inWindow = chain.puts.filter {
            !it.nonStandard && !it.inTheMoney && it.strike < spot && it.dte in config.minDte..config.maxDte
        }
        val metrics = inWindow.map { metricsFor(it, spot, chain.interestRate, ctx.atr, nowMs()) }
        val inBand = metrics.filter { it.absDelta != null && it.absDelta in low..high }
        val liquid = inBand.filter { isLiquid(it.contract) }
        val cushioned = liquid.filter { (it.cushionSigma ?: 0.0) >= minCushion }

        val ideas = cushioned
            .map { toIdea(it, ctx) }
            .groupBy { it.contract.expiration }
            .values
            .map { sameExpiry -> sameExpiry.maxBy { it.score } }
            .sortedByDescending { it.score }
            .take(MAX_IDEAS_PER_SYMBOL)

        val rejection = when {
            ideas.isNotEmpty() -> null
            inWindow.isEmpty() -> "No puts in the ${config.minDte}–${config.maxDte} day window"
            inBand.isEmpty() -> "No strike near ${"%.2f".format(regime.targetDelta)} delta"
            liquid.isEmpty() -> "Strikes near target are too illiquid"
            ctx.atr == null -> "Not enough price history for ATR"
            else -> "Cushion is under ${"%.2f".format(minCushion)}σ"
        }
        return SymbolScreen(ideas, rejection)
    }

    private fun isLiquid(c: OptionContract) =
        c.bid >= config.minBid && c.openInterest >= config.minOpenInterest && c.spreadPct <= config.maxSpreadPct

    private fun toIdea(m: ContractMetrics, ctx: SymbolContext): Idea {
        val ivOverRv = run {
            val iv = m.contract.iv
            val rv = ctx.realizedVol
            if (iv == null || rv == null || rv <= 0.0) null else iv / rv
        }
        val chance = m.chanceWorthless ?: 0.0
        val score = m.annualYieldPct * chance * (ivOverRv ?: 1.0).coerceIn(0.5, 1.5)

        val flags = LinkedHashSet<IdeaFlag>()
        val event = ctx.earnings.next
        val earningsHit = event != null && earningsBeforeExpiry(event, m.contract.expiration)
        if (earningsHit) flags += IdeaFlag.EARNINGS_BEFORE_EXPIRY
        if (isSelloff(ctx)) flags += IdeaFlag.SELLOFF
        val covered = ctx.earnings.coveredThrough
        if (!ctx.quote.isEtf && !earningsHit && (covered == null || covered.isBefore(m.contract.expiration))) {
            flags += IdeaFlag.EARNINGS_UNVERIFIED
        }
        if (ctx.isLeveraged) flags += IdeaFlag.LEVERAGED
        if (regime.sizeFactor < 1.0) flags += IdeaFlag.HALF_SIZE

        val maxContracts = accountValue?.takeIf { it > 0.0 }?.let { nlv ->
            floor(nlv * perNameCapPct / 100.0 * regime.sizeFactor / m.collateral).toInt()
        }
        if (maxContracts == 0) flags += IdeaFlag.OVER_NAME_CAP

        return Idea(
            metrics = m,
            group = ctx.group,
            regime = regime,
            atr = ctx.atr,
            atrPercentile = ctx.atrPercentile,
            ivOverRv = ivOverRv,
            score = score,
            flags = flags,
            earningsDate = event?.date,
            maxContracts = maxContracts,
        )
    }

    private companion object {
        const val MAX_IDEAS_PER_SYMBOL = 2
    }
}

enum class IdeaSort(val label: String) {
    SCORE("Best"),
    YIELD("Yield"),
    CHANCE("Chance"),
    CUSHION("Cushion"),
    PREMIUM("Premium"),
}

fun List<Idea>.sortedFor(sort: IdeaSort): List<Idea> = when (sort) {
    IdeaSort.SCORE -> sortedByDescending { it.score }
    IdeaSort.YIELD -> sortedByDescending { it.metrics.annualYieldPct }
    IdeaSort.CHANCE -> sortedByDescending { it.metrics.chanceWorthless ?: 0.0 }
    IdeaSort.CUSHION -> sortedByDescending { it.metrics.cushionSigma ?: 0.0 }
    IdeaSort.PREMIUM -> sortedByDescending { it.metrics.premium }
}
