package com.wadio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wadio.app.WadioController

@Composable
fun AuthSheet(controller: WadioController, onBiometric: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val isSetup = !state.hasPin

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isSetup) "Create a 4-digit PIN" else "Enter your 4-digit PIN",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.passInput,
                    onValueChange = { controller.updatePinInput(it) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = if (isSetup) ImeAction.Next else ImeAction.Done,
                    ),
                )

                if (isSetup) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.confirmPassInput,
                        onValueChange = { controller.updateConfirmInput(it) },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }

                if (state.pinError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.pinError!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { controller.submitPin() },
                    enabled = !state.authPending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        state.authPending -> "Checking…"
                        isSetup -> "Create PIN"
                        else -> "Unlock with PIN"
                    })
                }

                Spacer(Modifier.height(8.dp))
                if (!isSetup) {
                    OutlinedButton(
                        onClick = onBiometric,
                        enabled = !state.authPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Use fingerprint / device lock") }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { controller.dismissAuthPrompt() }) { Text("Cancel") }
            }
        }
    }
}
