package app.cushion.core.repo

import app.cushion.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Loading state for polled data. A failed poll keeps the last good value on screen. */
data class Live<T>(
    val value: T? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

/**
 * Fetches repeatedly while collected, which stops when the screen isn't visible.
 * Runs off the main thread because a full option chain is a lot of JSON to parse.
 */
fun <T> poll(interval: suspend () -> Long, fetch: suspend () -> T): Flow<Live<T>> = flow {
    var last: T? = null
    while (true) {
        val next = try {
            val value = fetch()
            last = value
            Live(value, null, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Live(last, e.userMessage(), false)
        }
        emit(next)
        delay(interval())
    }
}.flowOn(Dispatchers.Default)

fun Throwable.userMessage(): String = when (this) {
    is ApiException.AuthRequired -> message ?: "Sign in to Schwab to continue"
    is ApiException.RateLimited -> "Schwab's rate limit was reached. Data will resume shortly."
    is ApiException.Network -> "Can't reach Schwab. Check your connection."
    is ApiException -> message ?: "Schwab returned an error"
    else -> "Something went wrong loading data"
}
