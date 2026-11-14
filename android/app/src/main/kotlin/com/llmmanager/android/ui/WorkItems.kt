package com.llmmanager.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.RepoWorkItem

@Composable
internal fun WorkItemsPanel(project: Project) {
    val data = project.workItems
    val azure = project.forge == "azure-devops"
    val uriHandler = LocalUriHandler.current
    val web = (project.webUrl ?: project.gitHubUrl)?.takeIf { it.startsWith("https://") }
    val browse = web?.let { if (azure) it.substringBefore("/_git/") + "/_workitems" else "$it/issues" }
    Panel {
        PanelTitle(if (azure) "Linked work items" else "GitHub issues")
        data?.fetchedAt?.let { Text("Updated ${relativeTime(it)}", color = Palette.ink2, fontSize = 12.sp) }
        data?.error?.let { Text("Couldn't refresh: $it", color = Palette.warnInk, fontSize = 13.sp) }
        WorkItemsStatus(data, azure)
        data?.items?.forEach { item ->
            HorizontalDivider(color = Palette.paperLine)
            WorkItemRow(item)
        }
        if (browse != null) TextButton(onClick = { uriHandler.openUri(browse) }) {
            Text(if (azure) "Open Azure Boards" else "All issues on GitHub")
            Spacer(Modifier.width(6.dp))
            Icon(Glyphs.link, contentDescription = null, modifier = Modifier.size(15.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkItemRow(item: RepoWorkItem) {
    val uriHandler = LocalUriHandler.current
    val url = item.url.takeIf { it.startsWith("https://") }
    Row(
        Modifier.fillMaxWidth()
            .then(if (url != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${item.type} ${item.number}") {
                uriHandler.openUri(url)
            } else Modifier)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Chip("#${item.number}", mono = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(item.title, color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip(item.type)
                Chip(item.state)
                item.assignee?.let { Chip(it) }
                item.labels.forEach { Chip(it) }
            }
        }
        if (url != null) Icon(Glyphs.link, contentDescription = null, tint = Palette.ink2, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun WorkItemsStatus(data: com.llmmanager.android.data.RepoWorkItems?, azure: Boolean) {
        if (data == null) {
            Muted("Reading ${if (azure) "linked work items" else "open issues"}…")
        } else if (data.fetchedAt != null) {
            Text(data.scope, color = if (data.truncated) Palette.warnInk else Palette.ink2, fontSize = 12.sp)
            if (data.items.isEmpty()) Muted(if (azure) "No repository-linked work items found in this scan." else "No open issues in this repository.")
        }
}
