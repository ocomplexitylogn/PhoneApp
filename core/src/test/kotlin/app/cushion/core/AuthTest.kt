package app.cushion.core

import app.cushion.core.auth.AuthState
import app.cushion.core.auth.SchwabCredentials
import app.cushion.core.auth.SecretStore
import app.cushion.core.auth.StoredSession
import app.cushion.core.auth.TokenManager
import app.cushion.core.auth.TokenSet
import app.cushion.core.auth.buildAuthorizeUrl
import app.cushion.core.auth.decodeSession
import app.cushion.core.auth.encodeSession
import app.cushion.core.auth.parseCallback
import app.cushion.core.auth.parseImportedToken
import app.cushion.core.net.ApiException
import app.cushion.core.net.HttpRequest
import app.cushion.core.net.HttpResponse
import app.cushion.core.net.HttpTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64

private class MemoryStore(var session: StoredSession?) : SecretStore {
    var writes = 0
    override suspend fun read() = session
    override suspend fun write(session: StoredSession?) { this.session = session; writes++ }
}

private val creds = SchwabCredentials("KEY", "SECRET", "https://127.0.0.1:8182")

class AuthTest {
    private var now = 1_000_000_000L
    private val requests = ArrayList<HttpRequest>()

    private fun manager(store: SecretStore, reply: suspend (HttpRequest) -> HttpResponse) =
        TokenManager(HttpTransport { r -> requests += r; reply(r) }, store, { now })

    private fun ok(access: String = "A2", refresh: String? = null) = HttpResponse(
        200,
        """{"expires_in":1800,"token_type":"Bearer","scope":"api","access_token":"$access"${refresh?.let { ""","refresh_token":"$it"""" } ?: ""}}""",
    )

    private fun session(access: String? = "A1", expires: Long = now + 1_800_000) =
        StoredSession(creds, TokenSet(access, expires, "R1", now))

    @Test fun `valid access token is reused with no network`() = runBlocking {
        val m = manager(MemoryStore(session())) { error("no call expected") }
        m.load()
        assertEquals("A1", m.accessToken())
    }

