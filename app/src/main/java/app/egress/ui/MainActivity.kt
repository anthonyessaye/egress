package app.egress.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.egress.ui.components.quietClick
import app.egress.ui.screens.ActivityScreen
import app.egress.ui.screens.AppsScreen
import app.egress.ui.screens.MapScreen
import app.egress.ui.screens.OverviewScreen
import app.egress.ui.theme.EgressTheme
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type
import app.egress.vpn.CaptureState
import app.egress.vpn.EgressVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { EgressTheme { EgressRoot() } }
    }
}

fun startCapture(ctx: Context) {
    ContextCompat.startForegroundService(
        ctx, Intent(ctx, EgressVpnService::class.java).setAction(EgressVpnService.ACTION_START),
    )
}

fun stopCapture(ctx: Context) {
    ctx.startService(Intent(ctx, EgressVpnService::class.java).setAction(EgressVpnService.ACTION_STOP))
}

/** Returns a toggle that handles the one-time VPN consent dialog. */
@Composable
fun rememberCaptureToggle(): () -> Unit {
    val ctx = LocalContext.current
    val running by CaptureState.running.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) startCapture(ctx)
        else CaptureState.lastError.value = "VPN permission is needed to see which app talks to which server."
    }
    return {
        if (running) stopCapture(ctx)
        else {
            val ask = VpnService.prepare(ctx)
            if (ask != null) consent.launch(ask) else startCapture(ctx)
        }
    }
}

@Composable
fun EgressRoot(vm: MainViewModel = viewModel()) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val detail by vm.appDetail.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = detail != null || tab != Tab.Overview) {
        if (detail != null) vm.appDetail.value = null else vm.tab.value = Tab.Overview
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab",
        ) { t ->
            when (t) {
                Tab.Overview -> OverviewScreen(vm)
                Tab.Map -> MapScreen(vm)
                Tab.Apps -> AppsScreen(vm)
                Tab.Activity -> ActivityScreen(vm)
            }
        }
        NavBar(
            current = tab,
            onPick = { vm.appDetail.value = null; vm.tab.value = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private data class NavItem(val tab: Tab, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Tab.Overview, "Pulse", Icons.Rounded.SpaceDashboard),
    NavItem(Tab.Map, "Map", Icons.Rounded.Public),
    NavItem(Tab.Apps, "Apps", Icons.Rounded.Apps),
    NavItem(Tab.Activity, "Log", Icons.Rounded.Timeline),
)

@Composable
private fun NavBar(current: Tab, onPick: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .clip(shape)
            .background(Palette.SurfaceHi.copy(alpha = 0.94f))
            .border(1.dp, Palette.StrokeHi, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navItems.forEach { item ->
            val on = item.tab == current
            val pill = RoundedCornerShape(24.dp)
            Row(
                Modifier
                    .clip(pill)
                    .background(if (on) Palette.AccentSoft else SolidColor(androidx.compose.ui.graphics.Color.Transparent))
                    .quietClick { onPick(item.tab) }
                    .padding(horizontal = if (on) 16.dp else 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, item.label, Modifier.size(20.dp), tint = if (on) Palette.Cyan else Palette.TextLo)
                if (on) {
                    Spacer(Modifier.width(8.dp))
                    Text(item.label, style = Type.Small.copy(color = Palette.Text, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}
