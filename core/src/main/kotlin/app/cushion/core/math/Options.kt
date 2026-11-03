package app.cushion.core.math

import app.cushion.core.model.PutCall
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Standard normal CDF (Hart, via West 2005). Accurate to double precision in practice. */
fun normCdf(x: Double): Double {
    val a = abs(x)
    val c: Double
    if (a > 37.0) {
        c = 0.0
    } else {
        val e = exp(-a * a / 2.0)
        if (a < 7.07106781186547) {
            var b = 3.52624965998911e-02 * a + 0.700383064443688
            b = b * a + 6.37396220353165
            b = b * a + 33.912866078383
            b = b * a + 112.079291497871
            b = b * a + 221.213596169931
            b = b * a + 220.206867912376
            val n = e * b
            var d = 8.83883476483184e-02 * a + 1.75566716318264
            d = d * a + 16.064177579207
            d = d * a + 86.7807322029461
            d = d * a + 296.564248779674
            d = d * a + 637.333633378831
            d = d * a + 793.826512519948
            d = d * a + 440.413735824752
            c = n / d
        } else {
            var b = a + 0.65
            b = a + 4.0 / b
            b = a + 3.0 / b
            b = a + 2.0 / b
            b = a + 1.0 / b
            c = e / b / 2.506628274631
        }
    }
    return if (x > 0.0) 1.0 - c else c
}

object BlackScholes {
    private fun d1(s: Double, k: Double, t: Double, r: Double, v: Double) =
        (ln(s / k) + (r + v * v / 2.0) * t) / (v * sqrt(t))

    fun price(side: PutCall, s: Double, k: Double, t: Double, r: Double, v: Double): Double {
        if (t <= 0.0 || v <= 0.0) {
            return if (side == PutCall.PUT) (k - s).coerceAtLeast(0.0) else (s - k).coerceAtLeast(0.0)
        }
        val d1 = d1(s, k, t, r, v)
        val d2 = d1 - v * sqrt(t)
        return if (side == PutCall.PUT) {
            k * exp(-r * t) * normCdf(-d2) - s * normCdf(-d1)
        } else {
            s * normCdf(d1) - k * exp(-r * t) * normCdf(d2)
        }
    }

    fun delta(side: PutCall, s: Double, k: Double, t: Double, r: Double, v: Double): Double {
        if (t <= 0.0 || v <= 0.0) {
            val itm = if (side == PutCall.PUT) s < k else s > k
            return if (!itm) 0.0 else if (side == PutCall.PUT) -1.0 else 1.0
        }
        val n = normCdf(d1(s, k, t, r, v))
        return if (side == PutCall.PUT) n - 1.0 else n
    }

    /** Risk-neutral probability the option finishes out of the money: N(d2) for a put. */
    fun probExpiresWorthless(side: PutCall, s: Double, k: Double, t: Double, r: Double, v: Double): Double {
        if (t <= 0.0 || v <= 0.0) {
            return if (side == PutCall.PUT) (if (s >= k) 1.0 else 0.0) else (if (s <= k) 1.0 else 0.0)
        }
        val d2 = d1(s, k, t, r, v) - v * sqrt(t)
        return if (side == PutCall.PUT) normCdf(d2) else normCdf(-d2)
    }
}
