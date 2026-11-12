package com.llmmanager.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.BuildConfig
import com.llmmanager.android.data.AndroidApp

/** The version running on this phone, as people read it. */
val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

/** Offers the newer app the server has; Android asks the user to confirm the install. */
@Composable
fun UpdateBanner(app: AndroidApp, updating: Boolean, onUpdate: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update available", color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Version ${app.versionName ?: app.versionCode} is ready on the server. You have ${BuildConfig.VERSION_NAME}.",
                    color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            UpdateButton(updating, onUpdate)
        }
        Text(
            "Android asks you to confirm. The first time, allow LLM Manager to install apps.",
            color = Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun UpdateButton(updating: Boolean, onUpdate: () -> Unit) {
    Button(onClick = onUpdate, enabled = !updating) {
        if (updating) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Palette.onRust, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Downloading")
        } else {
            Text("Update")
        }
    }
}
