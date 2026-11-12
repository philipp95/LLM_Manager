package com.llmmanager.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.BuildConfig
import com.llmmanager.android.data.ForgeTokenRequest
import com.llmmanager.android.data.SettingsResponse

/** What the Settings tab can change, routed to the view models by the caller. */
class SettingsActions(
    val onSaveProfile: (name: String, email: String, defaultAgent: String?) -> Unit,
    val onSaveAgent: (agent: String, enabled: Boolean, model: String) -> Unit,
    val onSignIn: (agent: String) -> Unit,
    val onSignOut: (agent: String) -> Unit,
    val onConnectForge: (forge: String, request: ForgeTokenRequest, onSaved: () -> Unit) -> Unit,
    val onDisconnectForge: (forge: String, host: String?) -> Unit,
)

/** This phone: the server it talks to, how it got access, and the app version. */
class PhoneSettings(val serverUrl: String, val pairedAs: String?, val hasToken: Boolean, val update: UpdateState, val onServer: () -> Unit)

/** The Settings tab: profile, agents and their sign-ins, code hosts, and this phone. */
@Composable
fun SettingsScreen(state: SettingsViewModel.State, actions: SettingsActions, phone: PhoneSettings, onRetry: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        val data = state.data
        if (data == null) {
            item(key = "loading") {
                Column {
                    BackgroundNote(state.error ?: "Loading…")
                    if (state.error != null) OutlinedButton(onClick = onRetry) { Text("Try again", color = Palette.onHarbor) }
                }
            }
        } else {
            item(key = "profile-title") { SectionTitle("Profile") }
            item(key = "profile") { ProfilePanel(data, state.saving, actions) }
            item(key = "agents-title") { SectionTitle("Agents") }
            item(key = "agents") { AgentsPanel(data.agents, actions) }
            item(key = "forges-title") { SectionTitle("Code hosts") }
            item(key = "forges") { ForgesPanel(data.forges, actions) }
        }
        item(key = "phone-title") { SectionTitle("This phone") }
        item(key = "phone") { PhonePanel(phone) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePanel(data: SettingsResponse, saving: Boolean, actions: SettingsActions) {
    var name by rememberSaveable(data.profile.name) { mutableStateOf(data.profile.name) }
    var email by rememberSaveable(data.profile.email) { mutableStateOf(data.profile.email) }
    var agent by rememberSaveable(data.defaultAgent) { mutableStateOf(data.defaultAgent) }
    val offered = data.agents.filter { it.installed && it.enabled }.map { it.agent }
    Panel {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
        )
        Text(
            "Your agents' commits name you as the committer, so they show up on your account on GitHub, GitLab or Azure DevOps. " +
                "The agent stays the author.",
            color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Text("Agent in the chat", color = Palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = agent == null, onClick = { agent = null }, label = { Text("Last used") })
            offered.forEach { option ->
                FilterChip(selected = agent == option, onClick = { agent = option }, label = { Text(pickName(option)) })
            }
        }
        Button(onClick = { actions.onSaveProfile(name, email, agent) }, enabled = !saving, modifier = Modifier.padding(top = 8.dp)) { Text("Save profile") }
    }
}

@Composable
private fun PhonePanel(phone: PhoneSettings) {
    val offered = phone.update.offered?.takeIf { it.newerThan(BuildConfig.VERSION_CODE) }
    Panel {
        Text(phone.serverUrl.ifEmpty { "No server set" }, color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            when {
                phone.pairedAs != null -> "Paired automatically as ${phone.pairedAs} through Tailscale."
                phone.hasToken -> "Using the access token you entered."
                else -> "Not paired yet."
            },
            color = Palette.ink2, fontSize = 13.sp,
        )
        OutlinedButton(onClick = phone.onServer, modifier = Modifier.padding(top = 6.dp)) { Text("Server and access token") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("LLM Manager $appVersion", color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    offered?.let { "Version ${it.versionName ?: it.versionCode} is available" } ?: "Up to date with the server",
                    color = Palette.ink2, fontSize = 12.5.sp,
                )
            }
            if (offered != null) UpdateButton { phone.update.onUpdate(offered) }
        }
    }
}

/** How the pickers name an agent: Codex signs in with ChatGPT. */
fun pickName(agent: String): String = if (agent == "codex") "Codex (ChatGPT)" else agentLabel(agent)
