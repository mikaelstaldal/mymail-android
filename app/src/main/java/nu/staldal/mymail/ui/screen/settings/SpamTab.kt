package nu.staldal.mymail.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SpamTab(
    snackbarHostState: SnackbarHostState,
    viewModel: SpamViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    when (val state = uiState) {
        is SpamUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is SpamUiState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Button(onClick = { viewModel.load() }) {
                    Text("Retry")
                }
            }
        }

        is SpamUiState.Loaded -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Enable spam filtering",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { viewModel.onEnabledChange(it) },
                        enabled = !state.isSaving,
                    )
                }

                OutlinedTextField(
                    value = state.scoreHeader,
                    onValueChange = { viewModel.onScoreHeaderChange(it) },
                    label = { Text("Score header name") },
                    singleLine = true,
                    enabled = !state.isSaving,
                    isError = state.inlineError != null,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.scoreThreshold,
                    onValueChange = { viewModel.onScoreThresholdChange(it) },
                    label = { Text("Score threshold") },
                    singleLine = true,
                    enabled = !state.isSaving,
                    isError = state.inlineError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.inlineError != null) {
                    Text(
                        text = state.inlineError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Button(
                    onClick = { viewModel.save() },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSaving) "Saving…" else "Save")
                }
            }
        }
    }
}
