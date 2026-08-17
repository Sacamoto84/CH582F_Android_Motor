package com.example.ch582motor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ch582motor.ble.Params
import com.example.ch582motor.data.Preset
import com.example.ch582motor.vm.UiState

/**
 * Пресеты хранятся на телефоне, не в устройстве: во flash контроллера один
 * набор настроек, и держать там несколько негде.
 */
@Composable
fun PresetsScreen(
    state: UiState,
    presets: List<Preset>,
    diffCount: (Preset) -> Int,
    onSave: (String) -> Unit,
    onApply: (Preset) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var naming by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<Preset?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Button(
                onClick = { naming = true },
                enabled = state.paramsLoaded,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Сохранить текущие настройки") }
        }

        if (presets.isEmpty()) {
            item {
                Text(
                    "Пресетов пока нет. Настройте помпу под конкретную задачу и " +
                        "сохраните — потом вернётесь к этому набору одним касанием.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        items(presets, key = { it.name }) { preset ->
            val diff = diffCount(preset)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${preset.values.size} параметров" +
                            if (state.paramsLoaded) {
                                if (diff == 0) " · совпадает с текущими"
                                else " · отличается в $diff"
                            } else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { applying = preset },
                            enabled = state.connected && diff > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Применить") }
                        OutlinedButton(
                            onClick = { deleting = preset.name },
                            modifier = Modifier.weight(1f),
                        ) { Text("Удалить") }
                    }
                }
            }
        }

        item {
            Text(
                "Пресеты лежат на телефоне: во flash контроллера помещается один " +
                    "набор настроек. Применение пишет значения в устройство, но " +
                    "не во flash — нажмите «Сохранить», если хотите оставить насовсем.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }

    if (naming) {
        NameDialog(
            existing = presets.map { it.name },
            onDismiss = { naming = false },
            onConfirm = {
                naming = false
                onSave(it)
            },
        )
    }

    applying?.let { preset ->
        val touchesCalibration = preset.values.keys.any {
            it == Params.VBAT_SCALE_Q12 || it == Params.POT_RAW_MAX
        }

        AlertDialog(
            onDismissRequest = { applying = null },
            title = { Text("Применить «${preset.name}»?") },
            text = {
                Text(
                    "Изменится параметров: ${diffCount(preset)}." +
                        if (touchesCalibration) {
                            "\n\nПресет содержит калибровку — масштаб вольтметра и верх " +
                                "хода ручки. Если он снят с другого устройства, эти " +
                                "два значения собьются, и их придётся выставить заново."
                        } else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onApply(preset)
                    applying = null
                }) { Text("Применить") }
            },
            dismissButton = {
                TextButton(onClick = { applying = null }) { Text("Отмена") }
            },
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить «$name»?") },
            text = { Text("Пресет исчезнет с телефона. Настройки устройства не изменятся.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(name)
                    deleting = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun NameDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val overwrites = trimmed in existing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название пресета") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(32) },
                    label = { Text("Например: холодная вода") },
                    singleLine = true,
                )
                if (overwrites) {
                    Text(
                        "Пресет с таким названием уже есть — он будет перезаписан.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text(if (overwrites) "Перезаписать" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
