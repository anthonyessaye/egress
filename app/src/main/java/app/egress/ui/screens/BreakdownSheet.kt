package app.egress.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.egress.data.AppSummary
import app.egress.data.CountryCount
import app.egress.data.DomainCount
import app.egress.data.Filter
import app.egress.ui.MainViewModel
import app.egress.ui.components.AppIcon
import app.egress.ui.components.EmptyHint
import app.egress.ui.components.MeterBar
import app.egress.ui.components.SearchField
import app.egress.ui.components.compact
import app.egress.ui.components.plural
import app.egress.ui.components.flag
import app.egress.ui.components.rememberQuery
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type

enum class Breakdown(val title: String, val noun: String, val action: String) {
    Apps("Apps", "apps", "Tap an app for its detail"),
    Domains("Domains", "domains", "Tap a domain to see its lookups in the Log"),
    Countries("Countries", "countries", "Tap a country to see it on the map"),
}

private data class Entry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val count: Int,
    val uid: Int? = null,
    val flag: String? = null,
    val mono: Boolean = false,
)

/** The full list behind a Pulse stat tile, under the current filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakdownSheet(kind: Breakdown, vm: MainViewModel, filter: Filter, tick: Long, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val rows: List<Entry> = when (kind) {
        Breakdown.Apps -> rememberQuery(emptyList<AppSummary>(), kind, filter, tick) { vm.repo.apps(filter) }.map {
            Entry(
                it.uid.toString(), it.label,
                plural(it.domains, "domain") + if (it.countries.isEmpty()) "" else "  ·  " + it.countries.take(6).joinToString(" ") { cc -> flag(cc) },
                it.lookups, uid = it.uid,
            )
        }
        Breakdown.Domains -> rememberQuery(emptyList<DomainCount>(), kind, filter, tick) { vm.repo.domains(filter, 500) }.map {
            Entry(it.domain, it.domain, null, it.count, flag = it.cc?.let(::flag), mono = true)
        }
        Breakdown.Countries -> rememberQuery(emptyList<CountryCount>(), kind, filter, tick) { vm.repo.topCountries(filter, 250) }.map {
            Entry(it.cc, it.country, null, it.count, flag = flag(it.cc))
        }
    }
    val max = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val shown = rows.filter { query.isBlank() || it.title.contains(query, true) || it.key.contains(query, true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        scrimColor = Palette.Bg.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(kind.title, style = Type.Title, modifier = Modifier.weight(1f))
                Text("${rows.size} ${kind.noun} · ${filter.range.label}", style = Type.Mono)
            }
            Spacer(Modifier.height(2.dp))
            Text(kind.action, style = Type.Small)
            Spacer(Modifier.height(14.dp))
            if (rows.size > 8) {
                SearchField(query, { query = it }, "Search ${kind.noun}")
                Spacer(Modifier.height(8.dp))
            }
            if (rows.isEmpty()) EmptyHint("Nothing here yet", "Widen the time range or clear filters.")
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                items(shown, key = { it.key }) { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onDismiss()
                                when (kind) {
                                    Breakdown.Apps -> vm.openApp(r.uid!!)
                                    Breakdown.Domains -> vm.searchLog(r.key)
                                    Breakdown.Countries -> vm.showCountryOnMap(r.key)
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            r.uid != null -> AppIcon(r.uid, r.title, 34.dp)
                            r.flag != null -> Text(r.flag, fontSize = 22.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.title,
                                    style = if (r.mono) Type.Mono.copy(color = Palette.Text, fontSize = 13.sp) else Type.Body,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(compact(r.count), style = Type.Mono.copy(color = Palette.Text))
                            }
                            if (r.subtitle != null) {
                                Text(r.subtitle, style = Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(6.dp))
                            MeterBar(r.count / max.toFloat(), Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp), tint = Palette.TextLo)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
