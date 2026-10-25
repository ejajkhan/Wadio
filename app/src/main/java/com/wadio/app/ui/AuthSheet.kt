package com.wadio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wadio.app.WadioController

@Composable
fun AuthSheet(controller: WadioController, onBiometric: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val isSetup = !state.hasPin
    var setupConfirm by remember(isSetup) { mutableStateOf(false) }
    var firstPin by remember(isSetup) { mutableStateOf("") }
    var setupEntry by remember(isSetup) { mutableStateOf("") }
    val currentEntry = if (isSetup) setupEntry else state.passInput

    fun addDigit(digit: String) {
        if (state.authPending || currentEntry.length >= 4) return
        val next = currentEntry + digit
        if (!isSetup) {
            controller.updatePinInput(next)
            if (next.length == 4) controller.submitPin()
            return
        }
        setupEntry = next
        if (next.length == 4) {
            if (!setupConfirm) {
                firstPin = next
                setupEntry = ""
                setupConfirm = true
            } else {
                controller.updatePinInput(firstPin)
                controller.updateConfirmInput(next)
                controller.submitPin()
                if (firstPin != next) {
                    firstPin = ""
                    setupEntry = ""
                    setupConfirm = false
                }
            }
        }
    }

    fun deleteDigit() {
        if (state.authPending) return
        if (isSetup) setupEntry = setupEntry.dropLast(1)
        else controller.updatePinInput(state.passInput.dropLast(1))
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0B1018),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    isSetup && !setupConfirm -> "Create a 4-digit PIN"
                    isSetup -> "Confirm your PIN"
                    else -> "Unlock Wadio"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    isSetup && !setupConfirm -> "Choose a PIN to protect your private media"
                    isSetup -> "Enter the same four digits again"
                    else -> "Enter your PIN or use fingerprint / device lock"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB9C2D0), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { index ->
                    Box(
                        Modifier.size(14.dp).background(
                            if (index < currentEntry.length) MaterialTheme.colorScheme.primary else Color(0xFF394352),
                            CircleShape,
                        )
                    )
                }
            }
            if (state.pinError != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.pinError!!, color = Color(0xFFFFB4AB), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    row.forEach { digit ->
                        FilledTonalButton(
                            onClick = { addDigit(digit) },
                            enabled = !state.authPending && currentEntry.length < 4,
                            modifier = Modifier.size(72.dp), shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(digit, fontSize = 27.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { deleteDigit() }, enabled = !state.authPending && currentEntry.isNotEmpty(),
                    modifier = Modifier.size(72.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Rounded.Backspace, contentDescription = "Delete digit")
                }
                FilledTonalButton(onClick = { addDigit("0") }, enabled = !state.authPending && currentEntry.length < 4,
                    modifier = Modifier.size(72.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) {
                    Text("0", fontSize = 27.sp, fontWeight = FontWeight.Medium)
                }
                FilledTonalButton(
                    onClick = { if (!isSetup && state.passInput.length == 4) controller.submitPin() },
                    enabled = !isSetup && !state.authPending && state.passInput.length == 4,
                    modifier = Modifier.size(72.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (state.authPending) "…" else "OK", fontSize = 18.sp)
                }
            }
            if (!isSetup) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBiometric, enabled = !state.authPending) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Fingerprint / device lock")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { controller.dismissAuthPrompt() }, enabled = !state.authPending) {
                Text("Cancel", color = Color(0xFFB9C2D0))
            }
        }
    }
}
