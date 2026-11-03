package app.cushion.core.math

import app.cushion.core.model.Candle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private const val TRADING_DAYS = 252.0

private fun trueRange(current: Candle, previousClose: Double): Double = max(
    current.high - current.low,
    max(abs(current.high - previousClose), abs(current.low - previousClose)),
)

/** Wilder's ATR series. The first value appears at index [period]. */
fun atrSeries(candles: List<Candle>, period: Int = 14): List<Double> {
    if (candles.size <= period) return emptyList()
    val ranges = (1 until candles.size).map { trueRange(candles[it], candles[it - 1].close) }
    val out = ArrayList<Double>(ranges.size - period + 1)
    var atr = ranges.take(period).average()
    out += atr
    for (i in period until ranges.size) {
        atr = (atr * (period - 1) + ranges[i]) / period
        out += atr
    }
    return out
}

fun atr(candles: List<Candle>, period: Int = 14): Double? = atrSeries(candles, period).lastOrNull()

/**
 * Where today's ATR-as-%-of-price sits against its own history, 0–100. Absolute ATR
 * says little on a leveraged ETF that has been reverse split or trended, so
 * percentile is the comparable number.
 */
fun atrPercentile(candles: List<Candle>, period: Int = 14): Double? {
    val series = atrSeries(candles, period)
    if (series.size < 20) return null
    val closes = candles.takeLast(series.size).map { it.close }
    val pct = series.indices.map { series[it] / closes[it] }
    val current = pct.last()
    return pct.count { it <= current } * 100.0 / pct.size
}

/** Annualised close-to-close realised volatility, as a fraction. */
fun realizedVol(candles: List<Candle>, period: Int = 20): Double? {
    if (candles.size <= period) return null
    val closes = candles.takeLast(period + 1).map { it.close }
    if (closes.any { it <= 0.0 }) return null
    val returns = closes.zipWithNext { a, b -> ln(b / a) }
    val mean = returns.average()
    val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
    return sqrt(variance * TRADING_DAYS)
}

fun sma(candles: List<Candle>, period: Int): Double? =
    if (candles.size < period) null else candles.takeLast(period).map { it.close }.average()
