package app.cushion.core.demo

import app.cushion.core.auth.AccessTokenProvider
import app.cushion.core.math.BlackScholes
import app.cushion.core.model.PutCall
import app.cushion.core.net.HttpRequest
import app.cushion.core.net.HttpResponse
import app.cushion.core.net.HttpTransport
import app.cushion.core.repo.marketDate
import app.cushion.core.screen.MARKET_ZONE
import app.cushion.core.screen.yearsToExpiry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

object DemoTokens : AccessTokenProvider {
    override suspend fun accessToken(rejected: String?) = "demo"
}

/**
 * Sample data for demo mode, in Schwab's and Nasdaq's real JSON shapes so that it
 * flows through the same parsers as live data. Prices start from the mock screens.
 */
class DemoTransport(private val nowMs: () -> Long = System::currentTimeMillis) : HttpTransport {
    private data class Seed(val name: String, val price: Double, val changePct: Double, val iv: Double, val leverage: Double = 0.0, val etf: Boolean = false)

    private val seeds = mapOf(
        "SPY" to Seed("SPDR S&P 500 ETF", 752.88, -0.76, 0.17, etf = true),
        "QQQ" to Seed("INVESCO QQQ TRUST", 682.55, -1.90, 0.23, etf = true),
        "DIA" to Seed("SPDR DOW JONES INDUSTRIAL AVERAGE ETF", 519.00, 0.05, 0.15, etf = true),
        "IBIT" to Seed("ISHARES BITCOIN TRUST ETF", 43.54, -1.51, 0.55, etf = true),
        "ETHA" to Seed("ISHARES ETHEREUM TRUST ETF", 50.97, -1.79, 0.75, etf = true),
        "SOLZ" to Seed("VOLATILITY SHARES SOLANA ETF", 9.11, -2.14, 0.95, etf = true),
        "SOXL" to Seed("DIREXION DAILY SEMICONDUCTOR BULL 3X ETF", 69.76, -13.89, 1.30, leverage = 3.0, etf = true),
        "TQQQ" to Seed("PROSHARES ULTRAPRO QQQ", 88.40, -5.60, 0.70, leverage = 3.0, etf = true),
        "AMD" to Seed("ADVANCED MICRO DEVICES INC", 214.30, -4.10, 0.62),
        "MU" to Seed("MICRON TECHNOLOGY INC", 246.15, -3.72, 0.66),
        "NVDA" to Seed("NVIDIA CORP", 191.20, -3.05, 0.45),
        "AVGO" to Seed("BROADCOM INC", 372.60, -2.80, 0.46),
        "TSM" to Seed("TAIWAN SEMICONDUCTOR MFG", 301.75, -2.20, 0.42),
        "SMCI" to Seed("SUPER MICRO COMPUTER INC", 52.80, -5.25, 0.85),
        "AAPL" to Seed("APPLE INC", 271.40, -0.42, 0.28),
        "MSFT" to Seed("MICROSOFT CORP", 520.10, -0.85, 0.25),
        "GOOGL" to Seed("ALPHABET INC", 288.35, -1.02, 0.31),
        "META" to Seed("META PLATFORMS INC", 702.45, -1.65, 0.36),
        "PLTR" to Seed("PALANTIR TECHNOLOGIES INC", 186.90, -3.95, 0.68),
        "SNDK" to Seed("SANDISK CORP", 164.25, -4.45, 0.78),
        "AMZN" to Seed("AMAZON.COM INC", 228.70, -0.95, 0.33),
        "TSLA" to Seed("TESLA INC", 448.10, -2.15, 0.58),
        "COST" to Seed("COSTCO WHOLESALE CORP", 945.25, 0.22, 0.22),
        "KO" to Seed("COCA-COLA CO", 69.15, 0.48, 0.17),
        "HOOD" to Seed("ROBINHOOD MARKETS INC", 128.55, -3.30, 0.74),
        "JPM" to Seed("JPMORGAN CHASE & CO", 311.40, 0.18, 0.24),
        "BAC" to Seed("BANK OF AMERICA CORP", 52.65, 0.25, 0.27),
        "XOM" to Seed("EXXON MOBIL CORP", 118.20, 0.60, 0.25),
        "CVX" to Seed("CHEVRON CORP", 163.75, 0.44, 0.25),
        "GEV" to Seed("GE VERNOVA INC", 612.40, -2.05, 0.52),
        "ABBV" to Seed("ABBVIE INC", 231.85, 0.31, 0.26),
        "UNH" to Seed("UNITEDHEALTH GROUP INC", 362.10, -0.33, 0.36),
        "\$VIX" to Seed("CBOE VOLATILITY INDEX", 24.80, 11.2, 0.9),
    )

