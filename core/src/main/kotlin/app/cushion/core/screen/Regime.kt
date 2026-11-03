package app.cushion.core.screen

/** Volatility regime from VIX, which sets the target short-put delta and position size. */
enum class Regime(val label: String, val targetDelta: Double, val sizeFactor: Double) {
    CALM("Calm", 0.19, 1.0),
    NORMAL("Normal", 0.16, 1.0),
    ELEVATED("Elevated", 0.11, 0.5),
    STRESSED("Stressed", 0.08, 0.5),
    ;

    companion object {
        fun fromVix(vix: Double): Regime = when {
            vix < 14.0 -> CALM
            vix < 20.0 -> NORMAL
            vix < 28.0 -> ELEVATED
            else -> STRESSED
        }
    }
}
