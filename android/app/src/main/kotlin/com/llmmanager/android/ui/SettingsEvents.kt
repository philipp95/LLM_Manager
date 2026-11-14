package com.llmmanager.android.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Settings messages, and refreshes once an agent got signed in so the chat stops asking. */
@Composable
fun SettingsEvents(settings: SettingsViewModel, signIn: SignInViewModel, schedule: ScheduleViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(settings) { settings.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(signIn) {
        signIn.signedIn.collect { agent ->
            schedule.refreshSchedule()
            settings.load()
            snackbar.showSnackbar("${agentLabel(agent)} is signed in")
        }
    }
}
