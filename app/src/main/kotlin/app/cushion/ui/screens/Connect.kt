package app.cushion.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import app.cushion.core.auth.SchwabCredentials
import app.cushion.core.repo.userMessage
import app.cushion.data.AppGraph
import app.cushion.ui.Routes
import app.cushion.ui.components.Card
import app.cushion.ui.components.Notice
import app.cushion.ui.components.PrimaryButton
import app.cushion.ui.components.SecondaryButton
import app.cushion.ui.components.TopBar
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64

private const val DEFAULT_CALLBACK = "https://127.0.0.1"

private fun NavHostController.finishConnect() {
    navigate(Routes.IDEAS) {
        popUpTo(graph.id) { inclusive = true }
    }
}

@Composable
fun ConnectScreen(graph: AppGraph, nav: NavHostController) {
    val saved = graph.tokens.credentials
    var appKey by rememberSaveable { mutableStateOf(saved?.appKey.orEmpty()) }
    var appSecret by remember { mutableStateOf(saved?.appSecret.orEmpty()) }
    var callback by rememberSaveable { mutableStateOf(saved?.callbackUrl ?: DEFAULT_CALLBACK) }
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val keysReady = appKey.isNotBlank() && appSecret.isNotBlank() && callback.startsWith("https://")

    fun connected() {
        graph.settings.update { it.copy(demoMode = false) }
        graph.scans.invalidate()
        nav.finishConnect()
    }

    fun saveKeys(after: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                graph.tokens.saveCredentials(SchwabCredentials(appKey.trim(), appSecret.trim(), callback.trim()))
                after()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Connect Schwab",
            onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack(); Unit }) else null,
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Text("Your Schwab app", style = Type.CardTitle)
                Text(
                    "From your app at developer.schwab.com. These stay encrypted on this phone and go only to Schwab.",
                    style = Type.Body,
                    color = Palette.Secondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                FieldBox("App key", appKey, { appKey = it })
                FieldBox("App secret", appSecret, { appSecret = it }, secret = true)
                FieldBox("Callback URL", callback, { callback = it }, placeholder = DEFAULT_CALLBACK)
                PrimaryButton(
                    text = "Sign in with Schwab",
                    enabled = keysReady && !busy,
                    modifier = Modifier.padding(top = 10.dp),
                    onClick = { saveKeys { nav.navigate(Routes.SIGN_IN) } },
                )
            }
            Card {
                Text("Or use a refresh token", style = Type.CardTitle)
                Text(
                    "Paste a token, your schwab-py token file, or the URL Schwab redirected you to.",
                    style = Type.Body,
                    color = Palette.Secondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                FieldBox("Token", pasted, { pasted = it }, secret = false, singleLine = false)
                PrimaryButton(
                    text = if (busy) "Checking" else "Use this token",
                    enabled = keysReady && pasted.isNotBlank() && !busy,
                    modifier = Modifier.padding(top = 10.dp),
                    onClick = {
                        saveKeys {
                            val text = pasted.trim()
                            if (text.startsWith("http") && text.contains("code=")) {
                                graph.tokens.completeSignIn(text, null)
                            } else {
                                graph.tokens.importRefreshToken(text)
                            }
                            connected()
                        }
                    },
                )
            }
            error?.let { Text(it, style = Type.Body, color = Palette.Orange, modifier = Modifier.padding(horizontal = 4.dp)) }
            SecondaryButton("Try it with demo data", {
                graph.settings.update { it.copy(demoMode = true) }
                graph.scans.invalidate()
                nav.finishConnect()
            })
            Box(Modifier.height(12.dp))
        }
    }
}

private fun newState(): String {
    val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Hosts Schwab's own login page and catches its redirect back to the callback URL. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(graph: AppGraph, nav: NavHostController) {
    val state = remember { newState() }
    val credentials = graph.tokens.credentials
    var phase by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Sign in", onBack = { nav.popBackStack() })
        when {
            credentials == null -> Notice("Enter your app key first")
            failure != null -> Notice("Sign-in didn't finish", failure)
            phase != null -> Notice(phase!!)
            else -> {
                val callback = credentials.callbackUrl.trimEnd('/')
                val onCallback: (String) -> Unit = { url ->
                    if (phase == null) {
                        phase = "Finishing sign-in"
                        scope.launch {
                            try {
                                graph.tokens.completeSignIn(url, state)
                                graph.settings.update { it.copy(demoMode = false) }
                                graph.scans.invalidate()
                                nav.finishConnect()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = e.userMessage()
                            }
                        }
                    }
                }
                val authorizeUrl = graph.tokens.authorizeUrl(state)
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (!url.startsWith(callback)) return false
                                    onCallback(url)
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (url.startsWith(callback)) {
                                        view.stopLoading()
                                        onCallback(url)
                                    }
                                }
                            }
                            loadUrl(authorizeUrl)
                        }
                    },
                )
            }
        }
    }

    // Schwab's session cookies are of no further use once this screen closes.
    DisposableEffect(Unit) {
        onDispose { CookieManager.getInstance().removeAllCookies(null) }
    }
}
