package app.cushion.core.screen

/** Names the scanner covers out of the box, with the bucket used for sector caps. */
object Universe {
    val groups: Map<String, String> = linkedMapOf(
        "AMD" to "Semiconductors", "MU" to "Semiconductors", "NVDA" to "Semiconductors",
        "AVGO" to "Semiconductors", "TSM" to "Semiconductors", "SOXL" to "Semiconductors",
        "SMCI" to "Technology", "AAPL" to "Technology", "MSFT" to "Technology",
        "GOOGL" to "Technology", "META" to "Technology", "PLTR" to "Technology",
        "SNDK" to "Technology", "TQQQ" to "Technology",
        "AMZN" to "Consumer", "TSLA" to "Consumer", "COST" to "Consumer", "KO" to "Consumer",
        "HOOD" to "Financials", "JPM" to "Financials", "BAC" to "Financials",
        "XOM" to "Energy", "CVX" to "Energy", "GEV" to "Energy",
        "ABBV" to "Healthcare", "UNH" to "Healthcare",
        "SPY" to "Broad market", "QQQ" to "Broad market",
        "IBIT" to "Crypto", "ETHA" to "Crypto",
    )

    val defaultSymbols: List<String> = groups.keys.toList()

    fun groupOf(symbol: String): String = groups[symbol] ?: "Other"

    /** Fallback for when Schwab omits `fundLeverageFactor`. */
    private val knownLeverage = mapOf(
        "SOXL" to 3.0, "SOXS" to 3.0, "TQQQ" to 3.0, "SQQQ" to 3.0, "SPXL" to 3.0, "UPRO" to 3.0,
        "TNA" to 3.0, "LABU" to 3.0, "NVDL" to 2.0, "TSLL" to 2.0, "SSO" to 2.0, "QLD" to 2.0,
    )

    fun leverageOf(symbol: String, reported: Double?): Double =
        reported?.takeIf { it > 1.0 } ?: knownLeverage[symbol] ?: 1.0
}
