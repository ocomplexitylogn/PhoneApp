package app.cushion.core.schwab

import app.cushion.core.model.Candle
import app.cushion.core.model.Expiration
import app.cushion.core.model.Instrument
import app.cushion.core.model.MarketHours
import app.cushion.core.model.OptionChain
import app.cushion.core.model.OptionContract
import app.cushion.core.model.PriceHistory
import app.cushion.core.model.PutCall
import app.cushion.core.model.Quote
import app.cushion.core.net.ApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Parsers work on the JSON tree rather than generated classes because Schwab's
 * responses disagree with its own spec: the OpenAPI document says `bidPrice` and
 * `expiration` where the live API sends `bid` and `expirationDate`, numbers can be
 * the bare token NaN, and -999 stands in for missing Greeks.
 */
object Parsers {
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    private fun root(body: String): JsonElement = try {
        json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw ApiException.Parse("Schwab sent a response this app couldn't read", e)
    }

    fun quotes(body: String): Map<String, Quote> {
        val rootObject = root(body) as? JsonObject ?: return emptyMap()
        return buildMap {
            for ((key, value) in rootObject) {
                val entry = value as? JsonObject ?: continue
                val quoteBlock = entry.obj("quote") ?: continue
                put(key, quote(key, entry, quoteBlock))
            }
        }
    }

    private fun quote(key: String, entry: JsonObject, q: JsonObject): Quote {
        val reference = entry.obj("reference")
        val fundamental = entry.obj("fundamental")
        val last = q.price("lastPrice") ?: q.price("mark") ?: q.price("closePrice") ?: 0.0
        val close = q.price("closePrice")
        val change = q.num("netChange") ?: close?.let { last - it } ?: 0.0
        val changePct = q.num("netPercentChange")
            ?: close?.takeIf { it > 0.0 }?.let { (last - it) / it * 100.0 } ?: 0.0
        return Quote(
            symbol = entry.str("symbol") ?: key,
            description = reference?.str("description") ?: entry.str("description") ?: key,
            last = last,
            change = change,
            changePct = changePct,
            bid = q.price("bidPrice"),
            ask = q.price("askPrice"),
            mark = q.price("mark"),
            open = q.price("openPrice"),
            high = q.price("highPrice"),
            low = q.price("lowPrice"),
            previousClose = close,
            volume = q.lng("totalVolume"),
            week52High = q.price("52WeekHigh"),
            week52Low = q.price("52WeekLow"),
            realtime = entry.bool("realtime") ?: true,
            quoteTimeMs = q.lng("quoteTime"),
            assetMainType = entry.str("assetMainType"),
            assetSubType = entry.str("assetSubType"),
            leverageFactor = fundamental?.num("fundLeverageFactor")?.let { kotlin.math.abs(it) }?.takeIf { it > 0.0 },
            delta = q.greek("delta"),
        )
    }

    fun chain(body: String): OptionChain {
        val o = root(body) as? JsonObject ?: throw ApiException.Parse("Schwab sent an empty option chain")
        val symbol = o.str("symbol").orEmpty()
        return OptionChain(
            symbol = symbol,
            status = o.str("status") ?: "UNKNOWN",
            underlyingPrice = o.num("underlyingPrice") ?: 0.0,
            // Schwab reports the rate in percent, e.g. 4.25 for 4.25%.
            interestRate = (o.num("interestRate") ?: 0.0) / 100.0,
            isDelayed = o.bool("isDelayed") ?: false,
            puts = contracts(o.obj("putExpDateMap"), symbol, PutCall.PUT),
            calls = contracts(o.obj("callExpDateMap"), symbol, PutCall.CALL),
        )
    }

    private fun contracts(map: JsonObject?, underlying: String, side: PutCall): List<OptionContract> {
        if (map == null) return emptyList()
        val out = ArrayList<OptionContract>()
        // Shape: { "2026-11-06:4": { "65.0": [ { contract } ] } }
        for ((expiryKey, strikes) in map) {
            val keyDate = runCatching { LocalDate.parse(expiryKey.substringBefore(':')) }.getOrNull() ?: continue
            val keyDte = expiryKey.substringAfter(':', "").toIntOrNull()
            for ((strikeKey, list) in strikes as? JsonObject ?: continue) {
                val c = (list as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                val strike = c.num("strikePrice") ?: strikeKey.toDoubleOrNull() ?: continue
                out += OptionContract(
                    symbol = c.str("symbol") ?: continue,
                    underlying = underlying,
                    putCall = side,
                    strike = strike,
                    expiration = keyDate,
                    dte = c.lng("daysToExpiration")?.toInt() ?: keyDte ?: 0,
                    bid = c.price("bid") ?: c.price("bidPrice") ?: 0.0,
                    ask = c.price("ask") ?: c.price("askPrice") ?: 0.0,
                    last = c.price("last") ?: c.price("lastPrice"),
                    mark = c.price("mark") ?: c.price("markPrice"),
                    volume = c.lng("totalVolume") ?: 0L,
                    openInterest = c.lng("openInterest") ?: 0L,
                    iv = c.greek("volatility")?.takeIf { it > 0.0 }?.div(100.0),
                    delta = c.greek("delta"),
                    gamma = c.greek("gamma"),
                    theta = c.greek("theta"),
                    vega = c.greek("vega"),
                    inTheMoney = c.bool("inTheMoney") ?: c.bool("isInTheMoney") ?: false,
                    multiplier = c.lng("multiplier")?.toInt()?.takeIf { it > 0 } ?: 100,
                    nonStandard = c.bool("nonStandard") ?: c.bool("isNonStandard") ?: false,
                    quoteTimeMs = c.lng("quoteTimeInLong"),
                )
            }
        }
        return out.sortedWith(compareBy({ it.expiration }, { it.strike }))
    }

    fun expirations(body: String): List<Expiration> {
        val list = (root(body) as? JsonObject)?.get("expirationList") as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val e = element as? JsonObject ?: return@mapNotNull null
            val raw = e.str("expirationDate") ?: e.str("expiration") ?: return@mapNotNull null
            val date = parseDate(raw) ?: return@mapNotNull null
            Expiration(
                date = date,
                dte = e.lng("daysToExpiration")?.toInt() ?: 0,
                type = e.str("expirationType"),
                standard = e.bool("standard") ?: true,
            )
        }.sortedBy { it.date }
    }

