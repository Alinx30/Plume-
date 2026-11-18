package com.alinx.plume.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.data.PaperColors
import com.alinx.plume.data.PaperSpec
import com.alinx.plume.data.PaperStyle
import com.alinx.plume.render.PageRenderer

/** Choix du modèle de feuille et de sa couleur, avec aperçus. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperPickerDialog(
    initial: PaperSpec,
    onConfirm: (PaperSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    var style by remember { mutableStateOf(initial.style) }
    var color by remember { mutableLongStateOf(initial.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Papier") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("Couleur", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PaperColors.forEach { (c, _) ->
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(
                                    if (c == color) 3.dp else 1.dp,
                                    if (c == color) MaterialTheme.colorScheme.primary else Color(0x33000000),
                                    CircleShape,
                                )
                                .padding(4.dp)
                                .clip(CircleShape)
                                .border(1.dp, Color(0x22000000), CircleShape)
                                .clickable { color = c }
                                .padding(1.dp),
                        ) {
                            Canvas(Modifier.fillMaxSize()) { drawCircle(Color(c)) }
                        }
                    }
                }
                Text("Modèle", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PaperStyle.entries.forEach { st ->
                        Column(
                            Modifier.width(76.dp).clickable { style = st },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val selected = st == style
                            Canvas(
                                Modifier
                                    .width(68.dp)
                                    .aspectRatio(1f / 1.414f)
                                    .shadow(2.dp, RoundedCornerShape(4.dp))
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(
                                        if (selected) 3.dp else 0.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        RoundedCornerShape(4.dp),
                                    )
                            ) {
                                val s = size.width / PAGE_W
                                drawIntoCanvas { cv ->
                                    val nc = cv.nativeCanvas
                                    nc.save()
                                    nc.scale(s, s)
                                    PageRenderer.drawPaper(nc, PaperSpec(st, color))
                                    nc.restore()
                                }
                            }
                            Text(
                                st.label,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(PaperSpec(style, color)) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
