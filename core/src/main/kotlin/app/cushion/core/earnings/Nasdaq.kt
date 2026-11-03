package app.cushion.core.earnings

import app.cushion.core.model.EarningsEvent
import app.cushion.core.model.EarningsTiming
import app.cushion.core.net.HttpRequest
import app.cushion.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate

/** Survives restarts so one day's calendar is only downloaded once. */
interface DayCache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
}

class InMemoryDayCache : DayCache {
    private val map = HashMap<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
}

data class EarningsSnapshot(
    val events: List<EarningsEvent>,
    /** Last date fetched with no gap before it. Null if the first day failed. */
    val coveredThrough: LocalDate?,
)

/**
 * Schwab's market data has no earnings calendar, so this reads Nasdaq's public one.
 * It's an unofficial endpoint: failures are reported through [EarningsSnapshot.coveredThrough]
 * so ideas show as unverified instead of looking earnings-free.
 */
class NasdaqEarnings(
    private val transport: HttpTransport,
    private val cache: DayCache,
    /** Runs after each network fetch, to stay polite to a free endpoint. */
    private val pauseAfterFetch: suspend () -> Unit = {},
    private val today: () -> LocalDate,
) {
    private val mutex = Mutex()

    suspend fun range(from: LocalDate, to: LocalDate): EarningsSnapshot = mutex.withLock {
        val events = ArrayList<EarningsEvent>()
        var covered: LocalDate? = null
        var date = from
        var gap = false
        while (!date.isAfter(to)) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                val day = load(date)
                if (day == null) {
                    gap = true
                } else {
                    events += day
                }
            }
            if (!gap) covered = date
            date = date.plusDays(1)
        }
        EarningsSnapshot(events, covered)
    }

    private suspend fun load(date: LocalDate): List<EarningsEvent>? {
        val key = "earnings-$date-fetched-${today()}"
        cache.get(key)?.let { return parseEarningsDay(it, date) }
        val body = try {
            val response = transport.execute(
                HttpRequest(
                    method = "GET",
                    url = "https://api.nasdaq.com/api/calendar/earnings?date=$date",
                    headers = mapOf(
                        // The endpoint drops requests that don't look like a browser.
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
                        "Accept" to "application/json, text/plain, */*",
                    ),
                ),
            )
            if (!response.isSuccess) return null
            response.body
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return null
        }
        pauseAfterFetch()
        val parsed = parseEarningsDay(body, date) ?: return null
        cache.put(key, body)
        return parsed
    }
}

private val json = Json { isLenient = true; ignoreUnknownKeys = true }

/** Returns null for an unreadable response, and an empty list for a quiet day. */
fun parseEarningsDay(body: String, date: LocalDate): List<EarningsEvent>? {
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val data = root["data"] as? JsonObject ?: return null
    val rows = data["rows"] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val symbol = row.text("symbol")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        EarningsEvent(
            symbol = symbol,
            name = row.text("name"),
            date = date,
            timing = when (row.text("time")) {
                "time-pre-market" -> EarningsTiming.BEFORE_OPEN
                "time-after-hours" -> EarningsTiming.AFTER_CLOSE
                else -> EarningsTiming.UNKNOWN
            },
            marketCapUsd = row.text("marketCap")?.replace("$", "")?.replace(",", "")?.toDoubleOrNull(),
            epsForecast = row.text("epsForecast")?.takeIf { it.isNotBlank() },
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Today through the coming Sunday; on a weekend, the coming Monday to Friday. */
fun earningsWeek(today: LocalDate): Pair<LocalDate, LocalDate> = when (today.dayOfWeek) {
    DayOfWeek.SATURDAY -> today.plusDays(2) to today.plusDays(6)
    DayOfWeek.SUNDAY -> today.plusDays(1) to today.plusDays(5)
    else -> today to today.plusDays((DayOfWeek.FRIDAY.value - today.dayOfWeek.value).toLong())
}

/** Soonest day first, biggest companies first within a day. */
fun List<EarningsEvent>.sortedForDisplay(): List<EarningsEvent> =
    sortedWith(compareBy<EarningsEvent> { it.date }.thenByDescending { it.marketCapUsd ?: 0.0 }.thenBy { it.symbol })
