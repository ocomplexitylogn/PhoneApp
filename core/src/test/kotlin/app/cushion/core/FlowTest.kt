package app.cushion.core

import app.cushion.core.demo.DemoTokens
import app.cushion.core.demo.DemoTransport
import app.cushion.core.earnings.InMemoryDayCache
import app.cushion.core.earnings.NasdaqEarnings
import app.cushion.core.earnings.earningsWeek
import app.cushion.core.earnings.parseEarningsDay
import app.cushion.core.earnings.sortedForDisplay
import app.cushion.core.format.Fmt
import app.cushion.core.model.EarningsEvent
import app.cushion.core.model.EarningsTiming
import app.cushion.core.model.MarketHours
import app.cushion.core.model.PriceHistory
import app.cushion.core.model.Candle
import app.cushion.core.net.ApiException
import app.cushion.core.net.HttpResponse
import app.cushion.core.net.HttpTransport
import app.cushion.core.net.RateLimiter
import app.cushion.core.repo.MarketPhase
import app.cushion.core.repo.MarketRepository
import app.cushion.core.repo.Session
import app.cushion.core.repo.buildChart
import app.cushion.core.repo.marketDate
import app.cushion.core.repo.marketPhase
import app.cushion.core.repo.pollIntervalMs
import app.cushion.core.schwab.ChartRange
import app.cushion.core.schwab.SchwabClient
import app.cushion.core.screen.IdeaFlag
import app.cushion.core.screen.ScreenConfig
import app.cushion.core.screen.ShortlistItem
import app.cushion.core.screen.Universe
import app.cushion.core.screen.metricsFor
import app.cushion.core.settings.AppSettings
import app.cushion.core.settings.decodeSettings
import app.cushion.core.settings.decodeShortlist
import app.cushion.core.settings.encodeSettings
import app.cushion.core.settings.encodeShortlist
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class FlowTest {
    private val et = ZoneId.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, et).toInstant().toEpochMilli()

    private val monday = at(2026, 11, 2, 21, 30)

    private fun demoRepo(now: Long = monday): MarketRepository {
        val t = DemoTransport { now }
        return MarketRepository(
            SchwabClient(t, DemoTokens, RateLimiter(100_000, 1000)),
            NasdaqEarnings(t, InMemoryDayCache()) { marketDate(now) },
            { now },
        )
    }

    @Test fun `demo scan produces screened ideas through the real pipeline`() = runBlocking {
        var progress = 0
        val r = demoRepo().scan(Universe.defaultSymbols, ScreenConfig(), 500_000.0, 5.0) { d, _ -> progress = d }
        assertEquals(Universe.defaultSymbols.size, progress)
        assertTrue("failures: ${r.failures}", r.failures.isEmpty())
        assertTrue("rejections: ${r.rejections}", r.ideas.isNotEmpty())
        assertEquals(24.8, r.vix!!, 1e-9)
        assertEquals("Elevated", r.regime.label)
        for (i in r.ideas) {
            val m = i.metrics
            assertTrue(m.absDelta!! in 0.07..0.15)
            assertTrue(i.contract.dte in 7..45)
            assertTrue(i.contract.strike < m.spot)
            assertTrue(m.cushionSigma!! >= 0.75)
            assertTrue(IdeaFlag.HALF_SIZE in i.flags)
            assertTrue(m.chanceWorthless!! in 0.5..1.0)
        }
        assertTrue(r.topPremiums.none { it.blocked })
        assertEquals(r.topPremiums.map { it.metrics.premium }, r.topPremiums.map { it.metrics.premium }.sortedDescending())
    }

    @Test fun `an earnings name in the demo is blocked in the scan`() = runBlocking {
        val r = demoRepo().scan(listOf("AMD", "PLTR", "KO"), ScreenConfig(minCushionSigma = 0.0), null, 5.0)
        val amd = r.ideas.filter { it.contract.underlying == "AMD" }
        assertTrue(amd.isNotEmpty())
        assertTrue(amd.all { IdeaFlag.EARNINGS_BEFORE_EXPIRY in it.flags })
    }

    @Test fun `SOXL's demo day is blocked as a selloff when it clears the cushion`() = runBlocking {
        val r = demoRepo().scan(listOf("SOXL"), ScreenConfig(minCushionSigmaLeveraged = 0.0), null, 5.0)
        assertTrue(r.ideas.isNotEmpty())
        assertTrue(r.ideas.all { IdeaFlag.SELLOFF in it.flags && IdeaFlag.LEVERAGED in it.flags })
    }

    @Test fun `one symbol failing doesn't sink the scan`() = runBlocking {
        val demo = DemoTransport { monday }
        val t = HttpTransport { r ->
            if (r.url.contains("/chains") && r.url.contains("symbol=KO")) HttpResponse(400, "{}") else demo.execute(r)
        }
        val repo = MarketRepository(SchwabClient(t, DemoTokens, RateLimiter(100_000, 1000)), NasdaqEarnings(demo, InMemoryDayCache()) { marketDate(monday) }, { monday })
        val r = repo.scan(listOf("KO", "AAPL", "JPM"), ScreenConfig(), null, 5.0)
        assertEquals(setOf("KO"), r.failures.keys)
    }

    @Test fun `an expired session aborts the scan instead of showing it as per-symbol failures`() = runBlocking {
        val demo = DemoTransport { monday }
        val t = HttpTransport { r -> if (r.url.contains("/chains")) HttpResponse(401, "") else demo.execute(r) }
        val repo = MarketRepository(SchwabClient(t, DemoTokens, RateLimiter(100_000, 1000)), NasdaqEarnings(demo, InMemoryDayCache()) { marketDate(monday) }, { monday })
        try {
            repo.scan(listOf("KO", "AAPL"), ScreenConfig(), null, 5.0)
            fail("expected AuthRequired")
        } catch (_: ApiException.AuthRequired) {
        }
    }

    @Test fun `demo ticker flow matches the mock's numbers`() = runBlocking {
        val repo = demoRepo()
        val q = repo.quote("SOXL")!!
        assertEquals("$69.76", Fmt.money(q.last))
        assertEquals("-11.25", Fmt.signedNumber(q.change))
        assertEquals("-13.89%", Fmt.percent(q.changePct))
        val exp = repo.expirations("SOXL")
        assertEquals(LocalDate.of(2026, 11, 6), exp.first().date)
        assertEquals("Nov 6", Fmt.monthDay(exp.first().date))
        assertEquals("Fri", Fmt.weekday(exp.first().date))
        val chain = repo.fullChain("SOXL", exp.first().date)
        assertTrue(chain.puts.isNotEmpty() && chain.calls.isNotEmpty())
        val m = metricsFor(chain.puts.first { it.strike == 65.0 }, q.last, chain.interestRate, null, monday)
        assertEquals("-6.82%", Fmt.percent(m.distancePct))
        assertNotNull(m.chanceWorthless)
    }

    @Test fun `shortlist option quotes resolve`() = runBlocking {
        val repo = demoRepo()
        val c = repo.fullChain("SOXL", LocalDate.of(2026, 11, 20)).puts.first { it.strike == 60.0 }
        val q = repo.quotes(listOf(c.symbol)).getValue(c.symbol)
        assertTrue(q.mark!! > 0.0)
        assertTrue(q.delta!! < 0.0)
    }

    @Test fun `search finds tickers and names, and ignores regex input`() = runBlocking {
        val repo = demoRepo()
        assertEquals("SOXL", repo.search("sox").first().symbol)
        assertTrue(repo.search("micron").any { it.symbol == "MU" })
        assertTrue(repo.search("   ").isEmpty())
        assertTrue(repo.search("(").isEmpty())
    }

    @Test fun `1D chart splits sessions and keeps only the latest date`() {
        val day = at(2026, 11, 2, 0)
        val prior = at(2026, 10, 30, 12)
        fun c(t: Long, p: Double) = Candle(t, p, p, p, p, 0)
        val h = PriceHistory(
            "SOXL",
            listOf(
                c(prior, 90.0),
                c(at(2026, 11, 2, 8), 80.0),
                c(at(2026, 11, 2, 9, 30), 79.0),
                c(at(2026, 11, 2, 15, 55), 70.0),
                c(at(2026, 11, 2, 16), 69.8),
                c(at(2026, 11, 2, 19), 69.76),
            ),
            81.01,
        )
        val chart = buildChart(h, ChartRange.D1, null)
        assertEquals(listOf(Session.PRE, Session.REGULAR, Session.REGULAR, Session.POST, Session.POST), chart.points.map { it.session })
        assertEquals(-11.25, chart.change, 1e-9)
        assertEquals(-13.8871, chart.changePct, 1e-3)
        assertTrue(chart.isDown)
        assertTrue(day > 0)
    }

    @Test fun `half-day close from Schwab moves the post-market boundary`() {
        val hours = MarketHours(LocalDate.of(2026, 11, 27), true, null, at(2026, 11, 27, 9, 30), at(2026, 11, 27, 13), null)
        val h = PriceHistory("SPY", listOf(Candle(at(2026, 11, 27, 13, 30), 1.0, 1.0, 1.0, 1.0, 0)), null)
        assertEquals(Session.POST, buildChart(h, ChartRange.D1, hours).points.single().session)
    }

    @Test fun `weekend 1D chart still classifies Friday's bars`() {
        val h = PriceHistory("SPY", listOf(Candle(at(2026, 10, 31, 12), 1.0, 1.0, 1.0, 1.0, 0)), null)
        assertEquals(Session.REGULAR, buildChart(h, ChartRange.D1, null).points.single().session)
    }

    @Test fun `longer ranges measure from their first point`() {
        val h = PriceHistory("SPY", listOf(Candle(1, 100.0, 100.0, 100.0, 100.0, 0), Candle(2, 110.0, 110.0, 110.0, 110.0, 0)), 99.0)
        val c = buildChart(h, ChartRange.M1, null)
        assertEquals(10.0, c.change, 1e-9)
        assertEquals(10.0, c.changePct, 1e-9)
        assertFalse(c.isDown)
    }

    @Test fun `empty history doesn't crash the chart`() {
        val c = buildChart(PriceHistory("SPY", emptyList(), null), ChartRange.D1, null)
        assertTrue(c.points.isEmpty())
    }

    @Test fun `market phases and polling cadence`() {
        assertEquals(MarketPhase.PRE, marketPhase(at(2026, 11, 2, 8), null))
        assertEquals(MarketPhase.OPEN, marketPhase(at(2026, 11, 2, 9, 30), null))
        assertEquals(MarketPhase.POST, marketPhase(at(2026, 11, 2, 16), null))
        assertEquals(MarketPhase.CLOSED, marketPhase(at(2026, 11, 2, 21), null))
        assertEquals(MarketPhase.CLOSED, marketPhase(at(2026, 11, 1, 12), null)) // Sunday
        val holiday = MarketHours(LocalDate.of(2026, 11, 26), false, null, null, null, null)
        assertEquals(MarketPhase.CLOSED, marketPhase(at(2026, 11, 26, 12), holiday))
        assertTrue(pollIntervalMs(MarketPhase.OPEN) < pollIntervalMs(MarketPhase.POST))
        assertTrue(pollIntervalMs(MarketPhase.POST) < pollIntervalMs(MarketPhase.CLOSED))
    }

    @Test fun `nasdaq rows parse, including null rows on quiet days`() {
        val d = LocalDate.of(2026, 11, 2)
        val rows = parseEarningsDay(
            """{"data":{"asOf":"x","rows":[
                {"symbol":"AAPL","name":"Apple Inc.","time":"time-after-hours","marketCap":"$3,912,000,000,000","epsForecast":"$1.75"},
                {"symbol":"ADTN ","name":"ADTRAN","time":"time-not-supplied","marketCap":"N/A","epsForecast":""}]},"status":{"rCode":200}}""",
            d,
        )!!
        assertEquals(EarningsTiming.AFTER_CLOSE, rows[0].timing)
        assertEquals(3.912e12, rows[0].marketCapUsd!!, 1.0)
        assertEquals("ADTN", rows[1].symbol)
        assertNull(rows[1].marketCapUsd)
        assertNull(rows[1].epsForecast)
        assertEquals(emptyList<EarningsEvent>(), parseEarningsDay("""{"data":{"rows":null}}""", d))
        assertNull(parseEarningsDay("""{"data":null,"status":{"rCode":400}}""", d))
        assertNull(parseEarningsDay("<html>blocked</html>", d))
    }

    @Test fun `a calendar gap stops coverage at the last good day`() = runBlocking {
        val failDate = "2026-11-04"
        val calls = ArrayList<String>()
        val t = HttpTransport { r ->
            calls += r.url
            if (r.url.endsWith(failDate)) throw IOException("blocked") else HttpResponse(200, """{"data":{"rows":[]}}""")
        }
        val n = NasdaqEarnings(t, InMemoryDayCache()) { LocalDate.of(2026, 11, 2) }
        val s = n.range(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))
        assertEquals(LocalDate.of(2026, 11, 3), s.coveredThrough)
        assertTrue("weekends are skipped", calls.none { it.endsWith("2026-11-07") || it.endsWith("2026-11-08") })
    }

    @Test fun `a successful calendar day is cached`() = runBlocking {
        var calls = 0
        val t = HttpTransport { calls++; HttpResponse(200, """{"data":{"rows":[]}}""") }
        val n = NasdaqEarnings(t, InMemoryDayCache()) { LocalDate.of(2026, 11, 2) }
        n.range(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        n.range(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        assertEquals(2, calls)
    }

    @Test fun `earnings week spans today to Friday, or the next week on weekends`() {
        assertEquals(LocalDate.of(2026, 11, 2) to LocalDate.of(2026, 11, 6), earningsWeek(LocalDate.of(2026, 11, 2)))
        assertEquals(LocalDate.of(2026, 11, 6) to LocalDate.of(2026, 11, 6), earningsWeek(LocalDate.of(2026, 11, 6)))
        val sat = earningsWeek(LocalDate.of(2026, 11, 7))
        assertEquals(DayOfWeek.MONDAY, sat.first.dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 9) to LocalDate.of(2026, 11, 13), sat)
        assertEquals(LocalDate.of(2026, 11, 9) to LocalDate.of(2026, 11, 13), earningsWeek(LocalDate.of(2026, 11, 8)))
    }

    @Test fun `earnings sort by day then size`() {
        val d = LocalDate.of(2026, 11, 2)
        val list = listOf(
            EarningsEvent("ZZZ", null, d, EarningsTiming.UNKNOWN, 1e9, null),
            EarningsEvent("AAPL", null, d, EarningsTiming.UNKNOWN, 3e12, null),
            EarningsEvent("BBB", null, d.plusDays(1), EarningsTiming.UNKNOWN, 9e12, null),
            EarningsEvent("AAA", null, d, EarningsTiming.UNKNOWN, null, null),
        ).sortedForDisplay()
        assertEquals(listOf("AAPL", "ZZZ", "AAA", "BBB"), list.map { it.symbol })
    }

    @Test fun `settings round trip and tolerate old saves`() {
        val s = AppSettings(screen = ScreenConfig(minDte = 10, minOpenInterest = 250), universe = listOf("AMD"), accountValue = 123456.0, demoMode = true)
        assertEquals(s, decodeSettings(encodeSettings(s)))
        assertEquals(AppSettings(), decodeSettings(null))
        assertEquals(AppSettings(), decodeSettings("not json"))
        assertEquals(30, decodeSettings("""{"minDte":30}""").screen.minDte)
    }

    @Test fun `shortlist round trips and drops corrupt rows`() {
        val i = ShortlistItem("SOXL  261106P00065000", "SOXL", 65.0, LocalDate.of(2026, 11, 6), 4.05, 2, 99L)
        assertEquals(listOf(i), decodeShortlist(encodeShortlist(listOf(i))))
        assertEquals(emptyList<ShortlistItem>(), decodeShortlist("""[{"bad":1}]"""))
        assertEquals(emptyList<ShortlistItem>(), decodeShortlist(null))
    }

    @Test fun `formatting matches the mock`() {
        assertEquals("$752.88", Fmt.money(752.88))
        assertEquals("$1,234.50", Fmt.money(1234.5))
        assertEquals("-$5.00", Fmt.money(-5.0))
        assertEquals("0.76%", Fmt.unsignedPercent(-0.76))
        assertEquals("$65", Fmt.strike(65.0))
        assertEquals("$62.50", Fmt.strike(62.5))
        assertEquals("60%", Fmt.wholePercent(0.6))
        assertEquals("1.4M", Fmt.compact(1_400_000))
        assertEquals("950", Fmt.compact(950))
    }
}

