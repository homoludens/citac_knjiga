package com.homoludens.citacknjiga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.homoludens.citacknjiga.proof.LocalWavPlayer
import com.homoludens.citacknjiga.proof.TypedTextProofController
import com.homoludens.citacknjiga.proof.TypedTextProofDiagnostics
import com.homoludens.citacknjiga.proof.TypedTextProofEngine
import com.homoludens.citacknjiga.proof.TypedTextProofState
import com.homoludens.citacknjiga.proof.TypedTextProofStatus

@Composable
public fun CitacKnjigaApp(
    variant: AppVariant,
    proofEngine: TypedTextProofEngine? = null,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    MaterialTheme {
        NavHost(
            navController = navController,
            startDestination = AppRoute.Start.path,
            modifier = modifier,
        ) {
            composable(AppRoute.Start.path) {
                StartScreen(variant = variant, proofEngine = proofEngine)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StartScreen(variant: AppVariant, proofEngine: TypedTextProofEngine?) {
    val controller = remember(proofEngine) {
        TypedTextProofController(proofEngine ?: MissingProofEngine())
    }
    val player = remember { LocalWavPlayer() }
    val playbackScope = rememberCoroutineScope()
    DisposableEffect(controller, player) {
        onDispose {
            player.close()
            controller.close()
        }
    }
    val state by controller.state.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Srpski tekst u govor") }) },
    ) { paddingValues ->
        TypedTextProofContent(
            paddingValues = paddingValues,
            variant = variant,
            state = state,
            onTextChanged = controller::setText,
            onGenerate = controller::generate,
            onCancel = controller::cancel,
            onPlay = { state.wav?.file?.let { player.play(it, playbackScope) } },
            onStop = player::stop,
        )
    }
}

@Composable
private fun TypedTextProofContent(
    paddingValues: PaddingValues,
    variant: AppVariant,
    state: TypedTextProofState,
    onTextChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Проба српске синтезе",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Унесите текст на латиници или ћирилици. Обрада и звук остају на уређају.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 10,
            label = { Text("Текст") },
        )
        Text("Стање: ${state.status.displayName()}", style = MaterialTheme.typography.titleMedium)
        if (state.status == TypedTextProofStatus.ERROR) {
            Text(state.errorMessage ?: "Генерисање није успело.", color = MaterialTheme.colorScheme.error)
        }
        if (state.status == TypedTextProofStatus.GENERATING) {
            OutlinedButton(onClick = onCancel) { Text("Откажи") }
        } else {
            Button(onClick = onGenerate, enabled = state.text.isNotBlank()) { Text("Генериши") }
        }
        state.diagnostics?.let { diagnostics ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Дијагностика", style = MaterialTheme.typography.titleMedium)
                    DiagnosticValue("Очишћен текст", diagnostics.cleanupText)
                    DiagnosticValue("Нормализован текст", diagnostics.normalizedText)
                    DiagnosticValue("Фонеме", diagnostics.phonemes)
                    DiagnosticValue("ID токена", diagnostics.tokenIds.joinToString())
                    DiagnosticValue("Заштићени опсези", diagnostics.protectedSpans.joinToString().ifEmpty { "нема" })
                    DiagnosticValue("Границе делова", diagnostics.chunkBoundaries.joinToString().ifEmpty { "нема" })
                    DiagnosticValue("Глас / ред", "${diagnostics.model.voice} / ${diagnostics.voiceRowIndex}")
                    DiagnosticValue("Модел", "${diagnostics.model.packageId} ${diagnostics.model.packageVersion}")
                    DiagnosticValue("Порекло пакета", diagnostics.model.packageSha256)
                    DiagnosticValue("Распоред", diagnostics.model.runtime)
                    DiagnosticValue("Претпроцесирање", diagnostics.model.preprocessing)
                }
            }
        }
        state.wav?.let { wav ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Готов WAV", style = MaterialTheme.typography.titleMedium)
                    Text("24 kHz, mono, PCM16, ${wav.sampleCount} samples")
                    Text(wav.file.name, style = MaterialTheme.typography.labelMedium)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onPlay) { Text("Пусти") }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onStop) { Text("Заустави") }
                    }
                }
            }
        }
        Text("Дистрибуција: ${variant.distribution.id}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DiagnosticValue(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

private fun TypedTextProofStatus.displayName(): String = when (this) {
    TypedTextProofStatus.IDLE -> "спремно"
    TypedTextProofStatus.GENERATING -> "генерисање"
    TypedTextProofStatus.SUCCESS -> "успешно"
    TypedTextProofStatus.ERROR -> "грешка"
    TypedTextProofStatus.CANCELLED -> "отказано"
}

private class MissingProofEngine : TypedTextProofEngine {
    override suspend fun generate(
        text: String,
        onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
    ): com.homoludens.citacknjiga.proof.TypedTextProofResult =
        error("No verified model package is installed. Import a compatible package before generating.")
}
