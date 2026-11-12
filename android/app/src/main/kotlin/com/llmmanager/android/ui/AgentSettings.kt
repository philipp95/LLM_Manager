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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.AgentSettings

/** Each agent: how its CLI is signed in on the server, whether the chat offers it, and its model. */
@Composable
fun AgentsPanel(agents: List<AgentSettings>, actions: SettingsActions) {
    Panel {
        agents.forEachIndexed { index, agent ->
            if (index > 0) HorizontalDivider(color = Palette.paperLine, modifier = Modifier.padding(vertical = 12.dp))
            AgentRow(agent, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentRow(a: AgentSettings, actions: SettingsActions) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var model by rememberSaveable(a.agent, a.model) { mutableStateOf(a.model) }
    val label = agentLabel(a.agent)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentMark(a.agent, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(pickName(a.agent), color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            when {
                !a.installed -> Chip("Not installed", ChipTone.Warn)
                a.signedIn -> Chip("Signed in" + (a.method?.let { " · $it" } ?: ""), ChipTone.Ok)
                else -> Chip("Not signed in", ChipTone.Bad)
            }
        }
        a.account?.let { Text(it, color = Palette.ink2, fontSize = 13.sp) }
        if (!a.installed) {
            Text("Install the $label CLI on the server to use it.", color = Palette.ink2, fontSize = 13.sp)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val signInLabel = when {
                a.signIn != null -> "Continue signing in"
                a.signedIn -> "Switch account"
                else -> "Sign in"
            }
            if (a.signedIn) OutlinedButton(onClick = { actions.onSignIn(a.agent) }) { Text(signInLabel) }
            else Button(onClick = { actions.onSignIn(a.agent) }) { Text(signInLabel) }
            if (a.signedIn) TextButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = Palette.badInk) }
        }
        Row(Modifier.fillMaxWidth().clickable { actions.onSaveAgent(a.agent, !a.enabled, model) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = a.enabled, onCheckedChange = { actions.onSaveAgent(a.agent, it, model) })
            Text("Offer $label in the chat and in new jobs", color = Palette.ink, fontSize = 13.5.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = model, onValueChange = { model = it }, label = { Text("Model") }, placeholder = { Text("CLI default") },
                singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { actions.onSaveAgent(a.agent, a.enabled, model) }, enabled = model != a.model) { Text("Save") }
        }
        Text("The name $label's --model option takes. Empty uses the CLI's default.", color = Palette.ink2, fontSize = 12.sp)
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign $label out?") },
            text = { Text("$label is signed out on the server. Its runs fail until it is signed in again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    actions.onSignOut(a.agent)
                }) { Text("Sign out", color = Palette.badInk) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Keep") } },
        )
    }
}