    private fun seedOf(symbol: String): Seed = seeds[symbol] ?: run {
        val r = Random(symbol.hashCode())
        Seed("$symbol (demo)", 20.0 + r.nextDouble() * 300.0, -3.0 + r.nextDouble() * 6.0, 0.3 + r.nextDouble() * 0.5)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val query = request.url.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val path = request.url.substringBefore('?')
        val body = when {
            path.endsWith("/calendar/earnings") -> earnings(LocalDate.parse(query.getValue("date")))
            path.endsWith("/quotes") -> quotes(query.getValue("symbols").split(','))
            path.endsWith("/chains") -> chain(query)
            path.endsWith("/expirationchain") -> expirations()
            path.endsWith("/pricehistory") -> history(query)
            path.endsWith("/markets") -> markets()
            path.endsWith("/instruments") -> instruments(query.getValue("symbol"))
            else -> return HttpResponse(404, "{}")
        }
        return HttpResponse(200, body.toString())
    }

    private fun quotes(symbols: List<String>): JsonObject = buildJsonObject {
        for (symbol in symbols) {
            if (symbol.length > 12 && symbol.contains(' ')) {
                optionQuote(symbol)?.let { put(symbol, it) }
                continue
            }
            val seed = seedOf(symbol)
            val previous = seed.price / (1.0 + seed.changePct / 100.0)
            putJsonObject(symbol) {
                put("assetMainType", if (symbol.startsWith("$")) "INDEX" else "EQUITY")
                if (seed.etf) put("assetSubType", "ETF") else put("assetSubType", "COE")
                put("symbol", symbol)
                put("realtime", true)
                putJsonObject("quote") {
                    put("lastPrice", seed.price)
                    put("mark", seed.price)
                    put("bidPrice", seed.price - 0.01)
                    put("askPrice", seed.price + 0.01)
                    put("closePrice", previous)
                    put("openPrice", previous)
                    put("highPrice", max(previous, seed.price) * 1.01)
                    put("lowPrice", minOf(previous, seed.price) * 0.99)
                    put("netChange", seed.price - previous)
                    put("netPercentChange", seed.changePct)
                    put("totalVolume", 12_400_000)
                    put("52WeekHigh", seed.price * 1.4)
                    put("52WeekLow", seed.price * 0.6)
                    put("quoteTime", nowMs())
                }
                putJsonObject("reference") { put("description", seed.name) }
                putJsonObject("fundamental") {
                    if (seed.leverage > 0.0) put("fundLeverageFactor", seed.leverage)
                }
            }
        }
    }

    private fun optionQuote(osi: String): JsonObject? {
        val underlying = osi.take(6).trim()
        val body = osi.drop(6)
        if (body.length != 15) return null
        val expiration = LocalDate.of(2000 + body.take(2).toInt(), body.substring(2, 4).toInt(), body.substring(4, 6).toInt())
        val side = if (body[6] == 'P') PutCall.PUT else PutCall.CALL
        val strike = body.drop(7).toDouble() / 1000.0
        val seed = seedOf(underlying)
        val t = yearsToExpiry(expiration, nowMs())
        val mid = BlackScholes.price(side, seed.price, strike, t, RATE, volAt(seed, strike))
        return buildJsonObject {
            put("assetMainType", "OPTION")
            put("symbol", osi)
            put("realtime", true)
            putJsonObject("quote") {
                put("mark", cents(mid))
                put("bidPrice", cents(mid * 0.98))
                put("askPrice", cents(mid * 1.02))
                put("lastPrice", cents(mid))
                put("delta", BlackScholes.delta(side, seed.price, strike, t, RATE, volAt(seed, strike)))
                put("netChange", 0.0)
                put("netPercentChange", 0.0)
            }
        }
    }

    private fun volAt(seed: Seed, strike: Double): Double {
        val moneyness = abs(strike / seed.price - 1.0)
        return seed.iv * (1.0 + 0.6 * moneyness)
    }

    private fun strikeStep(price: Double): Double = when {
        price < 25 -> 0.5
        price < 100 -> 1.0
        price < 300 -> 2.5
        else -> 5.0
    }

    private fun chain(q: Map<String, String>): JsonObject {
        val symbol = q.getValue("symbol")
        val seed = seedOf(symbol)
        val from = LocalDate.parse(q.getValue("fromDate"))
        val to = LocalDate.parse(q.getValue("toDate"))
        val wantCalls = q["contractType"] != "PUT"
        val fridays = expiryDates().filter { !it.isBefore(from) && !it.isAfter(to) }
        return buildJsonObject {
            put("symbol", symbol)
            put("status", "SUCCESS")
            put("strategy", "SINGLE")
            put("isDelayed", false)
            put("underlyingPrice", seed.price)
            put("interestRate", RATE * 100.0)
            putJsonObject("putExpDateMap") {
                for (d in fridays) sideOf(this, symbol, seed, d, PutCall.PUT)
            }
            putJsonObject("callExpDateMap") {
                if (wantCalls) for (d in fridays) sideOf(this, symbol, seed, d, PutCall.CALL)
            }
        }
    }

