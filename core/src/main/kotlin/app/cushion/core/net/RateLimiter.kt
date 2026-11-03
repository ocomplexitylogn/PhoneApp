package app.cushion.core.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token bucket shared by every Schwab call, so a background scan can't starve
 * the screen the user is looking at or trip the API's per-minute limit.
 */
class RateLimiter(
    private val permitsPerMinute: Int,
    private val burst: Int = 10,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var available = burst.toDouble()
    private var lastRefillMs = nowMs()

    /** Suspends until a call may proceed. */
    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                refill()
                if (available >= 1.0) {
                    available -= 1.0
                    0L
                } else {
                    val perMs = permitsPerMinute / 60_000.0
                    kotlin.math.ceil((1.0 - available) / perMs).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            sleep(waitMs)
        }
    }

    private fun refill() {
        val now = nowMs()
        val elapsed = (now - lastRefillMs).coerceAtLeast(0L)
        lastRefillMs = now
        available = (available + elapsed * permitsPerMinute / 60_000.0).coerceAtMost(burst.toDouble())
    }
}
