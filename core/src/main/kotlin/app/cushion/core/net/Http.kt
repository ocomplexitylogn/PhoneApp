package app.cushion.core.net

/** Minimal HTTP seam. The app implements it with OkHttp; demo mode and tests fake it. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Form-encoded body, when present. */
    val formBody: String? = null,
)

data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccess: Boolean get() = code in 200..299

    /** Case-insensitive header lookup. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface HttpTransport {
    /** @throws java.io.IOException on connection failure. */
    suspend fun execute(request: HttpRequest): HttpResponse
}

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The Schwab session is gone and the user has to sign in again. */
    class AuthRequired(message: String) : ApiException(message)

    class RateLimited(val retryAfterMs: Long?) : ApiException("Schwab rate limit reached")

    class Http(val code: Int, message: String) : ApiException(message)

    class Network(cause: Throwable) : ApiException("Network unavailable", cause)

    class Parse(message: String, cause: Throwable? = null) : ApiException(message, cause)
}
