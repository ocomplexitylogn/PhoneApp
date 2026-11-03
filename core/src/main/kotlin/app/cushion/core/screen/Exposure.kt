package app.cushion.core.screen

import java.time.LocalDate

/** A contract the user is considering. Nothing here is an order or a live position. */
data class ShortlistItem(
    val contractSymbol: String,
    val underlying: String,
    val strike: Double,
    val expiration: LocalDate,
    val premiumWhenAdded: Double,
    val contracts: Int,
    val addedAtMs: Long,
) {
    val collateral: Double get() = strike * 100.0 * contracts
}

data class ExposureLine(
    val label: String,
    val collateral: Double,
    /** Percent of account value. Null without an account size. */
    val pctOfAccount: Double?,
    val capPct: Double,
    val overCap: Boolean,
)

data class ExposureReport(
    val totalCollateral: Double,
    val totalPremium: Double,
    val pctOfAccount: Double?,
    val byName: List<ExposureLine>,
    val byGroup: List<ExposureLine>,
)

fun exposureOf(
    items: List<ShortlistItem>,
    accountValue: Double?,
    perNameCapPct: Double,
    sectorCapPct: Double,
    groupOf: (String) -> String = Universe::groupOf,
): ExposureReport {
    val nlv = accountValue?.takeIf { it > 0.0 }
    fun lines(by: (ShortlistItem) -> String, cap: Double) = items.groupBy(by).map { (label, group) ->
        val collateral = group.sumOf { it.collateral }
        val pct = nlv?.let { collateral / it * 100.0 }
        ExposureLine(label, collateral, pct, cap, pct != null && pct > cap)
    }.sortedByDescending { it.collateral }

    val total = items.sumOf { it.collateral }
    return ExposureReport(
        totalCollateral = total,
        totalPremium = items.sumOf { it.premiumWhenAdded * 100.0 * it.contracts },
        pctOfAccount = nlv?.let { total / it * 100.0 },
        byName = lines({ it.underlying }, perNameCapPct),
        byGroup = lines({ groupOf(it.underlying) }, sectorCapPct),
    )
}
