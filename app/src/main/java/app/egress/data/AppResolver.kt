package app.egress.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/** Turns kernel UIDs into app names and icons. */
class AppResolver(context: Context) {
    data class Meta(val uid: Int, val pkg: String?, val label: String, val system: Boolean)

    private val pm: PackageManager = context.packageManager
    private val metas = ConcurrentHashMap<Int, Meta>()
    private val icons = ConcurrentHashMap<Int, Holder>()

    private class Holder(val bmp: ImageBitmap?)

    fun meta(uid: Int): Meta = metas.getOrPut(uid) { resolve(uid) }

    /** Blocking (decodes a drawable) — call off the main thread. */
    fun icon(uid: Int): ImageBitmap? = icons.getOrPut(uid) {
        val pkg = meta(uid).pkg
        val bmp = if (pkg == null || uid < 10_000) null else runCatching {
            pm.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()
        Holder(bmp)
    }.bmp

    private fun resolve(uid: Int): Meta {
        SPECIAL[uid]?.let { return Meta(uid, null, it, true) }
        val appId = uid % 100_000 // strip the Android user (work profile) offset
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
            ?: runCatching { pm.getPackagesForUid(appId) }.getOrNull()
        if (!pkgs.isNullOrEmpty()) {
            val infos = pkgs.mapNotNull { p -> runCatching { pm.getApplicationInfo(p, 0) }.getOrNull() }
            val best = infos.firstOrNull { it.packageName == pkgs.first() } ?: infos.firstOrNull()
            if (best != null) {
                val label = pm.getApplicationLabel(best).toString()
                val shared = if (pkgs.size > 1) " +${pkgs.size - 1}" else ""
                val system = best.flags and ApplicationInfo.FLAG_SYSTEM != 0
                return Meta(uid, best.packageName, label + shared, system)
            }
            return Meta(uid, pkgs.first(), pkgs.first(), false)
        }
        val name = runCatching { pm.getNameForUid(uid) }.getOrNull()
        return Meta(uid, null, name ?: if (uid == -1) "Unknown" else "UID $uid", uid < 10_000)
    }

    companion object {
        private val SPECIAL = mapOf(
            -1 to "Unattributed",
            0 to "Kernel / root",
            1000 to "Android System",
            1001 to "Phone services",
            1013 to "Media server",
            1051 to "DNS resolver",
            1052 to "Tethering DNS",
            1073 to "Network stack",
        )
    }
}
