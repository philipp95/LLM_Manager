package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.SignInView

private val ACCOUNT = mapOf("claude" to "your Claude account", "codex" to "your ChatGPT account", "gemini" to "your Google account")
private val CODE_HELP = mapOf(
    "claude" to "Copy the code the page shows after you sign in, and paste it here.",
    "gemini" to "Allow access, then copy the authorization code and paste it here. Gemini waits five minutes for it.",
)
private val KEY_HELP = mapOf(
    "codex" to "An OpenAI API key (platform.openai.com → API keys). Usage is then billed to the API account, not to ChatGPT.",
    "gemini" to "A Gemini API key from Google AI Studio (aistudio.google.com → Get API key).",
)

/**
 * Signs an agent's CLI in on the server: open its sign-in page, then enter the code it shows
 * there (Codex) or paste back the code the page hands out (Claude, Gemini).
 */
@Composable
fun SignInDialog(state: SignInViewModel.State, actions: SignInActions) {
    val agent = state.agent ?: return
    val view = state.view ?: return
    var keyMode by rememberSaveable(agent) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions.onClose,
        title = { Text("Sign in to ${pickName(agent)}") },
        text = { SignInBody(agent, view, state, actions, keyMode) { keyMode = it } },
        confirmButton = { TextButton(onClick = actions.onClose) { Text(if (view.state == "done") "Done" else "Close") } },
        dismissButton = {
            if (view.live) TextButton(onClick = actions.onCancel) { Text("Cancel sign-in", color = Palette.badInk) }
        },
    )
}

@Composable
private fun SignInBody(agent: String, view: SignInView, state: SignInViewModel.State, actions: SignInActions, keyMode: Boolean, onKeyMode: (Boolean) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "This signs in the ${agentLabel(agent)} CLI on the server, the way it signs in on a computer. Your password goes only to the sign-in page.",
            color = Palette.ink2, fontSize = 13.sp,
        )
        when {
            view.state == "done" -> Chip(view.message ?: "Signed in", ChipTone.Ok)
            keyMode -> KeyForm(agent, state, actions) { onKeyMode(false) }
            view.state == "starting" -> Progress("Starting the sign-in on the server…")
            view.live -> Steps(agent, view, state, actions)
            else -> Failed(view, actions)
        }
        if (!keyMode) {
            state.note?.let { Text(it, color = Palette.badInk, fontSize = 13.sp) }
            if (view.screen.isNotEmpty()) Printed(agent, view.screen, open = view.state == "failed")
            if (agent in KEY_HELP && view.state != "done") TextButton(onClick = { onKeyMode(true) }) { Text("Use an API key instead") }
        }
    }
}

@Composable
private fun Steps(agent: String, view: SignInView, state: SignInViewModel.State, actions: SignInActions) {
    val uriHandler = LocalUriHandler.current
    Step(1, "Open the sign-in page and sign in with ${ACCOUNT[agent] ?: "your account"}.") {
        val url = view.safeUrl
        if (url != null) Button(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) { Text("Open the sign-in page") }
        else Text("Waiting for the link…", color = Palette.ink2, fontSize = 13.sp)
    }
    val code = view.userCode
    if (code != null) {
        Step(2, "Enter this code on that page:") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    code, color = Palette.ink, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.background(Palette.field, RoundedCornerShape(12.dp)).border(1.dp, Palette.ink3, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                TextButton(onClick = { actions.onCopy(code) }) { Text("Copy") }
            }
        }
        Step(3, "Come back here: this window notices by itself when you're signed in.") {}
    } else {
        Step(2, CODE_HELP[agent] ?: "Paste the code the page shows.") {
            if (view.needsCode) CodeField(state.busy, actions.onSubmit)
            else Progress(if (view.state == "checking") "Checking the code…" else "Waiting for ${agentLabel(agent)}…")
        }
    }
    TextButton(onClick = actions.onRestart) { Text("New link") }
}

@Composable
private fun CodeField(busy: Boolean, onSubmit: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var code by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = code, onValueChange = { code = it.trim() }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    Row {
        TextButton(onClick = { clipboard.getText()?.text?.trim()?.let { code = it } }) { Text("Paste") }
        Spacer(Modifier.weight(1f))
        Button(onClick = { onSubmit(code) }, enabled = code.isNotEmpty() && !busy) { Text("Continue") }
    }
}

@Composable
private fun KeyForm(agent: String, state: SignInViewModel.State, actions: SignInActions, onBack: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    Text(KEY_HELP[agent].orEmpty(), color = Palette.ink2, fontSize = 13.sp)
    OutlinedTextField(
        value = key, onValueChange = { key = it.trim() }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    state.note?.let { Text(it, color = Palette.badInk, fontSize = 13.sp) }
    Row {
        TextButton(onClick = onBack) { Text("Back") }
        Spacer(Modifier.weight(1f))
        Button(onClick = { actions.onApiKey(key) }, enabled = key.isNotEmpty() && !state.busy) { Text("Use this key") }
    }
}

@Composable
private fun Failed(view: SignInView, actions: SignInActions) {
    Chip(if (view.state == "idle") "No sign-in running" else "Not signed in", if (view.state == "idle") ChipTone.Neutral else ChipTone.Bad)
    view.message?.let { Text(it, color = Palette.ink, fontSize = 13.5.sp) }
    Button(onClick = actions.onRestart) { Text("Try again") }
}

/** A numbered step with what to do under it. */
@Composable
private fun Step(number: Int, text: String, content: @Composable () -> Unit) {
    Row {
        Text(
            number.toString(), color = Palette.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Palette.paper2, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = Palette.ink, fontSize = 14.sp)
            content()
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Palette.ink2, fontSize = 13.sp)
    }
}

/** What the CLI printed, folded away unless something went wrong. */
@Composable
private fun Printed(agent: String, lines: List<String>, open: Boolean) {
    var shown by rememberSaveable(open) { mutableStateOf(open) }
    TextButton(onClick = { shown = !shown }) { Text((if (shown) "Hide" else "Show") + " what ${agentLabel(agent)} printed") }
    if (shown) {
        Text(
            lines.joinToString("\n"), color = Palette.onHarbor, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
            modifier = Modifier.fillMaxWidth().background(Palette.harbor, RoundedCornerShape(10.dp)).padding(10.dp),
        )
    }
}
