package com.homoludens.citacknjiga.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.tts.onnx.TtsEngine
import com.homoludens.citacknjiga.ui.theme.CitacKnjigaTheme

@Composable
public fun SettingsScreen(
    selectedEngine: TtsEngine,
    availableEngines: List<TtsEngine>,
    onEngineSelected: (TtsEngine) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(stringResource(R.string.settings_speech_voice)) {
            Text(stringResource(R.string.settings_default_engine), style = MaterialTheme.typography.labelLarge)
            Column(modifier = Modifier.selectableGroup()) {
                TtsEngine.entries.forEach { engine ->
                    val enabled = engine in availableEngines
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedEngine == engine,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onEngineSelected(engine) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedEngine == engine,
                            onClick = null,
                            enabled = enabled,
                        )
                        Column {
                            Text(stringResource(engine.label()))
                            if (!enabled) {
                                Text(
                                    stringResource(R.string.settings_engine_not_ready),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            SettingsValue(stringResource(R.string.settings_language), stringResource(R.string.settings_serbian))
        }
        SettingsSection(stringResource(R.string.settings_audio)) {
            SettingsValue(stringResource(R.string.settings_sample_rate), "24 kHz")
            SettingsValue(stringResource(R.string.settings_audio_format), "WAV (PCM16)")
        }
        SettingsSection(stringResource(R.string.settings_storage)) {
            SettingsValue(stringResource(R.string.settings_download_location), stringResource(R.string.settings_internal_storage))
            Text(
                stringResource(R.string.settings_storage_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection(stringResource(R.string.settings_appearance)) {
            SettingsValue(stringResource(R.string.settings_theme), stringResource(R.string.settings_light_theme))
        }
        SettingsSection(stringResource(R.string.settings_advanced)) {
            Text(
                stringResource(R.string.settings_advanced_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diagnostics_about_title))
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun TtsEngine.label(): Int = when (this) {
    TtsEngine.KOKORO -> R.string.engine_kokoro
    TtsEngine.VITS -> R.string.engine_vits
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    CitacKnjigaTheme {
        SettingsScreen(TtsEngine.VITS, TtsEngine.entries, {}, {})
    }
}
