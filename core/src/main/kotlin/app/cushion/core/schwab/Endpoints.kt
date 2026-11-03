package app.cushion.core.schwab

import java.net.URLEncoder
import java.time.LocalDate

const val SCHWAB_MARKET_DATA = "https://api.schwabapi.com/marketdata/v1"

private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

private fun url(path: String, vararg params: Pair<String, Any?>): String {
    val query = params.filter { it.second != null }
        .joinToString("&") { (k, v) -> "$k=${enc(v.toString())}" }
    return "$SCHWAB_MARKET_DATA$path" + if (query.isEmpty()) "" else "?$query"
}

object Endpoints {
    fun quotes(symbols: List<String>): String =
        url("/quotes", "symbols" to symbols.joinToString(","), "fields" to "quote,fundamental,reference,regular", "indicative" to false)

    fun putChain(symbol: String, from: LocalDate, to: LocalDate, strikeCount: Int): String = url(
        "/chains",
        "symbol" to symbol,
        "contractType" to "PUT",
        "strikeCount" to strikeCount,
        "includeUnderlyingQuote" to false,
        "strategy" to "SINGLE",
        "range" to "OTM",
        "fromDate" to from,
        "toDate" to to,
    )

    /** Every strike for one expiry, both sides. Drives the Ticker screen. */
    fun fullChain(symbol: String, expiration: LocalDate): String = url(
        "/chains",
        "symbol" to symbol,
        "contractType" to "ALL",
        "includeUnderlyingQuote" to false,
        "strategy" to "SINGLE",
        "fromDate" to expiration,
        "toDate" to expiration,
    )

    fun expirations(symbol: String): String = url("/expirationchain", "symbol" to symbol)

    fun history(symbol: String, range: ChartRange): String = url(
        "/pricehistory",
        "symbol" to symbol,
        "periodType" to range.periodType,
        "period" to range.period,
        "frequencyType" to range.frequencyType,
        "frequency" to range.frequency,
        "needExtendedHoursData" to range.extendedHours,
        "needPreviousClose" to true,
    )

    fun dailyHistoryOneYear(symbol: String): String = url(
        "/pricehistory",
        "symbol" to symbol,
        "periodType" to "year",
        "period" to 1,
        "frequencyType" to "daily",
        "frequency" to 1,
        "needExtendedHoursData" to false,
        "needPreviousClose" to true,
    )

    fun marketHours(date: LocalDate): String = url("/markets", "markets" to "equity", "date" to date)

    fun instruments(query: String, projection: String): String =
        url("/instruments", "symbol" to query, "projection" to projection)
}

/** The range pills under the chart, mapped to values `/pricehistory` accepts. */
enum class ChartRange(
    val label: String,
    val periodType: String,
    val period: Int,
    val frequencyType: String,
    val frequency: Int,
    val extendedHours: Boolean,
) {
    // Two days are requested so that weekends and holidays still return a session.
    D1("1D", "day", 2, "minute", 5, true),
    W1("1W", "day", 5, "minute", 30, false),
    M1("1M", "month", 1, "daily", 1, false),
    M3("3M", "month", 3, "daily", 1, false),
    Y1("1Y", "year", 1, "daily", 1, false),
    Y5("5Y", "year", 5, "weekly", 1, false),
    Y10("10Y", "year", 10, "weekly", 1, false),
}
