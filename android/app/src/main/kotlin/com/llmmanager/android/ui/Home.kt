package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.CloneStatus

/** Pins a repository to the top of the home screen, or lets it go. */
@Composable
fun PinButton(pinned: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(TOUCH).clickable(role = Role.Checkbox, onClickLabel = if (pinned) "Unpin" else "Pin to the top", onClick = onToggle)
            .semantics { contentDescription = if (pinned) "Pinned" else "Not pinned" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).background(if (pinned) Palette.rust else Palette.paper2, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.pin, contentDescription = null, tint = if (pinned) Palette.onRust else Palette.ink3, modifier = Modifier.size(18.dp))
        }
    }
}

/** Zero to five stars: tap a star to give that many, tap the last lit one again to take them all away. */
@Composable
fun StarRating(stars: Int, onRate: (Int) -> Unit) {
    Row(Modifier.semantics { contentDescription = "$stars of $MAX_STARS stars" }) {
        for (n in 1..MAX_STARS) {
            Box(
                Modifier.size(width = 36.dp, height = TOUCH)
                    .clickable(onClickLabel = if (n == stars) "Remove the stars" else "Give $n stars") { onRate(if (n == stars) 0 else n) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = if (n <= stars) Palette.rust else Palette.paper3, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** The way into the Server chat, set apart from the repositories: its agent is not confined to one. */
@Composable
fun ServerChatEntry(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().background(Palette.harbor2, shape).border(1.dp, Palette.harborLine, shape)
            .clickable(onClickLabel = "Open the Server chat", onClick = onOpen).heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.server, contentDescription = null, tint = Palette.onHarbor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Server chat", color = Palette.onHarbor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Change the server itself. Not confined to a repository.", color = Palette.onHarbor2, fontSize = 12.5.sp)
        }
        Chip("Unconfined", ChipTone.Warn)
    }
}

/** Add a repository by pasting its address; the server clones it in the background. */
@Composable
fun AddRepository(state: ReposViewModel.State, onAdd: (url: String, onStarted: () -> Unit) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val submit = { if (url.isNotBlank() && !state.adding) onAdd(url) { url = "" } }
    Panel {
        PanelTitle("Add a repository")
        Text(
            "Paste its GitHub, GitLab or Azure DevOps address. The server clones it in the background and it appears here.",
            color = Palette.ink2, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://github.com/owner/repository") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Palette.field, unfocusedContainerColor = Palette.field),
        )
        Button(
            onClick = submit, enabled = url.isNotBlank() && !state.adding, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = TOUCH),
        ) {
            if (state.adding) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.onRust, strokeWidth = 2.dp)
            } else {
                Icon(Glyphs.plus, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Add repository", fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A finished clone is on the home screen; only the ones still going, or that failed, are listed.
            state.clones.filter { it.state != "done" }.forEach { CloneRow(it) }
        }
    }
}

@Composable
private fun CloneRow(clone: CloneStatus) {
    val failed = clone.state == "failed"
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clone.name.ifEmpty { clone.url }, color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (failed) Chip("Failed", ChipTone.Bad) else Chip("Cloning", ChipTone.Live, dot = true)
        }
        clone.message?.takeIf { failed }?.let { Text(it.take(MAX_CLONE_MESSAGE), color = Palette.badInk, fontSize = 12.5.sp) }
    }
}

private val TOUCH = 48.dp
private const val MAX_STARS = 5
private const val MAX_CLONE_MESSAGE = 300
