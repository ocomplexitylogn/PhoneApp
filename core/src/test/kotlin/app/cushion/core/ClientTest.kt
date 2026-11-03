package app.cushion.core

import app.cushion.core.auth.AccessTokenProvider
import app.cushion.core.net.ApiException
import app.cushion.core.net.HttpRequest
import app.cushion.core.net.HttpResponse
import app.cushion.core.net.HttpTransport
import app.cushion.core.net.RateLimiter
import app.cushion.core.schwab.SchwabClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class ClientTest {
    private val quote = """{"SPY":{"symbol":"SPY","quote":{"lastPrice":752.88}}}"""
    private val sleeps = ArrayList<Long>()

    private fun tokens() = object : AccessTokenProvider {
        var current = "A1"
        override suspend fun accessToken(rejected: String?): String {
            if (rejected == current) current = "A2"
            return current
        }
    }

    private fun client(tokens: AccessTokenProvider = tokens(), t: suspend (HttpRequest) -> HttpResponse) =
        SchwabClient(HttpTransport { t(it) }, tokens, RateLimiter(6000, 100), { sleeps += it })

    @Test fun `401 refreshes once and retries with the new token`() = runBlocking {
        val seen = ArrayList<String>()
        val c = client { r ->
            seen += r.headers.getValue("Authorization")
            if (seen.size == 1) HttpResponse(401, "") else HttpResponse(200, quote)
        }
        assertEquals(752.88, c.quotes(listOf("SPY")).getValue("SPY").last, 1e-9)
        assertEquals(listOf("Bearer A1", "Bearer A2"), seen)
    }

    @Test fun `a second 401 requires sign-in rather than looping`() = runBlocking {
        var calls = 0
        val c = client { calls++; HttpResponse(401, "") }
        try {
            c.quotes(listOf("SPY"))
            fail("expected AuthRequired")
        } catch (_: ApiException.AuthRequired) {
        }
        assertEquals(2, calls)
    }

    @Test fun `429 honours Retry-After`() = runBlocking {
        var calls = 0
        val c = client {
            calls++
            if (calls == 1) HttpResponse(429, "", mapOf("retry-after" to "7")) else HttpResponse(200, quote)
        }
        c.quotes(listOf("SPY"))
        assertEquals(listOf(7000L), sleeps)
    }

    @Test fun `persistent 429 surfaces as rate limited`() = runBlocking {
        val c = client { HttpResponse(429, "") }
        try {
            c.quotes(listOf("SPY"))
            fail("expected RateLimited")
        } catch (_: ApiException.RateLimited) {
        }
        assertEquals(2, sleeps.size)
    }

    @Test fun `network failure retries with backoff then fails typed`() = runBlocking {
        var calls = 0
        val c = client { calls++; throw IOException("offline") }
        try {
            c.quotes(listOf("SPY"))
            fail("expected Network")
        } catch (_: ApiException.Network) {
        }
        assertEquals(3, calls)
        assertEquals(listOf(500L, 1000L), sleeps)
    }

    @Test fun `4xx carries Schwab's message and is not retried`() = runBlocking {
        var calls = 0
        val c = client { calls++; HttpResponse(400, """{"errors":[{"status":400,"title":"Bad Request","detail":"Invalid symbol"}]}""") }
        try {
            c.quotes(listOf("SPY"))
            fail("expected Http")
        } catch (e: ApiException.Http) {
            assertEquals("Invalid symbol", e.message)
        }
        assertEquals(1, calls)
    }

    @Test fun `quotes batch large universes and skip empty calls`() = runBlocking {
        var calls = 0
        val c = client { calls++; HttpResponse(200, "{}") }
        c.quotes(emptyList())
        assertEquals(0, calls)
        c.quotes((1..450).map { "S$it" })
        assertEquals(3, calls)
    }

    @Test fun `an oversized chain retries on a narrower window`() = runBlocking {
        val urls = ArrayList<String>()
        val c = client { r ->
            urls += r.url
            if (r.url.contains("toDate=2026-12-17")) {
                HttpResponse(502, """{"message":"Body buffer overflow"}""")
            } else {
                HttpResponse(200, """{"symbol":"SPY","status":"SUCCESS","underlyingPrice":700,"putExpDateMap":{}}""")
            }
        }
        val chain = c.putChain("SPY", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 12, 17))
        assertEquals("SPY", chain.symbol)
        assertTrue(urls.last().contains("toDate=2026-11-28"))
    }

    @Test fun `failed chain status is an error, not an empty success`() = runBlocking {
        val c = client { HttpResponse(200, """{"symbol":"BRK.A","status":"FAILED"}""") }
        try {
            c.putChain("BRK.A", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 12, 17))
            fail("expected Http")
        } catch (e: ApiException.Http) {
            assertEquals(404, e.code)
        }
    }

    @Test fun `limiter blocks past the burst and refills over time`() = runBlocking {
        var now = 0L
        val waits = ArrayList<Long>()
        val limiter = RateLimiter(60, 2, { now }, { waits += it; now += it })
        limiter.acquire()
        limiter.acquire()
        assertTrue(waits.isEmpty())
        limiter.acquire()
        assertEquals(listOf(1000L), waits)
    }

    @Test fun `limiter holds its average rate`() = runBlocking {
        var now = 0L
        val limiter = RateLimiter(100, 5, { now }, { now += it })
        repeat(305) { limiter.acquire() }
        // 5 burst + 300 refilled at 100/min takes three minutes.
        assertEquals(180_000.0, now.toDouble(), 2_000.0)
    }
}
