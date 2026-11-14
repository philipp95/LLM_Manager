package com.llmmanager.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.Publish

/** Push / pull request / merge for an agent branch. Merging happens on the forge, with its checks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PublishRow(publish: Publish, forge: String?, branch: String, defaultBranch: String?, onAction: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var confirmMerge by remember { mutableStateOf(false) }
    val pr = publish.pullRequest
    val mergeRequests = forge == "gitlab"
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (publish.upToDate) {
            Chip("pushed", ChipTone.Ok)
        } else {
            OutlinedButton(onClick = { onAction("push") }) { Text(if (publish.pushed) "Push update" else "Push branch", fontSize = 13.sp) }
        }
        pr?.takeIf { it.url.startsWith("https://") }?.let {
            TextButton(onClick = { uriHandler.openUri(it.url) }) {
                Icon(Glyphs.pullRequest, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("${prRef(forge, it.number)} ${it.state}", fontSize = 13.sp)
            }
        }
        if (pr == null && publish.blocked == null && publish.upToDate) {
            Button(onClick = { onAction("pull-request") }) { Text(if (mergeRequests) "Create merge request" else "Create pull request", fontSize = 13.sp) }
        }
        if (pr?.open == true && publish.blocked == null) OutlinedButton(onClick = { confirmMerge = true }) { Text("Merge", fontSize = 13.sp) }
    }
    PublishDetails(publish, mergeRequests)
    if (confirmMerge) {
        AlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text("Merge $branch?") },
            text = { Text("Into ${defaultBranch ?: "the default branch"}. The forge's required checks and approvals still apply.") },
            confirmButton = { TextButton(onClick = { confirmMerge = false; onAction("merge") }) { Text("Merge") } },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PublishDetails(publish: Publish, mergeRequests: Boolean) {
    val uriHandler = LocalUriHandler.current
    if (publish.pullRequest == null && !publish.upToDate && publish.blocked == null) {
        Text("Push the branch, then create a ${if (mergeRequests) "merge request" else "pull request"}.", color = Palette.ink2, fontSize = 12.sp)
    }
    publish.branchUrl?.takeIf { it.startsWith("https://") }?.let { url ->
        TextButton(onClick = { uriHandler.openUri(url) }) { Text("View branch", fontSize = 12.sp) }
    }
    publish.blocked?.let { Text(it, color = Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
}
