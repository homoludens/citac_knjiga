package com.homoludens.citacknjiga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
public fun CitacKnjigaApp(variant: AppVariant, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    MaterialTheme {
        NavHost(
            navController = navController,
            startDestination = AppRoute.Start.path,
            modifier = modifier,
        ) {
            composable(AppRoute.Start.path) {
                StartScreen(variant = variant)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StartScreen(variant: AppVariant) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Citac knjiga") }) },
    ) { paddingValues ->
        StartContent(paddingValues = paddingValues, variant = variant)
    }
}

@Composable
private fun StartContent(paddingValues: PaddingValues, variant: AppVariant) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Offline citanje, na vasem uredjaju.",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Ova pocetna osnova cuva obradu knjiga i zvuka lokalno, bez rutinskih mreznih servisa.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status osnove", style = MaterialTheme.typography.titleMedium)
                Text("Aplikacija je spremna za naredne lokalne funkcije.")
                Text(
                    text = "Distribucija: ${variant.distribution.id}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
