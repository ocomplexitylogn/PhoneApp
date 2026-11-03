package app.cushion.core.repo

import app.cushion.core.model.Candle
import app.cushion.core.model.MarketHours
import app.cushion.core.model.PriceHistory
import app.cushion.core.schwab.ChartRange
import app.cushion.core.screen.MARKET_ZONE
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class MarketPhase(val label: String) {
    PRE("Pre-market"),
    OPEN("Market open"),
    POST("After hours"),
    CLOSED("Market closed"),
}

private fun et(date: LocalDate, time: LocalTime, zone: ZoneId = MARKET_ZONE) =
    date.atTime(time).atZone(zone).toInstant().toEpochMilli()

private fun defaultHours(date: LocalDate): MarketHours {
    val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    return if (weekend) {
        MarketHours(date, false, null, null, null, null)
    } else {
        MarketHours(
            date = date,
            isOpen = true,
            preStartMs = et(date, LocalTime.of(4, 0)),
            regularStartMs = et(date, LocalTime.of(9, 30)),
            regularEndMs = et(date, LocalTime.of(16, 0)),
            postEndMs = et(date, LocalTime.of(20, 0)),
        )
    }
}

fun marketDate(nowMs: Long): LocalDate = Instant.ofEpochMilli(nowMs).atZone(MARKET_ZONE).toLocalDate()

fun marketPhase(nowMs: Long, hours: MarketHours?): MarketPhase {
    val today = marketDate(nowMs)
    val h = hours?.takeIf { it.date == today } ?: defaultHours(today)
    val open = h.regularStartMs
    val close = h.regularEndMs
    if (!h.isOpen || open == null || close == null) return MarketPhase.CLOSED
    return when {
        nowMs < (h.preStartMs ?: open) -> MarketPhase.CLOSED
        nowMs < open -> MarketPhase.PRE
        nowMs < close -> MarketPhase.OPEN
        nowMs < (h.postEndMs ?: close) -> MarketPhase.POST
        else -> MarketPhase.CLOSED
    }
}

/** How often visible quotes refresh, in step with how fast they can actually move. */
fun pollIntervalMs(phase: MarketPhase): Long = when (phase) {
    MarketPhase.OPEN -> 5_000L
    MarketPhase.PRE, MarketPhase.POST -> 15_000L
    MarketPhase.CLOSED -> 60_000L
}

enum class Session { PRE, REGULAR, POST }

data class ChartPoint(val timeMs: Long, val price: Double, val session: Session)

data class ChartData(
    val points: List<ChartPoint>,
    /** Change from the first point to the last, for ranges longer than a day. */
    val change: Double,
    val changePct: Double,
) {
    val min: Double get() = points.minOfOrNull { it.price } ?: 0.0
    val max: Double get() = points.maxOfOrNull { it.price } ?: 0.0
    val isDown: Boolean get() = change < 0.0
}

/**
 * Turns candles into chart points. A 1D chart keeps only the latest session's date and
 * tags each point, so pre- and post-market draw grey as they do in the mock.
 */
fun buildChart(history: PriceHistory, range: ChartRange, hours: MarketHours?): ChartData {
    val candles: List<Candle> = history.candles
    if (candles.isEmpty()) return ChartData(emptyList(), 0.0, 0.0)
    val points = if (range == ChartRange.D1) {
        val date = Instant.ofEpochMilli(candles.last().timeMs).atZone(MARKET_ZONE).toLocalDate()
        val h = hours?.takeIf { it.date == date && it.regularStartMs != null } ?: standardSession(date)
        val open = h.regularStartMs ?: Long.MIN_VALUE
        val close = h.regularEndMs ?: Long.MAX_VALUE
        candles
            .filter { Instant.ofEpochMilli(it.timeMs).atZone(MARKET_ZONE).toLocalDate() == date }
            .map {
                ChartPoint(
                    timeMs = it.timeMs,
                    price = it.close,
                    session = when {
                        it.timeMs < open -> Session.PRE
                        it.timeMs >= close -> Session.POST
                        else -> Session.REGULAR
                    },
                )
            }
    } else {
        candles.map { ChartPoint(it.timeMs, it.close, Session.REGULAR) }
    }
    val last = points.last().price
    val base = if (range == ChartRange.D1) history.previousClose ?: points.first().price else points.first().price
    return ChartData(points, last - base, if (base > 0.0) (last - base) / base * 100.0 else 0.0)
}

private fun standardSession(date: LocalDate) = MarketHours(
    date = date,
    isOpen = false,
    preStartMs = null,
    regularStartMs = et(date, LocalTime.of(9, 30)),
    regularEndMs = et(date, LocalTime.of(16, 0)),
    postEndMs = null,
)
