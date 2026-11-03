package app.cushion.core.auth

import app.cushion.core.net.ApiException
import app.cushion.core.net.HttpRequest
import app.cushion.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

const val SCHWAB_AUTHORIZE_URL = "https://api.schwabapi.com/v1/oauth/authorize"
const val SCHWAB_TOKEN_URL = "https://api.schwabapi.com/v1/oauth/token"

/** Schwab invalidates a refresh token seven days after it is issued. */
const val REFRESH_TOKEN_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000

/** Refresh the access token this long before it actually expires. */
private const val ACCESS_TOKEN_LEEWAY_MS = 5L * 60 * 1000

data class SchwabCredentials(
    val appKey: String,
    val appSecret: String,
    val callbackUrl: String,
)

data class TokenSet(
    val accessToken: String?,
    val accessExpiresAtMs: Long,
    val refreshToken: String,
    /** When the refresh token was issued; null if the user pasted a bare token. */
    val refreshIssuedAtMs: Long?,
)

data class StoredSession(
    val credentials: SchwabCredentials,
    val tokens: TokenSet?,
)

/** Encrypted persistence for the session. The app backs this with Android Keystore. */
interface SecretStore {
    suspend fun read(): StoredSession?
    suspend fun write(session: StoredSession?)
}

sealed interface AuthState {
    data object Loading : AuthState

    /** No app key, secret or token yet. */
    data object NotConfigured : AuthState

    /** Credentials are saved but the refresh token has expired or been revoked. */
    data class SignInRequired(val reason: String) : AuthState

    data class Ready(val refreshExpiresAtMs: Long?) : AuthState
}

/** What [app.cushion.core.schwab.SchwabClient] needs from whichever auth is in use. */
interface AccessTokenProvider {
    /**
     * @param rejected a token the server just refused. It forces a refresh, unless
     * another caller has already replaced that token.
     */
    suspend fun accessToken(rejected: String? = null): String
}

