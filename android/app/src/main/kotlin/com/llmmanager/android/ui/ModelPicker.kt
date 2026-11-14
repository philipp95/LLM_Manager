package com.llmmanager.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ModelOption

/**
 * The model an agent uses by default, picked from the server's list for that agent. Choosing
 * one saves it at once. A model set earlier that is no longer on the list stays selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(label: String, current: String, models: List<ModelOption>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = buildList {
        add(ModelOption("", "CLI default", "Whatever $label itself picks"))
        addAll(models)
        if (current.isNotEmpty() && models.none { it.id == current }) add(ModelOption(current, current, "Set earlier; not on the list"))
    }
    val selected = options.firstOrNull { it.id == current } ?: options.first()
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = selected.label, onValueChange = {}, readOnly = true, singleLine = true, label = { Text("Default model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, fontSize = 15.sp, fontWeight = if (option.id == current) FontWeight.Bold else FontWeight.Normal)
                            option.note?.let { Text(it, color = Palette.ink2, fontSize = 12.sp) }
                        }
                    },
                    onClick = {
                        open = false
                        if (option.id != current) onPick(option.id)
                    },
                )
            }
        }
    }
}