    private fun sideOf(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        symbol: String,
        seed: Seed,
        date: LocalDate,
        side: PutCall,
    ) {
        val today = marketDate(nowMs())
        val dte = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()
        val t = yearsToExpiry(date, nowMs())
        val step = strikeStep(seed.price)
        val atm = round(seed.price / step) * step
        with(builder) {
            putJsonObject("$date:$dte") {
                for (i in -STRIKES_EACH_SIDE..STRIKES_EACH_SIDE) {
                    val k = atm + i * step
                    if (k <= 0.0) continue
                    val vol = volAt(seed, k)
                    val theo = BlackScholes.price(side, seed.price, k, t, RATE, vol)
                    val half = max(0.01, theo * 0.02)
                    val delta = BlackScholes.delta(side, seed.price, k, t, RATE, vol)
                    val code = if (side == PutCall.PUT) "P" else "C"
                    val osi = String.format(
                        Locale.US, "%-6s%s%s%08d", symbol,
                        date.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")), code, round(k * 1000).toLong(),
                    )
                    putJsonArray(k.toString()) {
                        addJsonObject {
                            put("putCall", side.name)
                            put("symbol", osi)
                            put("bid", cents(theo - half))
                            put("ask", cents(theo + half))
                            put("mark", cents(theo))
                            put("last", cents(theo))
                            put("totalVolume", 950)
                            put("openInterest", 4200)
                            put("volatility", vol * 100.0)
                            put("delta", delta)
                            put("gamma", 0.01)
                            put("theta", -theo / max(4.0, dte.toDouble()) * 0.6)
                            put("vega", 0.05)
                            put("strikePrice", k)
                            put("daysToExpiration", dte)
                            put("multiplier", 100.0)
                            put("inTheMoney", if (side == PutCall.PUT) k > seed.price else k < seed.price)
                            put("nonStandard", false)
                            put("quoteTimeInLong", nowMs())
                        }
                    }
                }
            }
        }
    }

    private fun expiryDates(): List<LocalDate> {
        val today = marketDate(nowMs())
        val first = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        return (0 until 12).map { first.plusWeeks(it.toLong()) }
    }

    private fun expirations(): JsonObject = buildJsonObject {
        val today = marketDate(nowMs())
        putJsonArray("expirationList") {
            for (d in expiryDates()) {
                addJsonObject {
                    put("expirationDate", d.toString())
                    put("daysToExpiration", java.time.temporal.ChronoUnit.DAYS.between(today, d))
                    put("expirationType", "W")
                    put("standard", true)
                }
            }
        }
    }

    private fun history(q: Map<String, String>): JsonObject {
        val symbol = q.getValue("symbol")
        val seed = seedOf(symbol)
        val intraday = q["frequencyType"] == "minute"
        val previous = seed.price / (1.0 + seed.changePct / 100.0)
        val rng = Random(symbol.hashCode())
        return buildJsonObject {
            put("symbol", symbol)
            put("empty", false)
            put("previousClose", previous)
            putJsonArray("candles") {
                val candles = if (intraday) intradayCandles(seed, previous, rng) else dailyCandles(seed, previous, q, rng)
                for (c in candles) {
                    addJsonObject {
                        put("open", c[0])
                        put("high", c[1])
                        put("low", c[2])
                        put("close", c[3])
                        put("volume", 100_000)
                        put("datetime", c[4].toLong())
                    }
                }
            }
        }
    }

