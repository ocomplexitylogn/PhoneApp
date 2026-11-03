package app.cushion.core

import app.cushion.core.format.Fmt
import app.cushion.core.model.EarningsEvent
import app.cushion.core.model.EarningsTiming
import app.cushion.core.model.OptionChain
import app.cushion.core.model.OptionContract
import app.cushion.core.model.PutCall
import app.cushion.core.model.Quote
import app.cushion.core.screen.EarningsInfo
import app.cushion.core.screen.IdeaFlag
import app.cushion.core.screen.Regime
import app.cushion.core.screen.ScreenConfig
import app.cushion.core.screen.Screener
import app.cushion.core.screen.ShortlistItem
import app.cushion.core.screen.SymbolContext
import app.cushion.core.screen.earningsBeforeExpiry
import app.cushion.core.screen.exposureOf
import app.cushion.core.screen.isSelloff
import app.cushion.core.screen.metricsFor
import app.cushion.core.screen.yearsToExpiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ScreenerTest {
    private val et = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 11, 2, 21, 30, 0, 0, et).toInstant().toEpochMilli()
    private val expiry30 = LocalDate.of(2026, 12, 4)

    private fun put(
        strike: Double,
        mid: Double,
        delta: Double? = -0.16,
        iv: Double? = 0.5,
        dte: Int = 32,
        expiry: LocalDate = expiry30,
        oi: Long = 1000,
        bid: Double = mid - 0.05,
        ask: Double = mid + 0.05,
        nonStandard: Boolean = false,
    ) = OptionContract(
        "X", "AMD", PutCall.PUT, strike, expiry, dte, bid, ask, null, mid, 100, oi, iv, delta, 0.01, -0.12, 0.2,
        false, 100, nonStandard, null,
    )

    private fun quote(last: Double = 100.0, change: Double = -0.5, subType: String = "COE", leverage: Double? = null) = Quote(
        "AMD", "AMD", last, change, change / last * 100, null, null, null, null, null, null, last - change, null, null, null,
        true, null, "EQUITY", subType, leverage,
    )

    private fun ctx(
        q: Quote = quote(),
        atr: Double? = 2.0,
        leverage: Double = 1.0,
        earnings: EarningsInfo = EarningsInfo(null, LocalDate.of(2026, 12, 31)),
    ) = SymbolContext(q, atr, 50.0, 0.4, 101.0, -1.0, leverage, earnings, "Semiconductors")

    private fun chain(vararg puts: OptionContract) = OptionChain("AMD", "SUCCESS", 100.0, 0.04, false, puts.toList(), emptyList())

    private fun screener(regime: Regime = Regime.NORMAL, nlv: Double? = null, config: ScreenConfig = ScreenConfig()) =
        Screener(config, regime, nlv, 5.0, { now })

    @Test fun `metrics reproduce the mock's chain row`() {
        val m = metricsFor(put(65.0, 4.05, delta = -0.336, iv = 2.19, dte = 4, expiry = LocalDate.of(2026, 11, 6)), 69.76, 0.04, null, now)
        assertEquals("-6.82%", Fmt.percent(m.distancePct))
        assertEquals("$4.05", Fmt.money(m.premium))
        assertEquals(60.95, m.breakeven, 1e-9)
        assertEquals(6500.0, m.collateral, 1e-9)
        assertEquals(4.05 / 65.0 * 100.0, m.yieldPct, 1e-9)
        assertEquals(m.yieldPct * 365.0 / 4.0, m.annualYieldPct, 1e-9)
        assertNull(m.cushionSigma)
    }

    @Test fun `every strike in the mock lands on its printed distance`() {
        for ((k, label) in listOf(60.0 to "-13.99%", 55.0 to "-21.16%", 50.0 to "-28.33%", 45.0 to "-35.49%")) {
            assertEquals(label, Fmt.percent(metricsFor(put(k, 1.0), 69.76, 0.04, null, now).distancePct))
        }
    }

    @Test fun `cushion follows the three-part definition`() {
        val m = metricsFor(put(90.0, 1.0, dte = 25), 100.0, 0.04, 2.0, now)
        assertEquals(5.5, m.cushionAtr!!, 1e-9) // (100 - 89) / 2
        assertEquals(11.0 / (2.0 * 5.0), m.cushionSigma!!, 1e-9) // ÷ ATR√25
        assertEquals(0.12 / 0.16, m.thetaDeltaBreakeven!!, 1e-9)
    }

    @Test fun `delta falls back to the model, chance falls back to delta`() {
        val noDelta = metricsFor(put(90.0, 1.0, delta = null), 100.0, 0.04, 2.0, now)
        assertNotNull(noDelta.absDelta)
        val noIv = metricsFor(put(90.0, 1.0, delta = -0.2, iv = null), 100.0, 0.04, 2.0, now)
        assertEquals(0.8, noIv.chanceWorthless!!, 1e-9)
        val neither = metricsFor(put(90.0, 1.0, delta = null, iv = null), 100.0, 0.04, 2.0, now)
        assertNull(neither.chanceWorthless)
    }

    @Test fun `years to expiry counts to the close and never reaches zero`() {
        val exp = LocalDate.of(2026, 11, 6)
        assertEquals(3.77 / 365.0, yearsToExpiry(exp, now), 0.002 / 365.0 + 0.01 / 365.0)
        assertTrue(yearsToExpiry(LocalDate.of(2026, 1, 1), now) > 0.0)
    }

    @Test fun `regime thresholds and sizes`() {
        assertEquals(Regime.CALM, Regime.fromVix(13.9))
        assertEquals(Regime.NORMAL, Regime.fromVix(14.0))
        assertEquals(Regime.NORMAL, Regime.fromVix(19.9))
        assertEquals(Regime.ELEVATED, Regime.fromVix(20.0))
        assertEquals(Regime.ELEVATED, Regime.fromVix(27.9))
        assertEquals(Regime.STRESSED, Regime.fromVix(28.0))
        assertEquals(0.5, Regime.ELEVATED.sizeFactor, 0.0)
    }

    @Test fun `picks only strikes in the regime's delta band`() {
        val out = screener(Regime.NORMAL).screen(ctx(), chain(put(90.0, 1.0, delta = -0.30), put(85.0, 0.8, delta = -0.16), put(80.0, 0.3, delta = -0.05)))
        assertEquals(listOf(85.0), out.ideas.map { it.contract.strike })
    }

    @Test fun `delta band moves with the regime`() {
        val puts = chain(put(90.0, 1.0, delta = -0.20), put(85.0, 0.8, delta = -0.11))
        assertEquals(listOf(90.0), screener(Regime.CALM).screen(ctx(), puts).ideas.map { it.contract.strike })
        assertEquals(listOf(85.0), screener(Regime.ELEVATED).screen(ctx(), puts).ideas.map { it.contract.strike })
    }

    @Test fun `liquidity filters remove a thin or wide contract`() {
        assertTrue(screener().screen(ctx(), chain(put(85.0, 0.8, oi = 5))).ideas.isEmpty())
        assertTrue(screener().screen(ctx(), chain(put(85.0, 0.8, bid = 0.5, ask = 1.1))).ideas.isEmpty())
        assertTrue(screener().screen(ctx(), chain(put(85.0, 0.8, bid = 0.0, ask = 0.1))).ideas.isEmpty())
        assertEquals("Strikes near target are too illiquid", screener().screen(ctx(), chain(put(85.0, 0.8, oi = 5))).rejection)
    }

    @Test fun `non-standard and out-of-window contracts are excluded`() {
        assertTrue(screener().screen(ctx(), chain(put(85.0, 0.8, nonStandard = true))).ideas.isEmpty())
        assertTrue(screener().screen(ctx(), chain(put(85.0, 0.8, dte = 3))).ideas.isEmpty())
        assertTrue(screener().screen(ctx(), chain(put(85.0, 0.8, dte = 60))).ideas.isEmpty())
    }

    @Test fun `cushion floor applies, with a higher floor for leveraged ETFs`() {
        // Breakeven 84.2: (100 - 84.2) / (2 × √32) = 1.40σ on ATR 2; 0.56σ on ATR 5.
        assertTrue(screener().screen(ctx(atr = 5.0), chain(put(85.0, 0.8))).ideas.isEmpty())
        assertEquals("Cushion is under 0.75σ", screener().screen(ctx(atr = 5.0), chain(put(85.0, 0.8))).rejection)
        // 0.93σ on ATR 3: clears 0.75 but not 1.00.
        assertEquals(1, screener().screen(ctx(atr = 3.0), chain(put(85.0, 0.8))).ideas.size)
        assertTrue(screener().screen(ctx(atr = 3.0, leverage = 3.0), chain(put(85.0, 0.8))).ideas.isEmpty())
    }

    @Test fun `missing ATR never passes the cushion rule`() {
        val out = screener().screen(ctx(atr = null), chain(put(85.0, 0.8)))
        assertTrue(out.ideas.isEmpty())
        assertEquals("Not enough price history for ATR", out.rejection)
    }

    @Test fun `earnings inside the trade blocks it, after-close on expiry day does not`() {
        val ev = EarningsEvent("AMD", null, LocalDate.of(2026, 11, 20), EarningsTiming.BEFORE_OPEN, null, null)
        val out = screener().screen(ctx(earnings = EarningsInfo(ev, LocalDate.of(2026, 12, 31))), chain(put(85.0, 0.8)))
        val idea = out.ideas.single()
        assertTrue(IdeaFlag.EARNINGS_BEFORE_EXPIRY in idea.flags)
        assertTrue(idea.blocked)

        val onExpiry = ev.copy(date = expiry30, timing = EarningsTiming.AFTER_CLOSE)
        assertFalse(earningsBeforeExpiry(onExpiry, expiry30))
        assertTrue(earningsBeforeExpiry(onExpiry.copy(timing = EarningsTiming.UNKNOWN), expiry30))
        assertTrue(earningsBeforeExpiry(onExpiry.copy(timing = EarningsTiming.BEFORE_OPEN), expiry30))
    }

    @Test fun `a calendar outage marks stocks unverified but not ETFs`() {
        val offline = EarningsInfo(null, null)
        val stock = screener().screen(ctx(earnings = offline), chain(put(85.0, 0.8))).ideas.single()
        assertTrue(IdeaFlag.EARNINGS_UNVERIFIED in stock.flags)
        assertFalse(stock.blocked)
        val etf = screener().screen(ctx(q = quote(subType = "ETF"), earnings = offline), chain(put(85.0, 0.8))).ideas.single()
        assertFalse(IdeaFlag.EARNINGS_UNVERIFIED in etf.flags)
    }

    @Test fun `calendar coverage short of expiry is also unverified`() {
        val partial = EarningsInfo(null, LocalDate.of(2026, 11, 20))
        val idea = screener().screen(ctx(earnings = partial), chain(put(85.0, 0.8))).ideas.single()
        assertTrue(IdeaFlag.EARNINGS_UNVERIFIED in idea.flags)
    }

    @Test fun `selloff gate fires on a sharp drop or a slide`() {
        assertTrue(isSelloff(ctx(q = quote(change = -3.1))))
        assertFalse(isSelloff(ctx(q = quote(change = -2.9))))
        val slide = SymbolContext(quote(last = 95.0, change = -0.1), 2.0, 50.0, 0.4, 100.0, -5.0, 1.0, EarningsInfo(null, null), "x")
        assertTrue(isSelloff(slide))
        assertFalse(isSelloff(slide.copy(sma20 = 90.0)))
        assertFalse(isSelloff(ctx(q = quote(change = -30.0), atr = null)))
    }

    @Test fun `today's SOXL move trips the selloff gate`() {
        val soxl = SymbolContext(quote(last = 69.76, change = -11.25), 6.5, 90.0, 1.2, 80.0, -15.0, 3.0, EarningsInfo(null, null), "Semiconductors")
        assertTrue(isSelloff(soxl))
    }

    @Test fun `sizing respects cap and regime`() {
        // 5% of 200k = 10k, one 85 strike takes 8.5k.
        val normal = screener(Regime.NORMAL, nlv = 200_000.0).screen(ctx(), chain(put(85.0, 0.8))).ideas.single()
        assertEquals(1, normal.maxContracts)
        assertFalse(IdeaFlag.OVER_NAME_CAP in normal.flags)
        // Half size: 5k budget can't hold one.
        val half = screener(Regime.ELEVATED, nlv = 200_000.0).screen(ctx(), chain(put(85.0, 0.8, delta = -0.11))).ideas.single()
        assertEquals(0, half.maxContracts)
        assertTrue(IdeaFlag.OVER_NAME_CAP in half.flags)
        assertTrue(IdeaFlag.HALF_SIZE in half.flags)
        assertNull(screener(nlv = null).screen(ctx(), chain(put(85.0, 0.8))).ideas.single().maxContracts)
    }

    @Test fun `one idea per expiry, capped per symbol`() {
        val e1 = LocalDate.of(2026, 11, 20); val e2 = LocalDate.of(2026, 11, 27); val e3 = LocalDate.of(2026, 12, 4)
        val out = screener().screen(
            ctx(),
            chain(
                put(85.0, 0.8, dte = 18, expiry = e1), put(84.0, 0.7, dte = 18, expiry = e1),
                put(85.0, 0.9, dte = 25, expiry = e2), put(85.0, 1.0, dte = 32, expiry = e3),
            ),
        )
        assertEquals(2, out.ideas.size)
        assertEquals(2, out.ideas.map { it.contract.expiration }.toSet().size)
    }

    @Test fun `exposure totals and cap breaches`() {
        val item = { s: String, k: Double, n: Int -> ShortlistItem("c", s, k, LocalDate.of(2026, 11, 20), 1.5, n, 0) }
        val r = exposureOf(listOf(item("SOXL", 60.0, 1), item("AMD", 200.0, 1), item("MU", 240.0, 2)), 200_000.0, 5.0, 25.0)
        assertEquals(74_000.0, r.totalCollateral, 1e-9)
        assertEquals(600.0, r.totalPremium, 1e-9)
        assertEquals(37.0, r.pctOfAccount!!, 1e-9)
        assertEquals("MU", r.byName.first().label)
        assertTrue(r.byName.first().overCap)
        assertFalse(r.byName.last().overCap)
        val semis = r.byGroup.single()
        assertEquals("Semiconductors", semis.label)
        assertEquals(37.0, semis.pctOfAccount!!, 1e-9)
        assertTrue(semis.overCap)
        assertNull(exposureOf(listOf(item("AMD", 200.0, 1)), null, 5.0, 25.0).pctOfAccount)
    }
}
