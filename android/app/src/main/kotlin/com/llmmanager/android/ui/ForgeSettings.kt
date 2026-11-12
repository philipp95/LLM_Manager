package com.llmmanager.android.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ForgeAccount
import com.llmmanager.android.data.ForgeTokenRequest

/** A token form for one code host: what to ask for, and where such a token is made. */
private data class TokenForm(
    val forge: String,
    val title: String,
    val help: String,
    val link: String? = null,
    /** GitLab: the instance the token is for; null asks for it. */
    val host: String? = null,
    val asksHost: Boolean = false,
    val asksOrganization: Boolean = false,
)

/** One row: a code host's status, the token form it opens, and how it is disconnected. */
private class ForgeRowSpec(
    val account: ForgeAccount?,
    val title: String,
    val form: TokenForm,
    val connectLabel: String,
    val removeLabel: String,
    val removeQuestion: String,
)

/** GitHub (through the gh CLI), each GitLab, Azure DevOps: tokens are checked with the host, then kept on the server only. */
@Composable
fun ForgesPanel(forges: List<ForgeAccount>, actions: SettingsActions) {
    var editing by remember { mutableStateOf<TokenForm?>(null) }
    var removing by remember { mutableStateOf<ForgeRowSpec?>(null) }
    Panel {
        forgeRows(forges).forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = Palette.paperLine, modifier = Modifier.padding(vertical = 12.dp))
            ForgeRow(row, onConnect = { editing = row.form }, onRemove = { removing = row })
        }
    }
    editing?.let { form ->
        TokenDialog(form, onDismiss = { editing = null }) { request -> actions.onConnectForge(form.forge, request) { editing = null } }
    }
    removing?.let { row ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(row.removeQuestion) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    actions.onDisconnectForge(row.form.forge, row.account?.host?.takeIf { row.form.forge == "gitlab" })
                }) { Text(row.removeLabel, color = Palette.badInk) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

private fun forgeRows(forges: List<ForgeAccount>): List<ForgeRowSpec> = buildList {
    forges.firstOrNull { it.forge == "github" }?.let { github ->
        val form = TokenForm(
            "github", "GitHub token",
            "A classic token with the repo, read:org and workflow scopes, or a fine-grained one. It goes to the GitHub CLI (gh) on the server.",
            link = "https://github.com/settings/tokens/new?scopes=repo,read:org,workflow&description=LLM%20Manager",
        )
        val question = "Sign the GitHub CLI on the server out? Pull requests on GitHub stop until it signs in again."
        add(ForgeRowSpec(github, "GitHub", form, "Sign in with a token", "Sign out", question))
    }
    forges.filter { it.forge == "gitlab" }.forEach { gitlab ->
        val form = TokenForm(
            "gitlab", "GitLab token for ${gitlab.host}", "A personal access token with the api scope.",
            link = "https://${gitlab.host}/-/user_settings/personal_access_tokens?name=LLM%20Manager&scopes=api", host = gitlab.host,
        )
        val title = if (gitlab.host == "gitlab.com") "GitLab" else "GitLab · ${gitlab.host}"
        add(ForgeRowSpec(gitlab, title, form, "Add a token", "Remove token", "Remove the GitLab token for ${gitlab.host}?"))
    }
    val another = TokenForm(
        "gitlab", "Another GitLab server", "Its host name (it must contain \"gitlab\", as gitlab.example.com does) and a token with the api scope.",
        asksHost = true,
    )
    add(ForgeRowSpec(null, "Another GitLab server", another, "Add a server", "", ""))
    forges.firstOrNull { it.forge == "azure-devops" }?.let { azure ->
        val form = TokenForm(
            "azure-devops", "Azure DevOps token", "A personal access token with Code (Read & write), made in your organisation's user settings.",
            asksOrganization = true,
        )
        add(ForgeRowSpec(azure, "Azure DevOps", form, "Add a token", "Remove token", "Remove the Azure DevOps token?"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForgeRow(row: ForgeRowSpec, onConnect: () -> Unit, onRemove: () -> Unit) {
    val account = row.account
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForgeBadge(row.form.forge)
            Spacer(Modifier.width(8.dp))
            Text(row.title, color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            when {
                account == null -> Unit
                account.connected -> Chip(account.account?.let { "Signed in as $it" } ?: "Connected", ChipTone.Ok)
                else -> Chip("Not connected")
            }
        }
        account?.note?.let { Text(it, color = Palette.ink2, fontSize = 12.5.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account?.connected == true) {
                OutlinedButton(onClick = onConnect) { Text("Use another token") }
                TextButton(onClick = onRemove) { Text(row.removeLabel, color = Palette.badInk) }
            } else {
                Button(onClick = onConnect) { Text(row.connectLabel) }
            }
        }
    }
}

/** The token goes to the server once, which checks it with the host; it is never shown again. */
@Composable
private fun TokenDialog(form: TokenForm, onDismiss: () -> Unit, onSubmit: (ForgeTokenRequest) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var host by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(form.title) },
        text = {
            Column {
                if (form.asksHost) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it.trim() }, label = { Text("Host") }, placeholder = { Text("gitlab.example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                }
                if (form.asksOrganization) {
                    OutlinedTextField(
                        value = organization, onValueChange = { organization = it.trim() }, label = { Text("Organisation") },
                        supportingText = { Text("As in dev.azure.com/<organisation>") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                }
                OutlinedTextField(
                    value = token, onValueChange = { token = it.trim() }, label = { Text("Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                Text(form.help, color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
                form.link?.let { link -> TextButton(onClick = { uriHandler.openUri(link) }) { Text("Create a token") } }
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotEmpty() && (!form.asksHost || host.isNotEmpty()) && (!form.asksOrganization || organization.isNotEmpty()),
                onClick = {
                    val chosenHost = form.host ?: host.takeIf { form.asksHost }
                    onSubmit(ForgeTokenRequest(token, host = chosenHost, organization = organization.takeIf { form.asksOrganization }))
                },
            ) { Text("Check and save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