    @Test fun `token inside the leeway window refreshes with basic auth`() = runBlocking {
        val store = MemoryStore(session(expires = now + 60_000))
        val m = manager(store) { ok() }
        m.load()
        assertEquals("A2", m.accessToken())
        val r = requests.single()
        assertEquals("POST", r.method)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("KEY:SECRET".toByteArray()), r.headers["Authorization"])
        assertEquals("grant_type=refresh_token&refresh_token=R1", r.formBody)
        assertEquals("A2", store.session!!.tokens!!.accessToken)
        assertEquals("R1", store.session!!.tokens!!.refreshToken)
    }

    @Test fun `a rotated refresh token is persisted`() = runBlocking {
        val store = MemoryStore(session(expires = 0))
        val m = manager(store) { ok(refresh = "R2") }
        m.load()
        m.accessToken()
        assertEquals("R2", store.session!!.tokens!!.refreshToken)
    }

    @Test fun `concurrent callers share one refresh`() = runBlocking {
        val m = manager(MemoryStore(session(expires = 0))) { delay(20); ok() }
        m.load()
        val tokens = (1..20).map { async { m.accessToken() } }.awaitAll()
        assertEquals(1, requests.size)
        assertTrue(tokens.all { it == "A2" })
    }

    @Test fun `a rejected token forces one refresh, even for concurrent 401s`() = runBlocking {
        val m = manager(MemoryStore(session())) { delay(10); ok() }
        m.load()
        val tokens = (1..5).map { async { m.accessToken(rejected = "A1") } }.awaitAll()
        assertEquals(1, requests.size)
        assertTrue(tokens.all { it == "A2" })
    }

    @Test fun `expired refresh token asks for sign-in and keeps the app key`() = runBlocking {
        val store = MemoryStore(session(expires = 0))
        val m = manager(store) {
            HttpResponse(400, """{"error":"refresh_token_authentication_error","error_description":"expired"}""")
        }
        m.load()
        try {
            m.accessToken()
            fail("expected AuthRequired")
        } catch (_: ApiException.AuthRequired) {
        }
        assertTrue(m.state.value is AuthState.SignInRequired)
        assertEquals("KEY", store.session!!.credentials.appKey)
        assertNull(store.session!!.tokens)
    }

    @Test fun `server trouble during refresh keeps the session`() = runBlocking {
        val store = MemoryStore(session(expires = 0))
        val m = manager(store) { HttpResponse(503, "busy") }
        m.load()
        try {
            m.accessToken()
            fail("expected Http")
        } catch (e: ApiException.Http) {
            assertEquals(503, e.code)
        }
        assertTrue(m.state.value is AuthState.Ready)
        assertNotNull(store.session!!.tokens)
    }

    @Test fun `offline refresh is a network error and keeps the session`() = runBlocking {
        val store = MemoryStore(session(expires = 0))
        val m = manager(store) { throw IOException("offline") }
        m.load()
        try {
            m.accessToken()
            fail("expected Network")
        } catch (_: ApiException.Network) {
        }
        assertNotNull(store.session!!.tokens)
    }

    @Test fun `no secrets appear in error messages`() = runBlocking {
        val m = manager(MemoryStore(session(expires = 0))) { HttpResponse(200, "not json refresh_token=R1") }
        m.load()
        try {
            m.accessToken()
            fail("expected Parse")
        } catch (e: ApiException.Parse) {
            assertFalse(e.message!!.contains("R1"))
        }
    }

    @Test fun `failed save does not end the session`() = runBlocking {
        val store = object : SecretStore {
            override suspend fun read() = session(expires = 0)
            override suspend fun write(session: StoredSession?) = throw IOException("disk full")
        }
        val m = manager(store) { ok() }
        m.load()
        assertEquals("A2", m.accessToken())
    }

    @Test fun `sign-in decodes the code once and encodes it once`() = runBlocking {
        val store = MemoryStore(StoredSession(creds, null))
        val m = manager(store) { ok(refresh = "R9") }
        m.load()
        assertTrue(m.state.value is AuthState.SignInRequired)
        m.completeSignIn("https://127.0.0.1:8182/?code=C0.b2F%2Bx%40&session=abc&state=st8", "st8")
        val form = requests.single().formBody!!
        assertTrue(form, form.contains("code=C0.b2F%2Bx%40&"))
        assertTrue(form, form.contains("redirect_uri=https%3A%2F%2F127.0.0.1%3A8182"))
        assertEquals("R9", store.session!!.tokens!!.refreshToken)
        assertEquals(now, store.session!!.tokens!!.refreshIssuedAtMs)
        val state = m.state.value as AuthState.Ready
        assertEquals(now + 7L * 86_400_000, state.refreshExpiresAtMs)
    }

    @Test fun `mismatched state is rejected`() {
        try {
            parseCallback("https://127.0.0.1/?code=abc&state=evil", "good")
            fail("expected Parse")
        } catch (_: ApiException.Parse) {
        }
    }

    @Test fun `denied consent surfaces Schwab's error`() {
        try {
            parseCallback("https://127.0.0.1/?error=access_denied", null)
            fail("expected Parse")
        } catch (e: ApiException.Parse) {
            assertTrue(e.message!!.contains("access_denied"))
        }
    }

    @Test fun `authorize url is well formed`() {
        assertEquals(
            "https://api.schwabapi.com/v1/oauth/authorize?response_type=code&client_id=KEY" +
                "&redirect_uri=https%3A%2F%2F127.0.0.1%3A8182&state=x",
            buildAuthorizeUrl(creds, "x"),
        )
    }

    @Test fun `imports a bare token without inventing an expiry`() {
        val t = parseImportedToken("  abc.def-123@  \n")
        assertEquals("abc.def-123@", t.refreshToken)
        assertNull(t.issuedAtMs)
    }

    @Test fun `imports a schwab-py token file with its real issue date`() {
        val t = parseImportedToken("""{"creation_timestamp":1793000000,"token":{"refresh_token":"RPY","access_token":"x","expires_at":1}}""")
        assertEquals("RPY", t.refreshToken)
        assertEquals(1_793_000_000_000L, t.issuedAtMs)
    }

    @Test fun `imports a schwabdev token file`() {
        val t = parseImportedToken("""{"refresh_token_issued":"2026-10-30T12:00:00Z","token_dictionary":{"refresh_token":"RDEV"}}""")
        assertEquals("RDEV", t.refreshToken)
        assertNotNull(t.issuedAtMs)
    }

    @Test fun `rejects junk paste`() {
        for (bad in listOf("", "two words", "{\"a\":1}", "{broken")) {
            try {
                parseImportedToken(bad)
                fail("expected Parse for '$bad'")
            } catch (_: ApiException.Parse) {
            }
        }
    }

    @Test fun `import validates by refreshing before keeping the token`() = runBlocking {
        val store = MemoryStore(StoredSession(creds, null))
        val m = manager(store) { HttpResponse(400, """{"error":"invalid_grant"}""") }
        m.load()
        try {
            m.importRefreshToken("BAD")
            fail("expected AuthRequired")
        } catch (_: ApiException.AuthRequired) {
        }
        assertNull(store.session!!.tokens)
    }

    @Test fun `session survives an encode and decode`() {
        val s = StoredSession(creds, TokenSet("A\"1", 123L, "R/1=", null))
        assertEquals(s, decodeSession(encodeSession(s)))
        val noTokens = StoredSession(creds, null)
        assertEquals(noTokens, decodeSession(encodeSession(noTokens)))
    }

    @Test fun `changing the app key drops tokens from the old app`() = runBlocking {
        val store = MemoryStore(session())
        val m = manager(store) { error("none") }
        m.load()
        m.saveCredentials(creds.copy(callbackUrl = "https://127.0.0.1"))
        assertNotNull(store.session!!.tokens)
        m.saveCredentials(creds.copy(appKey = "OTHER"))
        assertNull(store.session!!.tokens)
    }
}