    fun history(body: String, symbol: String): PriceHistory {
        val o = root(body) as? JsonObject ?: return PriceHistory(symbol, emptyList(), null)
        val candles = (o["candles"] as? JsonArray).orEmpty().mapNotNull { element ->
            val c = element as? JsonObject ?: return@mapNotNull null
            val close = c.num("close") ?: return@mapNotNull null
            Candle(
                timeMs = c.lng("datetime") ?: return@mapNotNull null,
                open = c.num("open") ?: close,
                high = c.num("high") ?: close,
                low = c.num("low") ?: close,
                close = close,
                volume = c.lng("volume") ?: 0L,
            )
        }
        return PriceHistory(o.str("symbol") ?: symbol, candles, o.num("previousClose"))
    }

    /** Shape: { "equity": { "EQ": { "isOpen": true, "sessionHours": { ... } } } } */
    fun marketHours(body: String, date: LocalDate): MarketHours? {
        val equity = (root(body) as? JsonObject)?.obj("equity") ?: return null
        val block = equity.values.firstOrNull { it is JsonObject } as? JsonObject ?: return null
        val sessions = block.obj("sessionHours")
        fun window(name: String): Pair<Long, Long>? {
            val w = (sessions?.get(name) as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val start = w.str("start")?.let(::parseInstantMs) ?: return null
            val end = w.str("end")?.let(::parseInstantMs) ?: return null
            return start to end
        }
        val pre = window("preMarket")
        val regular = window("regularMarket")
        val post = window("postMarket")
        return MarketHours(
            date = block.str("date")?.let(::parseDate) ?: date,
            isOpen = block.bool("isOpen") ?: (regular != null),
            preStartMs = pre?.first,
            regularStartMs = regular?.first,
            regularEndMs = regular?.second,
            postEndMs = post?.second,
        )
    }

    fun instruments(body: String): List<Instrument> {
        val r = root(body)
        val list = (r as? JsonObject)?.get("instruments") as? JsonArray ?: r as? JsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Instrument(
                symbol = o.str("symbol") ?: return@mapNotNull null,
                description = o.str("description").orEmpty(),
                assetType = o.str("assetType"),
            )
        }
    }

    /** A Schwab error body's human message, if any. Never returned for token calls. */
    fun errorMessage(body: String): String? = runCatching {
        val o = json.parseToJsonElement(body) as? JsonObject ?: return null
        o.str("message") ?: o.str("error_description") ?: o.str("error")
            ?: ((o["errors"] as? JsonArray)?.firstOrNull() as? JsonObject)?.let { it.str("detail") ?: it.str("title") }
    }.getOrNull()

    private fun parseDate(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()

    private fun parseInstantMs(raw: String): Long? = runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(raw).toEpochMilli() }
        .getOrNull()
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.str(key: String): String? {
    val e = this[key]
    return if (e is JsonPrimitive && e !is JsonNull) e.contentOrNull else null
}

/** Any finite number. Rejects NaN, Infinity and Schwab's -999 sentinel. */
private fun JsonObject.num(key: String): Double? {
    val e = this[key] as? JsonPrimitive ?: return null
    if (e is JsonNull) return null
    val d = e.doubleOrNull ?: return null
    return d.takeIf { it.isFinite() && it > -999.0 }
}

/** A price: finite and not negative. */
private fun JsonObject.price(key: String): Double? = num(key)?.takeIf { it >= 0.0 }

/** A Greek or IV, which Schwab zero-fills as well as -999-fills when it has no model value. */
private fun JsonObject.greek(key: String): Double? = num(key)

private fun JsonObject.lng(key: String): Long? {
    val e = this[key] as? JsonPrimitive ?: return null
    if (e is JsonNull) return null
    return e.longOrNull ?: e.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
}

private fun JsonObject.bool(key: String): Boolean? {
    val e = this[key] as? JsonPrimitive ?: return null
    if (e is JsonNull) return null
    return e.booleanOrNull
}
