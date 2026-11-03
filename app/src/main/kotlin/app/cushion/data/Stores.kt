package app.cushion.data

import android.content.Context
import app.cushion.core.earnings.DayCache
import app.cushion.core.screen.ContractMetrics
import app.cushion.core.screen.ShortlistItem
import app.cushion.core.settings.AppSettings
import app.cushion.core.settings.decodeSettings
import app.cushion.core.settings.decodeShortlist
import app.cushion.core.settings.encodeSettings
import app.cushion.core.settings.encodeShortlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cushion", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(decodeSettings(prefs.getString(KEY, null)))
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    fun update(change: (AppSettings) -> AppSettings) {
        _state.update(change)
        prefs.edit().putString(KEY, encodeSettings(_state.value)).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}

class ShortlistStore(context: Context) {
    private val prefs = context.getSharedPreferences("cushion", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(decodeShortlist(prefs.getString(KEY, null)))
    val items: StateFlow<List<ShortlistItem>> = _items.asStateFlow()

    fun add(metrics: ContractMetrics) {
        val c = metrics.contract
        mutate { list ->
            if (list.any { it.contractSymbol == c.symbol }) {
                list
            } else {
                list + ShortlistItem(c.symbol, c.underlying, c.strike, c.expiration, metrics.premium, 1, System.currentTimeMillis())
            }
        }
    }

    fun remove(contractSymbol: String) = mutate { list -> list.filterNot { it.contractSymbol == contractSymbol } }

    fun setContracts(contractSymbol: String, contracts: Int) = mutate { list ->
        list.map { if (it.contractSymbol == contractSymbol) it.copy(contracts = contracts.coerceIn(1, 999)) else it }
    }

    private fun mutate(change: (List<ShortlistItem>) -> List<ShortlistItem>) {
        _items.update(change)
        prefs.edit().putString(KEY, encodeShortlist(_items.value)).apply()
    }

    private companion object {
        const val KEY = "shortlist"
    }
}

/** Earnings days cached on disk. Old files go when the OS clears the cache directory. */
class FileDayCache(context: Context) : DayCache {
    private val dir = File(context.cacheDir, "earnings").apply { mkdirs() }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        File(dir, key).takeIf { it.exists() }?.readText()
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        File(dir, key).writeText(value)
    }
}