    /** Pre-market, regular and post-market bars for the latest weekday. */
    private fun intradayCandles(seed: Seed, previous: Double, rng: Random): List<DoubleArray> {
        var date = marketDate(nowMs())
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) date = date.minusDays(1)
        val start = date.atTime(LocalTime.of(4, 0)).atZone(MARKET_ZONE).toInstant().toEpochMilli()
        val bars = 16 * 12
        val out = ArrayList<DoubleArray>(bars)
        val noise = seed.price * seed.iv / sqrt(252.0) / 18.0
        var drift = 0.0
        for (i in 0 until bars) {
            drift += rng.nextDouble(-1.0, 1.0) * noise
            drift *= 0.97
            val shape = when {
                i < 66 -> 0.1 * i / 66.0
                i < 144 -> 0.1 + 0.95 * (i - 66) / 78.0
                else -> 1.05 - 0.05 * (i - 144) / 48.0
            }
            val p = previous + (seed.price - previous) * shape + drift * (1.0 - i.toDouble() / bars)
            val time = (start + i * 300_000L).toDouble()
            out += doubleArrayOf(p, p + noise, p - noise, p, time)
        }
        out[out.lastIndex][3] = seed.price
        return out
    }

    private fun dailyCandles(seed: Seed, previous: Double, q: Map<String, String>, rng: Random): List<DoubleArray> {
        val period = q["period"]?.toIntOrNull() ?: 1
        val stepDays = if (q["frequencyType"] == "weekly") 7 else 1
        val days = when (q["periodType"]) {
            "year" -> 365 * period
            "month" -> 30 * period
            else -> 7
        } / stepDays
        val dailyVol = seed.iv / sqrt(252.0) * sqrt(stepDays.toDouble())
        val closes = DoubleArray(days + 1)
        closes[days] = seed.price
        closes[days - 1] = previous
        for (i in days - 2 downTo 0) {
            closes[i] = closes[i + 1] / exp(rng.nextDouble(-1.0, 1.0) * dailyVol * 1.3 + 0.0004 * stepDays)
        }
        val today = marketDate(nowMs())
        return closes.indices.map { i ->
            val date = today.minusDays(((days - i) * stepDays).toLong())
            val time = date.atTime(LocalTime.MIDNIGHT).atZone(MARKET_ZONE).toInstant().toEpochMilli().toDouble()
            val c = closes[i]
            val o = if (i > 0) closes[i - 1] else c
            val wick = abs(c - o) * 0.1 + c * dailyVol * 0.12
            doubleArrayOf(o, max(o, c) + wick, minOf(o, c) - wick, c, time)
        }
    }

    private fun markets(): JsonObject {
        val date = marketDate(nowMs())
        fun at(h: Int, m: Int) = date.atTime(h, m).atZone(MARKET_ZONE).toOffsetDateTime().toString()
        val open = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
        return buildJsonObject {
            putJsonObject("equity") {
                putJsonObject("EQ") {
                    put("date", date.toString())
                    put("isOpen", open)
                    if (open) {
                        putJsonObject("sessionHours") {
                            putJsonArray("preMarket") { addJsonObject { put("start", at(4, 0)); put("end", at(9, 30)) } }
                            putJsonArray("regularMarket") { addJsonObject { put("start", at(9, 30)); put("end", at(16, 0)) } }
                            putJsonArray("postMarket") { addJsonObject { put("start", at(16, 0)); put("end", at(20, 0)) } }
                        }
                    }
                }
            }
        }
    }

    private fun instruments(raw: String): JsonObject {
        val needle = raw.removeSuffix(".*").uppercase()
        return buildJsonObject {
            putJsonArray("instruments") {
                for ((symbol, seed) in seeds) {
                    if (symbol.startsWith("$")) continue
                    if (symbol.startsWith(needle) || seed.name.contains(needle)) {
                        addJsonObject {
                            put("symbol", symbol)
                            put("description", seed.name)
                            put("assetType", if (seed.etf) "ETF" else "EQUITY")
                        }
                    }
                }
            }
        }
    }

    /** A small fixed slate each week so the Browse section and earnings gate both have data. */
    private fun earnings(date: LocalDate): JsonObject {
        val slate = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> listOf("AAPL" to "Apple Inc", "ABBV" to "AbbVie Inc", "ADTN" to "ADTRAN Holdings", "AEIS" to "Advanced Energy")
            DayOfWeek.TUESDAY -> listOf("AMD" to "Advanced Micro Devices", "PLTR" to "Palantir Technologies")
            DayOfWeek.WEDNESDAY -> listOf("HOOD" to "Robinhood Markets", "UNH" to "UnitedHealth Group")
            DayOfWeek.THURSDAY -> listOf("COST" to "Costco Wholesale", "AEHR" to "Aehr Test Systems")
            else -> emptyList()
        }
        val weekOffset = java.time.temporal.ChronoUnit.WEEKS.between(
            marketDate(nowMs()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
        )
        return buildJsonObject {
            putJsonObject("data") {
                putJsonArray("rows") {
                    if (weekOffset == 0L) {
                        slate.forEachIndexed { i, (symbol, name) ->
                            addJsonObject {
                                put("symbol", symbol)
                                put("name", name)
                                put("time", if (i % 2 == 0) "time-after-hours" else "time-pre-market")
                                put("marketCap", "$${(4 - i)}00,000,000,000")
                                put("epsForecast", "$1.20")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cents(v: Double): Double = round(max(0.0, v) * 100.0) / 100.0

    private companion object {
        const val RATE = 0.04
        const val STRIKES_EACH_SIDE = 40
    }
}
