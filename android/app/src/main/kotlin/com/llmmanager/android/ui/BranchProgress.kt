package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.Publish

internal data class BranchProgress(val step: Int, val label: String)

internal fun branchProgress(publish: Publish?): BranchProgress {
    val state = publish?.pullRequest?.state
    return when {
        state in setOf("merged", "completed") -> BranchProgress(3, "Merged")
        state in setOf("closed", "abandoned") -> BranchProgress(2, "PR closed")
        state != null -> BranchProgress(2, "PR open")
        publish?.upToDate == true -> BranchProgress(1, "Pushed · ready for PR")
        publish?.pushed == true -> BranchProgress(0, "Push update")
        else -> BranchProgress(0, "Local branch")
    }
}

/** The four publishing stages use text as well as colour. */
@Composable
internal fun BranchFlow(publish: Publish?, modifier: Modifier = Modifier) {
    val progress = branchProgress(publish)
    val labels = listOf("Local", "Pushed", "PR", "Merged")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                val reached = index <= progress.step
                Column(
                    Modifier.weight(1f).semantics { contentDescription = "$label: ${if (reached) "reached" else "pending"}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(24.dp).background(if (reached) Palette.tealInk else Palette.paper2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (index < progress.step) "✓" else "${index + 1}",
                            color = if (reached) Palette.field else Palette.ink2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(label, color = if (reached) Palette.tealInk else Palette.ink2, fontSize = 11.sp)
                }
            }
        }
        Text(progress.label, color = Palette.tealInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
    }
}
