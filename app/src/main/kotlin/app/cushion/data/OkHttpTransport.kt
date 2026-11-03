package app.cushion.data

import app.cushion.core.net.HttpRequest
import app.cushion.core.net.HttpResponse
import app.cushion.core.net.HttpTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No logging interceptor on purpose: requests and responses carry bearer tokens. */
class OkHttpTransport : HttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val formType = "application/x-www-form-urlencoded".toMediaType()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.formBody?.toRequestBody(formType)
        builder.method(request.method, body)
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { r ->
                            HttpResponse(r.code, r.body?.string().orEmpty(), r.headers.associate { it.first to it.second })
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
