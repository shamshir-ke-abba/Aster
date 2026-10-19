package com.aster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.data.network.ConnectionState
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet

@Composable
fun SettingsScreen(
    onNavigate: (AsterNavDestination) -> Unit,
    onLogoutSuccess: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AsterNight)
    ) {
        ConstellationBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = "Preferences, privacy & network diagnostics",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = AsterGlow.copy(alpha = 0.7f)
                        )
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                // Section: Appearance & View Mode
                Text(
                    text = "APPEARANCE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AsterSurface)
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "Chat Navigation Mode",
                            color = AsterStar,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Choose between the interactive 2D constellation canvas or a traditional list.",
                            color = AsterGlow.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AsterNight)
                                .padding(4.dp)
                        ) {
                            val isConstellation = uiState.viewMode == "CONSTELLATION"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isConstellation) AsterElevated else Color.Transparent)
                                    .clickable { viewModel.setViewMode("CONSTELLATION") }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✦ Constellation",
                                    color = if (isConstellation) AsterViolet else AsterGlow.copy(alpha = 0.6f),
                                    fontWeight = if (isConstellation) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (!isConstellation) AsterElevated else Color.Transparent)
                                    .clickable { viewModel.setViewMode("LIST") }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "☰ List",
                                    color = if (!isConstellation) AsterStar else AsterGlow.copy(alpha = 0.6f),
                                    fontWeight = if (!isConstellation) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Privacy & Notifications
                Text(
                    text = "PREFERENCES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AsterSurface)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Read Receipts", color = AsterStar, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(text = "Share delivery acknowledgements with contacts", color = AsterGlow.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                            Switch(
                                checked = uiState.readReceiptsEnabled,
                                onCheckedChange = viewModel::toggleReadReceipts,
                                colors = SwitchDefaults.colors(checkedThumbColor = AsterNight, checkedTrackColor = AsterViolet)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Notifications", color = AsterStar, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(text = "Play subtle chime and vibrate for incoming messages", color = AsterGlow.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                            Switch(
                                checked = uiState.notificationsEnabled,
                                onCheckedChange = viewModel::toggleNotifications,
                                colors = SwitchDefaults.colors(checkedThumbColor = AsterNight, checkedTrackColor = AsterViolet)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Diagnostics & Network
                Text(
                    text = "NETWORK DIAGNOSTICS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AsterSurface)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Transport State", color = AsterGlow.copy(alpha = 0.7f), fontSize = 13.sp)
                            val statusColor = when (uiState.connectionState) {
                                ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFC107)
                                ConnectionState.DISCONNECTED -> Color(0xFFFF5252)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = uiState.connectionState.name,
                                    color = statusColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Server Endpoint", color = AsterGlow.copy(alpha = 0.7f), fontSize = 13.sp)
                            Text(
                                text = uiState.serverUrl.take(28) + if (uiState.serverUrl.length > 28) "…" else "",
                                color = AsterStar,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = viewModel::reconnect,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Reconnect Socket", fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Account Actions
                Text(
                    text = "ACCOUNT ACTIONS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AsterSurface)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.logout(onLogoutSuccess) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Log Out", fontSize = 13.sp)
                        }

                        Button(
                            onClick = { showDeleteDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A151D), contentColor = Color(0xFFFF6B6B)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Delete Account & Data", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Bottom Navigation
            AsterBottomNavigation(
                currentDestination = AsterNavDestination.SETTINGS,
                onNavigate = onNavigate
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "Delete Account?", color = AsterStar) },
            text = {
                Text(
                    text = "This will deactivate your Aster ID and revoke all session credentials permanently.",
                    color = AsterGlow.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteAccount(onLogoutSuccess)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = AsterNight)
                ) {
                    Text(text = "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDeleteDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                ) {
                    Text(text = "Cancel")
                }
            },
            containerColor = AsterSurface
        )
    }
}
