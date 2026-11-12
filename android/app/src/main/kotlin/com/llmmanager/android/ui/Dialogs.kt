package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.BuildConfig

@Composable
fun SettingsDialog(
    serverUrl: String,
    hasToken: Boolean,
    pairedAs: String?,
    onSave: (url: String, token: String?) -> String?,
    onForgetToken: () -> Unit,
    onDismiss: () -> Unit,
    update: UpdateState,
) {
    var url by remember { mutableStateOf(serverUrl) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server & access") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text("Server address") }, singleLine = true, isError = error != null,
                    supportingText = { Text(error ?: "Tailscale address of the dashboard") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        pairedAs != null -> "Paired automatically as $pairedAs: this phone is signed in to the server's Tailscale account."
                        hasToken -> "Using the access token you entered."
                        else -> "Phones signed in to the server's Tailscale account pair automatically; others need the access token."
                    },
                    color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(if (hasToken) "Replace with the access token" else "Access token") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = { Text("Only needed when pairing is not possible. On the server: cat ~/.config/llm-manager/api-token") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (hasToken) {
                    TextButton(onClick = { onForgetToken(); onDismiss() }) { Text("Remove saved token", color = Palette.badInk) }
                }
                VersionRow(update)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onSave(url, token.ifBlank { null })
                if (error == null) onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** This app's version, and the update the server offers when it has a newer one. */
@Composable
private fun VersionRow(update: UpdateState) {
    val offered = update.offered?.takeIf { it.newerThan(BuildConfig.VERSION_CODE) }
    HorizontalDivider(color = Palette.paperLine, modifier = Modifier.padding(vertical = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("LLM Manager $appVersion", color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    offered != null -> "Version ${offered.versionName ?: offered.versionCode} is available"
                    update.offered?.versionCode != null -> "Up to date"
                    else -> "The server has no app build to compare with"
                },
                color = Palette.ink2, fontSize = 12.5.sp,
            )
        }
        if (offered != null) UpdateButton(update.updating) { update.onUpdate(offered) }
    }
}

@Composable
fun ConfirmStartDialog(projectName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start Codex?") },
        text = { Text("Codex will start on $projectName in a new worktree and branch. Your checkout is not touched.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun StartedDialog(event: DashboardViewModel.Event.Started, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val command = event.launch.session.attachCommand
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codex is running") },
        text = {
            Column {
                Text("${event.projectName} · ${event.launch.branch}", color = Palette.ink2, fontSize = 13.sp)
                Text(
                    command, color = Palette.attachText, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth().background(Palette.harbor, RoundedCornerShape(10.dp)).padding(10.dp),
                )
                Text("Paste this in your SSH app to attach. Detach with Ctrl-b then d; Codex keeps running.", color = Palette.ink2, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(command); onDismiss() }) { Text("Copy command") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
