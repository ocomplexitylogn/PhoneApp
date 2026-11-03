package app.cushion.core.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Display strings, kept in one place so every screen writes numbers the same way. */
object Fmt {
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.US)

    fun money(value: Double): String {
        val body = String.format(Locale.US, "%,.2f", abs(value))
        return if (value < 0) "-$$body" else "$$body"
    }

    fun wholeMoney(value: Double): String {
        val body = String.format(Locale.US, "%,.0f", abs(value))
        return if (value < 0) "-$$body" else "$$body"
    }

    /** `$65` for whole strikes and `$62.50` otherwise. */
    fun strike(value: Double): String =
        if (value % 1.0 == 0.0) String.format(Locale.US, "$%,.0f", value) else String.format(Locale.US, "$%,.2f", value)

    fun signedNumber(value: Double): String {
        val body = String.format(Locale.US, "%,.2f", abs(value))
        return if (value < 0) "-$body" else "+$body"
    }

    fun percent(value: Double): String = String.format(Locale.US, "%.2f%%", value)

    fun unsignedPercent(value: Double): String = String.format(Locale.US, "%.2f%%", abs(value))

    fun wholePercent(fraction: Double): String = String.format(Locale.US, "%.0f%%", fraction * 100.0)

    fun oneDecimalPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    fun sigma(value: Double): String = String.format(Locale.US, "%.2fσ", value)

    fun decimal(value: Double, places: Int = 2): String = String.format(Locale.US, "%.${places}f", value)

    fun compact(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1e9)
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1e6)
        value >= 10_000 -> String.format(Locale.US, "%.1fK", value / 1e3)
        else -> String.format(Locale.US, "%,d", value)
    }

    fun monthDay(date: LocalDate): String = date.format(monthDay)

    fun weekday(date: LocalDate): String = date.format(weekday)
}
