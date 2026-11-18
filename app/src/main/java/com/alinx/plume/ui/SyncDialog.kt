package com.alinx.plume.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alinx.plume.sync.FoundPc
import com.alinx.plume.sync.SyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bouton nuage de la barre du haut : état de la synchronisation avec le PC. */
@Composable
fun SyncButton() {
    val status by SyncManager.status.collectAsState()
    var open by remember { mutableStateOf(false) }
    val icon = when {
        status.pcName == null -> Icons.Filled.Cloud
        status.running -> Icons.Filled.CloudSync
        status.unreachable || status.error != null -> Icons.Filled.CloudOff
        else -> Icons.Filled.CloudDone
    }
    IconButton(onClick = { open = true }) {
        Icon(
            icon,
            contentDescription = "Synchronisation avec le PC",
            tint = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) SyncDialog(onDismiss = { open = false })
}

@Composable
fun SyncDialog(onDismiss: () -> Unit) {
    val status by SyncManager.status.collectAsState()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundPc>>(emptyList()) }
    var chosen by remember { mutableStateOf<FoundPc?>(null) }
    var manual by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search() {
        searching = true
        message = null
        scope.launch {
            found = SyncManager.discover()
            searching = false
            searched = true
            if (found.size == 1) chosen = found.first()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (status.pcName == null) "Associer mon PC" else "Synchronisation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val pc = chosen
                when {
                    status.pcName != null -> {
                        Text("Associé à : ${status.pcName}", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                status.running -> "Synchronisation en cours…"
                                status.error != null -> status.error!!
                                status.unreachable -> "PC introuvable pour l'instant. La synchronisation reprendra quand le téléphone et le PC seront sur le même Wi-Fi, avec Plume ouvert sur le PC."
                                status.lastSync > 0 -> "Dernière synchronisation : " +
                                    SimpleDateFormat("d MMM 'à' HH:mm", Locale.FRANCE).format(Date(status.lastSync))
                                else -> "Pas encore synchronisé."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { SyncManager.requestSync(0) }, enabled = !status.running, modifier = Modifier.fillMaxWidth()) {
                            Text("Synchroniser maintenant")
                        }
                        TextButton(onClick = { SyncManager.unpair() }) { Text("Dissocier ce PC") }
                    }
                    pc != null -> {
                        Text("Sur le PC « ${pc.name} », cliquez sur « Associer mon téléphone », puis tapez ici le code affiché.")
                        OutlinedTextField(
                            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Code à 6 chiffres") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                busy = true
                                message = null
                                scope.launch { message = SyncManager.pair(pc, code); busy = false }
                            },
                            enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth(),
                        ) { Text("Associer") }
                        TextButton(onClick = { chosen = null; code = "" }) { Text("Choisir un autre PC") }
                    }
                    else -> {
                        Text("Vos notes passeront directement du téléphone au PC sur votre Wi-Fi, sans Internet. Ouvrez Plume sur le PC, puis lancez la recherche.")
                        if (searching) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Recherche sur le Wi-Fi…")
                            }
                        } else {
                            for (f in found) {
                                OutlinedButton(onClick = { chosen = f }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Filled.Computer, contentDescription = null)
                                    Text("  ${f.name}")
                                }
                            }
                            if (searched && found.isEmpty()) {
                                Text(
                                    "Aucun PC trouvé. Vérifiez que Plume est ouvert sur le PC et que les deux sont sur le même Wi-Fi. Vous pouvez aussi taper l'adresse affichée sur le PC.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(onClick = { search() }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (searched) "Rechercher à nouveau" else "Rechercher mon PC")
                            }
                            if (searched) {
                                OutlinedTextField(
                                    value = manual, onValueChange = { manual = it },
                                    label = { Text("Adresse du PC (ex. 192.168.1.20)") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedButton(
                                    onClick = {
                                        busy = true
                                        message = null
                                        scope.launch {
                                            val p = SyncManager.probe(manual)
                                            busy = false
                                            if (p == null) message = "Pas de Plume à cette adresse." else chosen = p
                                        }
                                    },
                                    enabled = !busy && manual.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                                ) { Text("Utiliser cette adresse") }
                            }
                        }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
