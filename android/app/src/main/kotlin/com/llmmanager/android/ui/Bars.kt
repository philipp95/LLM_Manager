package com.llmmanager.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.R
import com.llmmanager.android.data.ProjectsResponse
import com.llmmanager.android.ui.DashboardViewModel.Connection
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val TABS: List<Pair<String, () -> ImageVector>> = listOf(
    "Repos" to { Glyphs.folder }, "Schedule" to { Glyphs.clock }, "Usage" to { Glyphs.chart }, "Settings" to { Icons.Filled.Settings },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(title: String, status: String, hasToken: Boolean, onBack: (() -> Unit)?, onSettings: () -> Unit, onRefresh: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All repositories") }
            } else {
                Image(
                    painterResource(R.drawable.logo_mark), contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp).size(34.dp),
                )
            }
        },
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = Palette.onHarbor2, fontSize = 12.sp)
            }
        },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Lock, contentDescription = "Server and access token", tint = if (hasToken) Palette.ok else Palette.onHarbor)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Palette.harbor,
            titleContentColor = Palette.onHarbor,
            navigationIconContentColor = Palette.onHarbor,
            actionIconContentColor = Palette.onHarbor,
        ),
    )
}

/** The four places of the app, within reach of the thumb. */
@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = Palette.harbor2, contentColor = Palette.onHarbor) {
        TABS.forEachIndexed { index, (name, glyph) ->
            NavigationBarItem(
                selected = tab == index, onClick = { onTab(index) },
                icon = { Icon(glyph(), contentDescription = null, modifier = Modifier.size(22.dp)) },
                label = { Text(name, fontWeight = FontWeight.SemiBold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.onRust, selectedTextColor = Palette.onHarbor, indicatorColor = Palette.rust,
                    unselectedIconColor = Palette.onHarbor2, unselectedTextColor = Palette.onHarbor2,
                ),
            )
        }
    }
}

fun statusLine(connection: Connection, data: ProjectsResponse?): String = when (connection) {
    Connection.Loading -> "Connecting…"
    Connection.NotConfigured -> "Not configured"
    is Connection.NoRoute -> "Offline"
    is Connection.NeedsToken -> "Locked"
    is Connection.Failed -> "Error"
    Connection.Online -> data?.generatedAt?.let { updated(it) } ?: "Online"
}

private fun updated(iso: String): String = try {
    "Updated " + OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: DateTimeParseException) {
    "Online"
}
