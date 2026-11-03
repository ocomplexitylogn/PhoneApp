package app.cushion.core.schwab

import app.cushion.core.auth.AccessTokenProvider
import app.cushion.core.model.Expiration
import app.cushion.core.model.Instrument
import app.cushion.core.model.MarketHours
import app.cushion.core.model.OptionChain
import app.cushion.core.model.PriceHistory
import app.cushion.core.model.Quote
import app.cushion.core.net.ApiException
import app.cushion.core.net.HttpRequest
import app.cushion.core.net.HttpResponse
import app.cushion.core.net.HttpTransport
import app.cushion.core.net.RateLimiter
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.LocalDate

/** Read-only market data. This app never calls an order or account endpoint. */
class SchwabClient(
    private val transport: HttpTransport,
    private val tokens: AccessTokenProvider,
    private val limiter: RateLimiter,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun quotes(symbols: List<String>): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Quote>()
        for (batch in symbols.distinct().chunked(MAX_SYMBOLS_PER_QUOTE)) {
            out += Parsers.quotes(get(Endpoints.quotes(batch)).body)
        }
        return out
    }

    /**
     * OTM puts across a date window, for scanning. Schwab can fail outright when a
     * chain is too large, so a server error retries once on the nearer half.
     */
    suspend fun putChain(symbol: String, from: LocalDate, to: LocalDate): OptionChain = try {
        chain(Endpoints.putChain(symbol, from, to, SCAN_STRIKE_COUNT))
    } catch (e: ApiException.Http) {
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to)
        if (e.code < 500 || days < 4) throw e
        chain(Endpoints.putChain(symbol, from, from.plusDays(days / 2), SCAN_STRIKE_COUNT))
    }

    suspend fun fullChain(symbol: String, expiration: LocalDate): OptionChain =
        chain(Endpoints.fullChain(symbol, expiration))

    private suspend fun chain(url: String): OptionChain {
        val chain = Parsers.chain(get(url).body)
        if (chain.status.equals("FAILED", ignoreCase = true)) {
            throw ApiException.Http(404, "No options listed for ${chain.symbol.ifEmpty { "this symbol" }}")
        }
        return chain
    }

    suspend fun expirations(symbol: String): List<Expiration> = Parsers.expirations(get(Endpoints.expirations(symbol)).body)

    suspend fun history(symbol: String, range: ChartRange): PriceHistory =
        Parsers.history(get(Endpoints.history(symbol, range)).body, symbol)

    suspend fun dailyHistory(symbol: String): PriceHistory =
        Parsers.history(get(Endpoints.dailyHistoryOneYear(symbol)).body, symbol)

    suspend fun marketHours(date: LocalDate): MarketHours? = Parsers.marketHours(get(Endpoints.marketHours(date)).body, date)

    suspend fun instruments(query: String, projection: String): List<Instrument> =
        Parsers.instruments(get(Endpoints.instruments(query, projection)).body)

    private suspend fun get(url: String): HttpResponse {
        var token = tokens.accessToken()
        var refreshed = false
        var attempt = 0
        while (true) {
            limiter.acquire()
            val response = try {
                transport.execute(HttpRequest("GET", url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")))
            } catch (e: IOException) {
                if (attempt++ < MAX_RETRIES) {
                    sleep(backoffMs(attempt))
                    continue
                }
                throw ApiException.Network(e)
            }
            when {
                response.isSuccess -> return response
                response.code == 401 && !refreshed -> {
                    refreshed = true
                    token = tokens.accessToken(rejected = token)
                }
                response.code == 401 -> throw ApiException.AuthRequired("Schwab rejected the sign-in")
                response.code == 429 -> {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                    if (attempt++ >= MAX_RETRIES) throw ApiException.RateLimited(retryAfter)
                    sleep(retryAfter ?: backoffMs(attempt))
                }
                response.code >= 500 && attempt < MAX_RETRIES -> {
                    attempt++
                    sleep(backoffMs(attempt))
                }
                else -> throw ApiException.Http(
                    response.code,
                    Parsers.errorMessage(response.body) ?: "Schwab returned an error (${response.code})",
                )
            }
        }
    }

    private fun backoffMs(attempt: Int) = 500L shl (attempt - 1)

    private companion object {
        const val MAX_RETRIES = 2
        const val MAX_SYMBOLS_PER_QUOTE = 200
        const val SCAN_STRIKE_COUNT = 60
    }
}
