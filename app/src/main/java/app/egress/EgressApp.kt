package app.egress

import android.app.Application
import app.egress.data.AppResolver
import app.egress.data.Db
import app.egress.data.GeoResolver
import app.egress.data.Prefs
import app.egress.data.Repository
import app.egress.vpn.IpCountryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EgressApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var apps: AppResolver; private set
    lateinit var repo: Repository; private set
    lateinit var geo: GeoResolver; private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Offline IP → country table for blocking (~5 MB). Loaded on first use, off the main thread. */
    val ipDb: IpCountryDb by lazy { assets.open("ipcountry.bin").use { IpCountryDb.read(it) } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        apps = AppResolver(this)
        repo = Repository(Db(this), apps, prefs)
        geo = GeoResolver(repo, prefs)
        appScope.launch { geo.run() }
    }

    companion object {
        @Volatile private var instance: EgressApp? = null
        fun get(): EgressApp = instance ?: error("EgressApp not created")
    }
}