class LiveTest {
    private val monday = java.time.ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    @Test fun `a failed poll keeps the last value and reports the error`() = runBlocking {
        var n = 0
        val states = app.cushion.core.repo.poll(interval = { 1L }) {
            n++
            if (n == 2) throw ApiException.RateLimited(null)
            n
        }.take(3).toList()
        assertEquals(1, states[0].value)
        assertNull(states[0].error)
        assertEquals(1, states[1].value)
        assertNotNull(states[1].error)
        assertEquals(3, states[2].value)
        assertNull(states[2].error)
    }

    @Test fun `scan store dedupes, respects staleness and can be invalidated`() = runBlocking {
        var now = monday
        val demo = DemoTransport { now }
        val repo = MarketRepository(SchwabClient(demo, DemoTokens, RateLimiter(100_000, 1000)), NasdaqEarnings(demo, InMemoryDayCache()) { marketDate(now) }, { now })
        val store = app.cushion.core.repo.ScanStore(this, { repo }, { AppSettings(universe = listOf("KO", "JPM")) }, { now })
        store.refresh()
        store.refresh()
        store.state.first { it.result != null && !it.running }
        val firstAt = store.state.value.result!!.scannedAtMs
        now += 60_000
        store.refresh()
        kotlinx.coroutines.yield()
        assertEquals(firstAt, store.state.value.result!!.scannedAtMs)
        now += 10 * 60_000
        store.refresh()
        store.state.first { it.result?.scannedAtMs == now && !it.running }
        store.invalidate()
        assertNull(store.state.value.result)
    }
}

