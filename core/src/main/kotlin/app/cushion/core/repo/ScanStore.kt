package app.cushion.core.repo

import app.cushion.core.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanState(
    val result: ScanResult? = null,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/**
 * The single idea scan, shared by the Ideas list and Browse's Top premiums. A scan
 * costs about one call per symbol, so it only reruns when the result has gone stale.
 */
class ScanStore(
    private val scope: CoroutineScope,
    private val repository: () -> MarketRepository,
    private val settings: () -> AppSettings,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null

    /** Starts a scan if none is running and the last one is stale, or if [force] is set. */
    fun refresh(force: Boolean = false) {
        if (job?.isActive == true) return
        val last = _state.value.result
        if (!force && last != null && nowMs() - last.scannedAtMs < staleAfterMs()) return
        job = scope.launch {
            _state.update { it.copy(running = true, done = 0, total = settings().universe.distinct().size, error = null) }
            try {
                val s = settings()
                val result = repository().scan(s.universe, s.screen, s.accountValue, s.perNameCapPct) { done, total ->
                    _state.update { it.copy(done = done, total = total) }
                }
                _state.update { it.copy(result = result, running = false, error = null) }
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.userMessage()) }
            }
        }
    }

    /** Drops results after a change to the data source or the rules. */
    fun invalidate() {
        job?.cancel()
        job = null
        _state.value = ScanState()
    }

    private fun staleAfterMs(): Long =
        if (marketPhase(nowMs(), null) == MarketPhase.OPEN) 5L * 60 * 1000 else 30L * 60 * 1000
}