class TokenManager(
    private val transport: HttpTransport,
    private val store: SecretStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AccessTokenProvider {
    private val mutex = Mutex()
    private var session: StoredSession? = null
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Loads the saved session. Call once at start-up. */
    suspend fun load() = mutex.withLock {
        session = store.read()
        publishState()
    }

    val credentials: SchwabCredentials? get() = session?.credentials

    override suspend fun accessToken(rejected: String?): String = mutex.withLock {
        val current = session
            ?: throw ApiException.AuthRequired("Connect your Schwab account in Settings")
        val tokens = current.tokens
            ?: throw ApiException.AuthRequired("Sign in to Schwab to continue")
        val cached = tokens.accessToken
        val stillGood = cached != null &&
            cached != rejected &&
            nowMs() < tokens.accessExpiresAtMs - ACCESS_TOKEN_LEEWAY_MS
        if (stillGood) cached!! else refresh(current, tokens)
    }

    /** Must be called with [mutex] held, so concurrent callers share one refresh. */
    private suspend fun refresh(current: StoredSession, tokens: TokenSet): String {
        val response = postToken(
            current.credentials,
            "grant_type=refresh_token&refresh_token=${encode(tokens.refreshToken)}",
        )
        if (!response.isSuccess) {
            if (isDeadRefreshToken(response.code, response.body)) {
                // Keep the app key and secret: only a fresh sign-in is needed.
                val reason = "Your Schwab sign-in has expired"
                session = current.copy(tokens = null)
                safeWrite()
                _state.value = AuthState.SignInRequired(reason)
                throw ApiException.AuthRequired(reason)
            }
            throw ApiException.Http(response.code, "Schwab sign-in service error (${response.code})")
        }
        val json = parseTokenBody(response.body)
        val access = json.string("access_token")
            ?: throw ApiException.Parse("Schwab returned no access token")
        val updated = tokens.copy(
            accessToken = access,
            accessExpiresAtMs = nowMs() + (json.long("expires_in") ?: 1800L) * 1000,
            refreshToken = json.string("refresh_token") ?: tokens.refreshToken,
        )
        session = current.copy(tokens = updated)
        safeWrite()
        publishState()
        return access
    }

    /** Saves new app credentials, keeping any existing tokens only if the key is unchanged. */
    suspend fun saveCredentials(credentials: SchwabCredentials) = mutex.withLock {
        val previous = session
        val keep = previous?.tokens?.takeIf { previous.credentials.appKey == credentials.appKey }
        session = StoredSession(credentials, keep)
        safeWrite()
        publishState()
    }

    /**
     * Installs a refresh token pasted by the user. Accepts a bare token, or the
     * token file written by schwab-py or schwabdev, whose timestamps give an exact
     * expiry. Validates the token by refreshing before reporting success.
     */
    suspend fun importRefreshToken(pasted: String) = mutex.withLock {
        val current = session
            ?: throw ApiException.AuthRequired("Enter your app key and secret first")
        val parsed = parseImportedToken(pasted)
        val tokens = TokenSet(null, 0L, parsed.refreshToken, parsed.issuedAtMs)
        refresh(current, tokens)
        Unit
    }

    fun authorizeUrl(state: String): String {
        val credentials = session?.credentials
            ?: throw ApiException.AuthRequired("Enter your app key and secret first")
        return buildAuthorizeUrl(credentials, state)
    }

    /** Completes sign-in from the callback URL Schwab redirected to. */
    suspend fun completeSignIn(callbackUrl: String, expectedState: String?) = mutex.withLock {
        val current = session
            ?: throw ApiException.AuthRequired("Enter your app key and secret first")
        val code = parseCallback(callbackUrl, expectedState)
        val body = "grant_type=authorization_code&code=${encode(code)}" +
            "&redirect_uri=${encode(current.credentials.callbackUrl)}"
        val response = postToken(current.credentials, body)
        if (!response.isSuccess) {
            throw ApiException.Http(response.code, "Schwab rejected the sign-in (${response.code})")
        }
        val json = parseTokenBody(response.body)
        val refreshToken = json.string("refresh_token")
            ?: throw ApiException.Parse("Schwab returned no refresh token")
        session = current.copy(
            tokens = TokenSet(
                accessToken = json.string("access_token"),
                accessExpiresAtMs = nowMs() + (json.long("expires_in") ?: 1800L) * 1000,
                refreshToken = refreshToken,
                refreshIssuedAtMs = nowMs(),
            ),
        )
        safeWrite()
        publishState()
    }

    /** Forgets everything, including the app key and secret. */
    suspend fun signOut() = mutex.withLock {
        session = null
        store.write(null)
        publishState()
    }

    private suspend fun postToken(credentials: SchwabCredentials, form: String) = try {
        val basic = Base64.getEncoder()
            .encodeToString("${credentials.appKey}:${credentials.appSecret}".toByteArray(Charsets.UTF_8))
        transport.execute(
            HttpRequest(
                method = "POST",
                url = SCHWAB_TOKEN_URL,
                headers = mapOf("Authorization" to "Basic $basic"),
                formBody = form,
            ),
        )
    } catch (e: IOException) {
        throw ApiException.Network(e)
    }

    private suspend fun safeWrite() {
        // A failed save must not end the session: the token still works in memory.
        try {
            store.write(session)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun publishState() {
        val s = session
        _state.value = when {
            s == null -> AuthState.NotConfigured
            s.tokens == null -> AuthState.SignInRequired("Sign in to Schwab to continue")
            else -> AuthState.Ready(s.tokens.refreshIssuedAtMs?.plus(REFRESH_TOKEN_LIFETIME_MS))
        }
    }
}

fun buildAuthorizeUrl(credentials: SchwabCredentials, state: String): String =
    "$SCHWAB_AUTHORIZE_URL?response_type=code" +
        "&client_id=${encode(credentials.appKey)}" +
        "&redirect_uri=${encode(credentials.callbackUrl)}" +
        "&state=${encode(state)}"

/**
 * Pulls the auth code from Schwab's redirect. The code arrives percent-encoded
 * (it normally ends in `%40`), so it is decoded once here and form-encoded again
 * when posted. Skipping either step produces an invalid_grant error.
 */
fun parseCallback(callbackUrl: String, expectedState: String?): String {
    val query = try {
        URI(callbackUrl.trim()).rawQuery
    } catch (_: Exception) {
        null
    } ?: throw ApiException.Parse("That doesn't look like the Schwab redirect URL")
    val params = query.split('&').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) null else pair.substring(0, i) to decode(pair.substring(i + 1))
    }.toMap()
    params["error"]?.let { throw ApiException.Parse("Schwab reported: $it") }
    if (expectedState != null && params["state"] != expectedState) {
        throw ApiException.Parse("The sign-in response didn't match this request. Try again.")
    }
    return params["code"]?.takeIf { it.isNotBlank() }
        ?: throw ApiException.Parse("The redirect URL has no code in it")
}

data class ImportedToken(val refreshToken: String, val issuedAtMs: Long?)

fun parseImportedToken(pasted: String): ImportedToken {
    val text = pasted.trim()
    if (text.isEmpty()) throw ApiException.Parse("Paste a refresh token first")
    if (!text.startsWith("{")) {
        if (text.any { it.isWhitespace() }) {
            throw ApiException.Parse("A refresh token can't contain spaces")
        }
        return ImportedToken(text, null)
    }
    val root = try {
        LENIENT.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw ApiException.Parse("That token file isn't valid JSON", e)
    }
    // schwab-py: {"creation_timestamp": 1730000000, "token": {"refresh_token": ...}}
    root["token"]?.let { nested ->
        val token = (nested as? JsonObject)?.string("refresh_token")
        if (token != null) {
            return ImportedToken(token, root.long("creation_timestamp")?.times(1000))
        }
    }
    // schwabdev: {"refresh_token_issued": "...Z", "token_dictionary": {"refresh_token": ...}}
    root["token_dictionary"]?.let { nested ->
        val token = (nested as? JsonObject)?.string("refresh_token")
        if (token != null) {
            val issued = root.string("refresh_token_issued")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            return ImportedToken(token, issued)
        }
    }
    root.string("refresh_token")?.let { return ImportedToken(it, null) }
    throw ApiException.Parse("No refresh_token found in that file")
}

fun encodeSession(session: StoredSession): String = buildJsonObject {
    put("appKey", session.credentials.appKey)
    put("appSecret", session.credentials.appSecret)
    put("callbackUrl", session.credentials.callbackUrl)
    session.tokens?.let { t ->
        t.accessToken?.let { put("accessToken", it) }
        put("accessExpiresAtMs", t.accessExpiresAtMs)
        put("refreshToken", t.refreshToken)
        t.refreshIssuedAtMs?.let { put("refreshIssuedAtMs", it) }
    }
}.toString()

fun decodeSession(text: String): StoredSession {
    val o = LENIENT.parseToJsonElement(text).jsonObject
    val refresh = o.string("refreshToken")
    return StoredSession(
        credentials = SchwabCredentials(
            appKey = o.string("appKey").orEmpty(),
            appSecret = o.string("appSecret").orEmpty(),
            callbackUrl = o.string("callbackUrl").orEmpty(),
        ),
        tokens = refresh?.let {
            TokenSet(
                accessToken = o.string("accessToken"),
                accessExpiresAtMs = o.long("accessExpiresAtMs") ?: 0L,
                refreshToken = it,
                refreshIssuedAtMs = o.long("refreshIssuedAtMs"),
            )
        },
    )
}

private val LENIENT = Json { isLenient = true; ignoreUnknownKeys = true }

private fun parseTokenBody(body: String): JsonObject = try {
    LENIENT.parseToJsonElement(body).jsonObject
} catch (e: Exception) {
    // The body may hold tokens, so it is never included in the message.
    throw ApiException.Parse("Schwab sent an unreadable token response", e)
}

private fun isDeadRefreshToken(code: Int, body: String): Boolean {
    if (code != 400 && code != 401) return false
    if (code == 401) return true
    return listOf("invalid_grant", "refresh_token_authentication_error", "unsupported_token_type", "invalid_client")
        .any { body.contains(it, ignoreCase = true) }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { p ->
    p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong()
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
