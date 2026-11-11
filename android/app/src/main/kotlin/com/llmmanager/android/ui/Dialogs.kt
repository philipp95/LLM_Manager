package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsDialog(
    serverUrl: String,
    hasToken: Boolean,
    onSave: (url: String, token: String?) -> String?,
    onForgetToken: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(serverUrl) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server & access") },
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text("Server address") }, singleLine = true, isError = error != null,
                    supportingText = { Text(error ?: "Tailscale address of the dashboard") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(if (hasToken) "New access token (saved)" else "Access token") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = { Text("On the server: cat ~/.config/llm-manager/api-token") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (hasToken) {
                    TextButton(onClick = { onForgetToken(); onDismiss() }) { Text("Remove saved token", color = Palette.badInk) }
                }
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
