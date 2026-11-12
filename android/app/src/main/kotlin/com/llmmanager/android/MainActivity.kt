package com.llmmanager.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llmmanager.android.data.Credentials
import com.llmmanager.android.data.ServerClient
import com.llmmanager.android.data.SettingsClient
import com.llmmanager.android.data.SettingsStore
import com.llmmanager.android.ui.DashboardScreen
import com.llmmanager.android.ui.DashboardViewModel
import com.llmmanager.android.ui.LlmManagerTheme
import com.llmmanager.android.ui.ScheduleViewModel
import com.llmmanager.android.ui.SettingsViewModel
import com.llmmanager.android.ui.SignInViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val settings = SettingsStore(applicationContext)
        val client = ServerClient()
        val credentials = Credentials(settings, client)
        val settingsClient = SettingsClient()
        setContent {
            LlmManagerTheme {
                val viewModel: DashboardViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { DashboardViewModel(settings, client, credentials, BuildConfig.DEFAULT_SERVER_URL) }
                    },
                )
                val scheduleViewModel: ScheduleViewModel = viewModel(
                    factory = viewModelFactory { initializer { ScheduleViewModel(settings, client, credentials) } },
                )
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = viewModelFactory { initializer { SettingsViewModel(settings, settingsClient, credentials) } },
                )
                val signInViewModel: SignInViewModel = viewModel(
                    factory = viewModelFactory { initializer { SignInViewModel(settings, settingsClient, credentials) } },
                )
                DashboardScreen(viewModel, scheduleViewModel, settingsViewModel, signInViewModel)
            }
        }
    }
}
