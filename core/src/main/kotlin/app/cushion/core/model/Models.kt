package app.cushion.core.model

import java.time.LocalDate

enum class PutCall { PUT, CALL }

/** A live quote for an equity, ETF, index or option. Prices are per share. */
data class Quote(
    val symbol: String,
    val description: String,
    val last: Double,
    val change: Double,
    val changePct: Double,
    val bid: Double?,
    val ask: Double?,
    val mark: Double?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val previousClose: Double?,
    val volume: Long?,
    val week52High: Double?,
    val week52Low: Double?,
    val realtime: Boolean,
    val quoteTimeMs: Long?,
    val assetMainType: String?,
    val assetSubType: String?,
    /** Schwab's `fundLeverageFactor`; absolute value, e.g. 3.0 for SOXL. */
    val leverageFactor: Double?,
    /** Options only: signed delta, when Schwab supplies it. */
    val delta: Double? = null,
) {
    val isEtf: Boolean get() = assetSubType.equals("ETF", ignoreCase = true)
}

data class Candle(
    val timeMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

data class PriceHistory(
    val symbol: String,
    val candles: List<Candle>,
    val previousClose: Double?,
)

data class OptionContract(
    /** OSI symbol as Schwab spells it, e.g. `SOXL  261106P00065000`. */
    val symbol: String,
    val underlying: String,
    val putCall: PutCall,
    val strike: Double,
    val expiration: LocalDate,
    val dte: Int,
    val bid: Double,
    val ask: Double,
    val last: Double?,
    val mark: Double?,
    val volume: Long,
    val openInterest: Long,
    /** Implied volatility as a fraction: 1.31 means 131%. */
    val iv: Double?,
    val delta: Double?,
    val gamma: Double?,
    val theta: Double?,
    val vega: Double?,
    val inTheMoney: Boolean,
    val multiplier: Int,
    val nonStandard: Boolean,
    val quoteTimeMs: Long?,
) {
    /** Mid price: the mark Schwab reports, else the bid/ask midpoint. */
    val mid: Double
        get() = mark?.takeIf { it > 0.0 } ?: ((bid + ask) / 2.0)

    /** Bid/ask width as a fraction of mid. Infinite when the contract has no market. */
    val spreadPct: Double
        get() = if (mid > 0.0 && ask >= bid) (ask - bid) / mid else Double.POSITIVE_INFINITY
}

data class OptionChain(
    val symbol: String,
    val status: String,
    val underlyingPrice: Double,
    /** Risk-free rate as a fraction. */
    val interestRate: Double,
    val isDelayed: Boolean,
    val puts: List<OptionContract>,
    val calls: List<OptionContract>,
)

data class Expiration(
    val date: LocalDate,
    val dte: Int,
    val type: String?,
    val standard: Boolean,
)

/** One market's hours for one date. Times are epoch millis; null when closed. */
data class MarketHours(
    val date: LocalDate,
    val isOpen: Boolean,
    val preStartMs: Long?,
    val regularStartMs: Long?,
    val regularEndMs: Long?,
    val postEndMs: Long?,
)

data class Instrument(
    val symbol: String,
    val description: String,
    val assetType: String?,
)

enum class EarningsTiming { BEFORE_OPEN, AFTER_CLOSE, UNKNOWN }

data class EarningsEvent(
    val symbol: String,
    val name: String?,
    val date: LocalDate,
    val timing: EarningsTiming,
    val marketCapUsd: Double?,
    val epsForecast: String?,
)
