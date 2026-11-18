package com.alinx.plume.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.alinx.plume.sync.SyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bouton nuage de la barre du haut : état de la synchronisation, ouvre la fenêtre du compte. */
@Composable
fun SyncButton() {
    val status by SyncManager.status.collectAsState()
    var open by remember { mutableStateOf(false) }
    val icon = when {
        status.email == null -> Icons.Filled.Cloud
        status.running -> Icons.Filled.CloudSync
        status.error != null -> Icons.Filled.CloudOff
        else -> Icons.Filled.CloudDone
    }
    IconButton(onClick = { open = true }) {
        Icon(
            icon,
            contentDescription = "Synchronisation",
            tint = if (status.error != null && status.email != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) SyncDialog(onDismiss = { open = false })
}

@Composable
fun SyncDialog(onDismiss: () -> Unit) {
    val status by SyncManager.status.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun submit(create: Boolean) {
        busy = true
        message = null
        scope.launch {
            message = SyncManager.signIn(email, password, create)
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Synchronisation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !status.configured -> Text("La synchronisation n'est pas encore activée dans cette version de Plume.")
                    status.email == null -> {
                        Text("Connectez-vous pour retrouver vos notes sur votre PC et vos autres appareils.")
                        OutlinedTextField(
                            value = email, onValueChange = { email = it }, label = { Text("E-mail") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it }, label = { Text("Mot de passe") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { submit(false) }, enabled = !busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("Se connecter")
                        }
                        OutlinedButton(onClick = { submit(true) }, enabled = !busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("Créer un compte")
                        }
                    }
                    else -> {
                        Text("Connecté : ${status.email}")
                        Text(
                            when {
                                status.running -> "Synchronisation en cours…"
                                status.error != null -> status.error!!
                                status.lastSync > 0 -> "Dernière synchronisation : " +
                                    SimpleDateFormat("d MMM 'à' HH:mm", Locale.FRANCE).format(Date(status.lastSync))
                                else -> "Pas encore synchronisé."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { SyncManager.requestSync(0) }, enabled = !status.running, modifier = Modifier.fillMaxWidth()) {
                            Text("Synchroniser maintenant")
                        }
                        if (SyncManager.webUrl.isNotBlank()) {
                            Text("Version PC : ${SyncManager.webUrl}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, SyncManager.webUrl)
                                }.let { Intent.createChooser(it, "Envoyer le lien de la version PC") }) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Envoyer le lien vers mon PC") }
                        }
                        TextButton(onClick = { SyncManager.signOut() }) { Text("Se déconnecter") }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
