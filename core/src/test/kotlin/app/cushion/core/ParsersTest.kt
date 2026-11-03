package app.cushion.core

import app.cushion.core.model.PutCall
import app.cushion.core.schwab.ChartRange
import app.cushion.core.schwab.Endpoints
import app.cushion.core.schwab.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParsersTest {
    private val quoteBody = """
        {"SOXL":{"assetMainType":"EQUITY","assetSubType":"ETF","symbol":"SOXL","realtime":true,
          "quote":{"lastPrice":69.76,"mark":69.76,"bidPrice":69.70,"askPrice":69.80,"closePrice":81.01,
                   "netChange":-11.25,"netPercentChange":-13.89,"totalVolume":90123456,"52WeekHigh":120.5,"quoteTime":1793660000000},
          "reference":{"description":"DIREXION DAILY SEMICONDUCTOR BULL 3X ETF"},
          "fundamental":{"fundLeverageFactor":3.0}},
         "errors":{"invalidSymbols":["NOPE"]}}
    """

    @Test fun `quote matches the mock's ticker header`() {
        val q = Parsers.quotes(quoteBody).getValue("SOXL")
        assertEquals(69.76, q.last, 1e-9)
        assertEquals(-11.25, q.change, 1e-9)
        assertEquals(-13.89, q.changePct, 1e-9)
        assertEquals(3.0, q.leverageFactor!!, 1e-9)
        assertTrue(q.isEtf)
        assertEquals("DIREXION DAILY SEMICONDUCTOR BULL 3X ETF", q.description)
    }

    @Test fun `errors block is not treated as a symbol`() {
        assertEquals(setOf("SOXL"), Parsers.quotes(quoteBody).keys)
    }

    @Test fun `change is derived when Schwab omits it`() {
        val q = Parsers.quotes("""{"X":{"symbol":"X","quote":{"lastPrice":90.0,"closePrice":100.0}}}""").getValue("X")
        assertEquals(-10.0, q.change, 1e-9)
        assertEquals(-10.0, q.changePct, 1e-9)
    }

    private val chainBody = """
        {"symbol":"SOXL","status":"SUCCESS","isDelayed":false,"underlyingPrice":69.76,"interestRate":4.25,
         "putExpDateMap":{"2026-11-06:4":{
           "65.0":[{"putCall":"PUT","symbol":"SOXL  261106P00065000","bid":4.00,"ask":4.10,"mark":4.05,"last":4.0,
                    "totalVolume":1200,"openInterest":5300,"volatility":219.2,"delta":-0.336,"gamma":0.02,"theta":-1.5,"vega":0.03,
                    "strikePrice":65.0,"daysToExpiration":4,"multiplier":100.0,"inTheMoney":false,"nonStandard":false,"quoteTimeInLong":1793660000000}],
           "70.0":[{"putCall":"PUT","symbol":"SOXL  261106P00070000","bidPrice":6.0,"askPrice":6.2,"markPrice":6.1,
                    "volatility":-999.0,"delta":NaN,"gamma":-999.0,"theta":"NaN",
                    "strikePrice":70.0,"daysToExpiration":4,"multiplier":100.0,"isInTheMoney":true,"isNonStandard":true}]
         }},
         "callExpDateMap":{}}
    """

    @Test fun `chain parses live field names`() {
        val c = Parsers.chain(chainBody)
        assertEquals(0.0425, c.interestRate, 1e-9)
        val p = c.puts.first { it.strike == 65.0 }
        assertEquals(PutCall.PUT, p.putCall)
        assertEquals(LocalDate.of(2026, 11, 6), p.expiration)
        assertEquals(4, p.dte)
        assertEquals(4.05, p.mid, 1e-9)
        assertEquals(2.192, p.iv!!, 1e-9)
        assertEquals(-0.336, p.delta!!, 1e-9)
        assertEquals(5300L, p.openInterest)
    }

    @Test fun `spec field names work and NaN or sentinel Greeks become null`() {
        val p = Parsers.chain(chainBody).puts.first { it.strike == 70.0 }
        assertEquals(6.0, p.bid, 1e-9)
        assertEquals(6.2, p.ask, 1e-9)
        assertTrue(p.inTheMoney)
        assertTrue(p.nonStandard)
        assertNull(p.iv)
        assertNull(p.delta)
        assertNull(p.gamma)
        assertNull(p.theta)
    }

    @Test fun `expirations accept either date key and sort`() {
        val list = Parsers.expirations(
            """{"expirationList":[{"expirationDate":"2026-11-13","daysToExpiration":11,"expirationType":"W","standard":true},
                                  {"expiration":"2026-11-06T21:00:00.000+00:00","daysToExpiration":4}]}""",
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 13)), list.map { it.date })
    }

    @Test fun `history skips rows with no close`() {
        val h = Parsers.history(
            """{"symbol":"SPY","empty":false,"previousClose":758.6,"candles":[
               {"open":1,"high":2,"low":0.5,"close":1.5,"volume":10,"datetime":1000},
               {"open":1,"high":2,"low":0.5,"volume":10,"datetime":2000}]}""",
            "SPY",
        )
        assertEquals(1, h.candles.size)
        assertEquals(758.6, h.previousClose!!, 1e-9)
    }

    @Test fun `closed market has no session times`() {
        val h = Parsers.marketHours(
            """{"equity":{"equity":{"date":"2026-11-26","marketType":"EQUITY","isOpen":false}}}""",
            LocalDate.of(2026, 11, 26),
        )!!
        assertFalse(h.isOpen)
        assertNull(h.regularStartMs)
    }

    @Test fun `open market session times parse with an offset`() {
        val h = Parsers.marketHours(
            """{"equity":{"EQ":{"date":"2026-11-02","isOpen":true,"sessionHours":{
                "preMarket":[{"start":"2026-11-02T07:00:00-05:00","end":"2026-11-02T09:30:00-05:00"}],
                "regularMarket":[{"start":"2026-11-02T09:30:00-05:00","end":"2026-11-02T16:00:00-05:00"}],
                "postMarket":[{"start":"2026-11-02T16:00:00-05:00","end":"2026-11-02T20:00:00-05:00"}]}}}}""",
            LocalDate.of(2026, 11, 2),
        )!!
        assertTrue(h.isOpen)
        assertEquals(6.5 * 3_600_000, (h.regularEndMs!! - h.regularStartMs!!).toDouble(), 0.0)
    }

    @Test fun `garbage body is a typed error, not a crash`() {
        try {
            Parsers.chain("<html>502</html>")
            throw AssertionError("expected failure")
        } catch (e: app.cushion.core.net.ApiException.Parse) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test fun `urls encode index and option symbols`() {
        assertTrue(Endpoints.quotes(listOf("\$VIX", "SOXL  261106P00065000")).contains("symbols=%24VIX%2CSOXL%20%20261106P00065000"))
    }

    @Test fun `chart ranges use values pricehistory accepts`() {
        val validPeriods = mapOf("day" to setOf(1, 2, 3, 4, 5, 10), "month" to setOf(1, 2, 3, 6), "year" to setOf(1, 2, 3, 5, 10, 15, 20))
        val validFrequencyTypes = mapOf("day" to setOf("minute"), "month" to setOf("daily", "weekly"), "year" to setOf("daily", "weekly", "monthly"))
        for (r in ChartRange.entries) {
            assertTrue(r.label, r.period in validPeriods.getValue(r.periodType))
            assertTrue(r.label, r.frequencyType in validFrequencyTypes.getValue(r.periodType))
            if (r.frequencyType == "minute") assertTrue(r.frequency in setOf(1, 5, 10, 15, 30)) else assertEquals(1, r.frequency)
        }
    }
}
