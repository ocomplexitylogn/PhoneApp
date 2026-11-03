package app.cushion.core.settings

import app.cushion.core.screen.ScreenConfig
import app.cushion.core.screen.ShortlistItem
import app.cushion.core.screen.Universe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

data class AppSettings(
    val screen: ScreenConfig = ScreenConfig(),
    val universe: List<String> = Universe.defaultSymbols,
    /** Net liquidation value, typed in by the user. It sizes contracts and caps. */
    val accountValue: Double? = null,
    val perNameCapPct: Double = 5.0,
    val sectorCapPct: Double = 25.0,
    val demoMode: Boolean = false,
    val showBlocked: Boolean = false,
)

private val json = Json { isLenient = true; ignoreUnknownKeys = true }

private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

fun encodeSettings(s: AppSettings): String = buildJsonObject {
    put("minDte", s.screen.minDte)
    put("maxDte", s.screen.maxDte)
    put("deltaTolerance", s.screen.deltaTolerance)
    put("minCushionSigma", s.screen.minCushionSigma)
    put("minCushionSigmaLeveraged", s.screen.minCushionSigmaLeveraged)
    put("minOpenInterest", s.screen.minOpenInterest)
    put("maxSpreadPct", s.screen.maxSpreadPct)
    put("minBid", s.screen.minBid)
    putJsonArray("universe") { s.universe.forEach { add(JsonPrimitive(it)) } }
    s.accountValue?.let { put("accountValue", it) }
    put("perNameCapPct", s.perNameCapPct)
    put("sectorCapPct", s.sectorCapPct)
    put("demoMode", s.demoMode)
    put("showBlocked", s.showBlocked)
}.toString()

/** Unknown or missing keys take their defaults, so old saves survive upgrades. */
fun decodeSettings(text: String?): AppSettings {
    val defaults = AppSettings()
    val o = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return defaults
    val d = defaults.screen
    return AppSettings(
        screen = ScreenConfig(
            minDte = o.i("minDte") ?: d.minDte,
            maxDte = o.i("maxDte") ?: d.maxDte,
            deltaTolerance = o.d("deltaTolerance") ?: d.deltaTolerance,
            minCushionSigma = o.d("minCushionSigma") ?: d.minCushionSigma,
            minCushionSigmaLeveraged = o.d("minCushionSigmaLeveraged") ?: d.minCushionSigmaLeveraged,
            minOpenInterest = o.l("minOpenInterest") ?: d.minOpenInterest,
            maxSpreadPct = o.d("maxSpreadPct") ?: d.maxSpreadPct,
            minBid = o.d("minBid") ?: d.minBid,
        ),
        universe = (o["universe"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() } ?: defaults.universe,
        accountValue = o.d("accountValue"),
        perNameCapPct = o.d("perNameCapPct") ?: defaults.perNameCapPct,
        sectorCapPct = o.d("sectorCapPct") ?: defaults.sectorCapPct,
        demoMode = o.b("demoMode") ?: false,
        showBlocked = o.b("showBlocked") ?: false,
    )
}

fun encodeShortlist(items: List<ShortlistItem>): String = buildJsonArray {
    items.forEach { i ->
        addJsonObject {
            put("contractSymbol", i.contractSymbol)
            put("underlying", i.underlying)
            put("strike", i.strike)
            put("expiration", i.expiration.toString())
            put("premiumWhenAdded", i.premiumWhenAdded)
            put("contracts", i.contracts)
            put("addedAtMs", i.addedAtMs)
        }
    }
}.toString()

fun decodeShortlist(text: String?): List<ShortlistItem> {
    val array = text?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
    return array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        ShortlistItem(
            contractSymbol = o.s("contractSymbol") ?: return@mapNotNull null,
            underlying = o.s("underlying") ?: return@mapNotNull null,
            strike = o.d("strike") ?: return@mapNotNull null,
            expiration = o.s("expiration")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null,
            premiumWhenAdded = o.d("premiumWhenAdded") ?: 0.0,
            contracts = o.i("contracts") ?: 1,
            addedAtMs = o.l("addedAtMs") ?: 0L,
        )
    }
}
