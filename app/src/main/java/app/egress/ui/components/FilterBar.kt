package app.egress.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.egress.EgressApp
import app.egress.data.AppCount
import app.egress.data.CountryCount
import app.egress.data.Filter
import app.egress.data.TimeRange
import app.egress.ui.theme.Palette
import app.egress.ui.theme.Type

/**
 * Time segment + app and country filters. Shared by every screen so a filter set on the map
 * also applies to the app list, the activity log and the overview.
 */
@Composable
fun FilterBar(
    filter: Filter,
    tick: Long,
    onChange: (Filter) -> Unit,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    showApps: Boolean = true,
) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val apps = remember { EgressApp.get().apps }

    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTime) TimeSegment(filter.range) { onChange(filter.copy(range = it)) }
        if (showApps) {
            val appLabel = when (filter.uids.size) {
                0 -> "All apps"
                1 -> apps.meta(filter.uids.first()).label
                else -> "${filter.uids.size} apps"
            }
            Pill(
                appLabel, active = filter.uids.isNotEmpty(),
                leading = { Icon(Icons.Rounded.Apps, null, Modifier.size(16.dp), tint = Palette.TextMid) },
                onClick = { sheet = "apps" },
            )
        }
        val ccLabel = when (filter.countries.size) {
            0 -> "All countries"
            in 1..3 -> filter.countries.joinToString(" ") { flag(it) }
            else -> "${filter.countries.size} countries"
        }
        Pill(
            ccLabel, active = filter.countries.isNotEmpty(),
            leading = { Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tint = Palette.TextMid) },
            onClick = { sheet = "countries" },
        )
        if (filter.hasSelection) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Palette.SurfaceHi)
                    .border(1.dp, Palette.Stroke, CircleShape)
                    .clickable { onChange(filter.copy(uids = emptySet(), countries = emptySet())) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Clear filters", Modifier.size(16.dp), tint = Palette.TextMid) }
        }
    }

    when (sheet) {
        "apps" -> {
            val options = rememberQuery(emptyList<AppCount>(), filter.range, tick) {
                EgressApp.get().repo.appOptions(filter.range)
            }
            SelectSheet(
                title = "Filter by app",
                items = options.map { SheetItem(it.uid.toString(), it.label, it.count, uid = it.uid) },
                selected = filter.uids.map { it.toString() }.toSet(),
                onToggle = { key ->
                    val uid = key.toInt()
                    onChange(filter.copy(uids = if (uid in filter.uids) filter.uids - uid else filter.uids + uid))
                },
                onClear = { onChange(filter.copy(uids = emptySet())) },
                onDismiss = { sheet = null },
            )
        }
        "countries" -> {
            val options = rememberQuery(emptyList<CountryCount>(), filter.range, tick) {
                EgressApp.get().repo.countryOptions(filter.range)
            }
            SelectSheet(
                title = "Filter by destination country",
                items = options.map { SheetItem(it.cc, it.country, it.count, flag = flag(it.cc)) },
                selected = filter.countries,
                onToggle = { cc ->
                    onChange(filter.copy(countries = if (cc in filter.countries) filter.countries - cc else filter.countries + cc))
                },
                onClear = { onChange(filter.copy(countries = emptySet())) },
                onDismiss = { sheet = null },
            )
        }
    }
}

@Composable
fun TimeSegment(value: TimeRange, onPick: (TimeRange) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Palette.SurfaceHi)
            .border(1.dp, Palette.Stroke, shape)
            .padding(3.dp),
    ) {
        TimeRange.entries.forEach { r ->
            val on = r == value
            Box(
                Modifier
                    .clip(shape)
                    .background(if (on) Palette.Accent else SolidColor(androidx.compose.ui.graphics.Color.Transparent))
                    .clickable { onPick(r) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    r.label,
                    style = Type.Small.copy(
                        color = if (on) Palette.Bg else Palette.TextMid,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

data class SheetItem(val key: String, val label: String, val count: Int, val uid: Int? = null, val flag: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectSheet(
    title: String,
    items: List<SheetItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Palette.Surface,
        scrimColor = Palette.Bg.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.Title, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) LinkText("Clear") { onClear() }
            }
            Spacer(Modifier.height(14.dp))
            SearchField(query, { query = it }, "Search")
            Spacer(Modifier.height(10.dp))
            val shown = items.filter { query.isBlank() || it.label.contains(query, true) || it.key.contains(query, true) }
            if (items.isEmpty()) {
                EmptyHint("Nothing captured yet", "Start capture and use your phone for a minute.")
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(shown, key = { it.key }) { item ->
                    val on = item.key in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onToggle(item.key) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            item.uid != null -> AppIcon(item.uid, item.label, 32.dp)
                            item.flag != null -> Text(item.flag, fontSize = 24.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = Type.Body, maxLines = 1)
                            Text(plural(item.count, "lookup"), style = Type.Small)
                        }
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (on) Palette.Accent else SolidColor(Palette.SurfaceTop))
                                .border(1.dp, if (on) Palette.Cyan else Palette.StrokeHi, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Palette.Bg)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun SearchField(value: String, onValue: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.SurfaceHi)
            .border(1.dp, Palette.Stroke, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = Palette.TextLo)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = Type.Body.copy(color = Palette.TextLo))
            BasicTextField(
                value, onValue,
                singleLine = true,
                textStyle = Type.Body,
                cursorBrush = SolidColor(Palette.Cyan),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "Clear", Modifier.size(18.dp).clip(CircleShape).clickable { onValue("") },
                tint = Palette.TextMid,
            )
        }
    }
}
