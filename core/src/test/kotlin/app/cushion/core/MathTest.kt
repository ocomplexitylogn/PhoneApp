package app.cushion.core

import app.cushion.core.math.BlackScholes
import app.cushion.core.math.atr
import app.cushion.core.math.atrPercentile
import app.cushion.core.math.normCdf
import app.cushion.core.math.realizedVol
import app.cushion.core.math.sma
import app.cushion.core.model.Candle
import app.cushion.core.model.PutCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class MathTest {
    private fun c(h: Double, l: Double, close: Double) = Candle(0, close, h, l, close, 0)

    @Test fun `normal cdf reference values`() {
        assertEquals(0.5, normCdf(0.0), 1e-12)
        assertEquals(0.8413447460685429, normCdf(1.0), 1e-12)
        assertEquals(0.9750021048517795, normCdf(1.96), 1e-12)
        assertEquals(0.0227501319481792, normCdf(-2.0), 1e-12)
        assertEquals(1.0, normCdf(40.0), 0.0)
        assertEquals(0.0, normCdf(-40.0), 0.0)
    }

    @Test fun `black scholes textbook value and parity`() {
        // Hull: S=42, K=40, r=10%, v=20%, T=0.5.
        assertEquals(4.76, BlackScholes.price(PutCall.CALL, 42.0, 40.0, 0.5, 0.10, 0.20), 0.01)
        assertEquals(0.81, BlackScholes.price(PutCall.PUT, 42.0, 40.0, 0.5, 0.10, 0.20), 0.01)
        val call = BlackScholes.price(PutCall.CALL, 100.0, 95.0, 0.25, 0.04, 0.5)
        val put = BlackScholes.price(PutCall.PUT, 100.0, 95.0, 0.25, 0.04, 0.5)
        assertEquals(100.0 - 95.0 * kotlin.math.exp(-0.04 * 0.25), call - put, 1e-9)
    }

    @Test fun `chance worthless is complementary across sides`() {
        val p = BlackScholes.probExpiresWorthless(PutCall.PUT, 69.76, 65.0, 4 / 365.0, 0.04, 2.19)
        val c = BlackScholes.probExpiresWorthless(PutCall.CALL, 69.76, 65.0, 4 / 365.0, 0.04, 2.19)
        assertEquals(1.0, p + c, 1e-12)
        // Lands near the mock's 60% for this strike.
        assertEquals(0.58, p, 0.01)
    }

    @Test fun `delta bounds and expiry handling`() {
        assertEquals(-0.5, BlackScholes.delta(PutCall.PUT, 100.0, 100.0, 1e-9, 0.0, 0.5), 0.01)
        assertEquals(0.0, BlackScholes.delta(PutCall.PUT, 100.0, 90.0, 0.0, 0.0, 0.5), 0.0)
        assertEquals(-1.0, BlackScholes.delta(PutCall.PUT, 80.0, 90.0, 0.0, 0.0, 0.5), 0.0)
        assertEquals(1.0, BlackScholes.probExpiresWorthless(PutCall.PUT, 100.0, 90.0, 0.0, 0.0, 0.5), 0.0)
    }

    @Test fun `atr uses true range across gaps and wilder smoothing`() {
        val candles = listOf(
            c(10.0, 9.0, 9.5),
            c(12.0, 11.0, 11.5), // gap: TR = 12 - 9.5 = 2.5
            c(12.0, 11.0, 11.2), // TR = 1
            c(11.0, 10.0, 10.5), // TR = 1.2
        )
        assertEquals((2.5 + 1.0) / 2, atr(candles.take(3), 2)!!, 1e-9)
        assertEquals((1.75 * 1 + 1.2) / 2, atr(candles, 2)!!, 1e-9)
        assertNull(atr(candles, 14))
    }

    @Test fun `realised vol matches a known alternating series`() {
        val closes = (0..20).map { if (it % 2 == 0) 100.0 else 101.0 }
        val v = realizedVol(closes.map { c(it, it, it) }, 20)!!
        val step = kotlin.math.ln(101.0 / 100.0)
        val expected = sqrt(step * step * 20.0 / 19.0 * 252.0)
        assertEquals(expected, v, 1e-6)
    }

    @Test fun `atr percentile finds the top of a rising range`() {
        val candles = (0 until 60).map { i -> c(100.0 + i * 0.2, 100.0 - i * 0.2, 100.0) }
        assertEquals(100.0, atrPercentile(candles)!!, 0.0)
        assertNull(atrPercentile(candles.take(20)))
    }

    @Test fun `sma needs a full window`() {
        val candles = (1..5).map { c(it.toDouble(), it.toDouble(), it.toDouble()) }
        assertEquals(4.0, sma(candles, 3)!!, 1e-9)
        assertNull(sma(candles, 6))
        assertNotNull(sma(candles, 5))
    }
}