class PartialCalendarTest {
    @Test fun `a partial calendar is retried soon, a full one is held`() = runBlocking {
        var now = java.time.ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val demo = DemoTransport { now }
        var nasdaqDown = true
        var nasdaqCalls = 0
        val t = HttpTransport { r ->
            if (r.url.contains("nasdaq")) {
                nasdaqCalls++
                if (nasdaqDown) throw IOException("blocked")
            }
            demo.execute(r)
        }
        val repo = MarketRepository(
            SchwabClient(demo, DemoTokens, RateLimiter(100_000, 1000)),
            NasdaqEarnings(t, InMemoryDayCache()) { marketDate(now) },
            { now },
        )
        val config = ScreenConfig(minDte = 7, maxDte = 14)
        assertFalse(repo.scan(listOf("KO"), config, null, 5.0).earningsAvailable)
        nasdaqDown = false
        now += 60_000
        assertFalse("still inside the retry window", repo.scan(listOf("KO"), config, null, 5.0).earningsAvailable)
        now += 5 * 60_000
        assertTrue(repo.scan(listOf("KO"), config, null, 5.0).earningsAvailable)
        val afterRecovery = nasdaqCalls
        now += 30 * 60_000
        assertTrue(repo.scan(listOf("KO"), config, null, 5.0).earningsAvailable)
        assertEquals("a complete calendar isn't refetched", afterRecovery, nasdaqCalls)
    }
}
