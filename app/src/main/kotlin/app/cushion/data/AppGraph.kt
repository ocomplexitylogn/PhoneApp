package app.cushion.data

import android.content.Context
import app.cushion.core.auth.TokenManager
import app.cushion.core.demo.DemoTokens
import app.cushion.core.demo.DemoTransport
import app.cushion.core.earnings.InMemoryDayCache
import app.cushion.core.earnings.NasdaqEarnings
import app.cushion.core.net.RateLimiter
import app.cushion.core.repo.MarketRepository
import app.cushion.core.repo.ScanStore
import app.cushion.core.repo.marketDate
import app.cushion.core.repo.marketPhase
import app.cushion.core.repo.pollIntervalMs
import app.cushion.core.schwab.SchwabClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manual dependency wiring. One instance, created by the Application. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = OkHttpTransport()

    val settings = SettingsStore(appContext)
    val shortlist = ShortlistStore(appContext)
    val tokens = TokenManager(transport, KeystoreSecretStore(appContext))

    /** Stays under the 120 calls a minute commonly cited for Schwab market data. */
    private val limiter = RateLimiter(permitsPerMinute = 100, burst = 20)

    private val liveRepository by lazy {
        MarketRepository(
            SchwabClient(transport, tokens, limiter),
            NasdaqEarnings(
                transport = transport,
                cache = FileDayCache(appContext),
                today = { marketDate(System.currentTimeMillis()) },
                pauseAfterFetch = { delay(150) },
            ),
        )
    }

    private val demoRepository by lazy {
        val demo = DemoTransport()
        MarketRepository(
            SchwabClient(demo, DemoTokens, RateLimiter(permitsPerMinute = 6000, burst = 200)),
            NasdaqEarnings(demo, InMemoryDayCache()) { marketDate(System.currentTimeMillis()) },
        )
    }

    /** Read this on each use: it changes when demo mode is switched. */
    val repository: MarketRepository
        get() = if (settings.current.demoMode) demoRepository else liveRepository

    val scans = ScanStore(scope, { repository }, { settings.current })

    init {
        scope.launch { tokens.load() }
    }

    /** Quote refresh cadence for the current market phase. */
    suspend fun quoteIntervalMs(): Long {
        val hours = runCatching { repository.marketHoursToday() }.getOrNull()
        return pollIntervalMs(marketPhase(System.currentTimeMillis(), hours))
    }
}
