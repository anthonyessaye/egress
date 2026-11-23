package app.egress.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.egress.EgressApp
import app.egress.data.Filter
import app.egress.data.LogFilter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class Tab { Overview, Map, Apps, Activity }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val egress = app as EgressApp
    val repo get() = egress.repo
    val prefs get() = egress.prefs

    val filter = MutableStateFlow(Filter())
    val tab = MutableStateFlow(Tab.Overview)
    val appDetail = MutableStateFlow<Int?>(null)
    val selectedCluster = MutableStateFlow<String?>(null)
    /** Free-text search on the Log tab; other screens can jump there with a pre-filled query. */
    val logSearch = MutableStateFlow("")
    /** Log-only refinements on top of the global [filter]. */
    val logFilter = MutableStateFlow(LogFilter())

    /** Database change counter, throttled so the UI refreshes at most every ~1.5 s while capturing. */
    @OptIn(FlowPreview::class)
    val tick: StateFlow<Long> = egress.repo.changes
        .sample(1500)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), egress.repo.changes.value)

    fun setFilter(f: Filter) {
        filter.value = f
        selectedCluster.value = null
    }

    fun showAppOnMap(uid: Int) {
        filter.update { it.copy(uids = setOf(uid)) }
        selectedCluster.value = null
        appDetail.value = null
        tab.value = Tab.Map
    }

    fun showCountryOnMap(cc: String) {
        filter.update { it.copy(countries = setOf(cc)) }
        selectedCluster.value = null
        tab.value = Tab.Map
    }

    fun searchLog(query: String) {
        logSearch.value = query
        appDetail.value = null
        tab.value = Tab.Activity
    }

    fun openApp(uid: Int) {
        appDetail.value = uid
        tab.value = Tab.Apps
    }
}
