package com.example.ch582motor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ch582motor.ble.ParamKind
import com.example.ch582motor.ble.ParamSpec

/** Строка «подпись — значение» для экрана наблюдения. */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** Полоска с подписью: скважность, положение ручки. */
@Composable
fun GaugeRow(label: String, value: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * Редактор одного параметра. Ползунок двигает значение с дебаунсом (onChange),
 * поле ввода и переключатели пишут сразу (onCommit).
 */
@Composable
fun ParamEditor(
    spec: ParamSpec,
    value: Int?,
    enabled: Boolean,
    pending: Boolean,
    onChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(spec.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        spec.key + if (pending) " · запись…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    value?.let(spec::format) ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            when (spec.kind) {
                ParamKind.SWITCH -> Switch(
                    checked = (value ?: 0) != 0,
                    onCheckedChange = { onCommit(if (it) 1 else 0) },
                    enabled = enabled,
                    modifier = Modifier.padding(top = 8.dp),
                )

                ParamKind.ENUM -> EnumPicker(
                    options = spec.options,
                    selected = value ?: spec.default,
                    enabled = enabled,
                    onSelect = onCommit,
                    modifier = Modifier.padding(top = 8.dp),
                )

                ParamKind.NUMBER -> NumberEditor(
                    spec = spec,
                    value = value,
                    enabled = enabled,
                    onChange = onChange,
                    onCommit = onCommit,
                )
            }

            Text(
                spec.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Заводское: ${spec.format(spec.default)}   ·   " +
                    "диапазон ${spec.min}…${spec.max}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun NumberEditor(
    spec: ParamSpec,
    value: Int?,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = (value ?: spec.default).toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = spec.min.toFloat()..spec.max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { new -> text = new.filter(Char::isDigit).take(5) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    text.toIntOrNull()?.let { onCommit(spec.clamp(it)) }
                    // Убрать клавиатуру: значение записано, держать её незачем
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier.padding(start = 8.dp).width(104.dp),
        )
    }
}

@Composable
private fun EnumPicker(
    options: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(options.getOrElse(selected) { selected.toString() })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}
