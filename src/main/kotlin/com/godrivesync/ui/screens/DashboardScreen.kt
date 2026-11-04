package com.godrivesync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godrivesync.ui.components.AppIcons
import com.godrivesync.ui.components.AppPanel
import com.godrivesync.ui.components.Eyebrow
import com.godrivesync.ui.navigation.AppDestination

@Composable
fun DashboardScreen(onNavigate: (AppDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Contas conectadas", "0", AppIcons.Accounts, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            Metric("Pastas mapeadas", "0", AppIcons.Folder, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            Metric("Última sincronização", "—", AppIcons.History, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        }
        AppPanel(Modifier.fillMaxWidth(), inset = true, padding = PaddingValues(20.dp)) {
            Eyebrow("VAMOS COMEÇAR")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Tudo começa com a sua conta.", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "Reúna suas contas Google e escolha quais pastas quer manter por perto.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onNavigate(AppDestination.ACCOUNTS) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text("Ver contas")
                        Icon(AppIcons.ArrowRight, null, Modifier.padding(start = 12.dp).size(18.dp))
                    }
                }
                Box(
                    Modifier.size(96.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                        RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Cloud, null, Modifier.size(50.dp), tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppPanel(Modifier.weight(1f), inset = true, padding = PaddingValues(16.dp)) {
                Icon(AppIcons.Folder, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text("Cada pasta no seu lugar", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Os mapeamentos conectam uma pasta do Drive a uma pasta no computador.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onNavigate(AppDestination.MAPPINGS) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Explorar mapeamentos")
                    Icon(AppIcons.ArrowRight, null, Modifier.padding(start = 8.dp).size(16.dp))
                }
            }
            AppPanel(Modifier.weight(1f), inset = true, padding = PaddingValues(16.dp)) {
                Icon(AppIcons.History, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Atividade recente", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nenhuma transferência por enquanto. Suas próximas operações aparecerão aqui.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onNavigate(AppDestination.HISTORY) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Ver histórico")
                    Icon(AppIcons.ArrowRight, null, Modifier.padding(start = 8.dp).size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier) {
    AppPanel(modifier, inset = true, padding = PaddingValues(14.dp), spacing = 8.dp) {
        Icon(icon, null, Modifier.size(19.dp), tint = tint)
        Text(value, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
