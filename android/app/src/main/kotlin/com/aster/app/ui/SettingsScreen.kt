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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.data.network.ConnectionState
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterCard
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm

@Composable
fun SettingsScreen(
    onNavigate: (AsterNavDestination) -> Unit,
    onBackClick: () -> Unit = {},
    onLogoutSuccess: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

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
            // Header with back navigation
            SettingsHeader(onBackClick = onBackClick)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // Section: Appearance & Chat Mode
                Text(
                    text = "APPEARANCE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppearanceCard(
                    viewMode = uiState.viewMode,
                    onSelectMode = viewModel::setViewMode
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Privacy & Notifications
                Text(
                    text = "PREFERENCES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                PreferencesCard(
                    readReceiptsEnabled = uiState.readReceiptsEnabled,
                    notificationsEnabled = uiState.notificationsEnabled,
                    onToggleReadReceipts = viewModel::toggleReadReceipts,
                    onToggleNotifications = viewModel::toggleNotifications
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Network Status
                Text(
                    text = "NETWORK STATUS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                DiagnosticsCard(
                    connectionState = uiState.connectionState,
                    onReconnect = viewModel::reconnect
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Section: About & System
                Text(
                    text = "ABOUT & SYSTEM",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                AboutCard(onOpenAbout = { showAboutDialog = true })

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Account Actions
                Text(
                    text = "ACCOUNT ACTIONS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                AccountActionsCard(
                    onLogout = { viewModel.logout(onLogoutSuccess) },
                    onDeleteAccount = { showDeleteDialog = true }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aster v1.0 • Constellation Engine",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = AsterMuted.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    )
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
        DeleteAccountDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirmDelete = {
                showDeleteDialog = false
                viewModel.deleteAccount(onLogoutSuccess)
            }
        )
    }

    if (uiState.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearErrorMessage() },
            title = { Text(text = "Account Deletion Failed", color = AsterStar) },
            text = { Text(text = uiState.errorMessage ?: "Failed to delete account. Please try again.", color = AsterMuted) },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearErrorMessage() },
                    colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                ) {
                    Text(text = "OK")
                }
            },
            containerColor = AsterSurface
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

@Composable
private fun SettingsHeader(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    onClick = onBackClick,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                )
                .semantics { contentDescription = "Back to Profile" },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "←",
                color = AsterStar,
                fontSize = 22.sp
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

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
                    color = AsterMuted
                )
            )
        }
    }
}

@Composable
private fun AppearanceCard(
    viewMode: String,
    onSelectMode: (String) -> Unit
) {
    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Chat mode",
                color = AsterStar,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose between the interactive 2D constellation canvas or a traditional list.",
                color = AsterMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AsterNight)
                    .border(0.75.dp, AsterBorder, RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                val isConstellation = viewMode == "CONSTELLATION"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isConstellation) AsterSurfaceElevated else Color.Transparent)
                        .border(
                            if (isConstellation) 1.dp else 0.dp,
                            if (isConstellation) AsterViolet else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectMode("CONSTELLATION") }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✦ Constellation",
                        color = if (isConstellation) AsterStar else AsterMuted,
                        fontWeight = if (isConstellation) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isConstellation) AsterSurfaceElevated else Color.Transparent)
                        .border(
                            if (!isConstellation) 1.dp else 0.dp,
                            if (!isConstellation) AsterViolet else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectMode("LIST") }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "☰ List",
                        color = if (!isConstellation) AsterStar else AsterMuted,
                        fontWeight = if (!isConstellation) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PreferencesCard(
    readReceiptsEnabled: Boolean,
    notificationsEnabled: Boolean,
    onToggleReadReceipts: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit
) {
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = AsterStar,
        checkedTrackColor = AsterViolet,
        uncheckedThumbColor = AsterMuted,
        uncheckedTrackColor = AsterElevated,
        uncheckedBorderColor = AsterBorder
    )

    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Read Receipts",
                        color = AsterStar,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Share delivery acknowledgements with contacts",
                        color = AsterMuted,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = readReceiptsEnabled,
                    onCheckedChange = onToggleReadReceipts,
                    colors = switchColors
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notifications",
                        color = AsterStar,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Notify for incoming messages across app states",
                        color = AsterMuted,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = onToggleNotifications,
                    colors = switchColors
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    connectionState: ConnectionState,
    onReconnect: () -> Unit
) {
    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Connection Status", color = AsterMuted, fontSize = 13.sp)
                val (statusColor, statusLabel) = when (connectionState) {
                    ConnectionState.CONNECTED -> Pair(AsterOnline, "Connected")
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Pair(AsterWarm, "Reconnecting…")
                    ConnectionState.DISCONNECTED -> Pair(AsterCrimson, "Offline")
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
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Security", color = AsterMuted, fontSize = 13.sp)
                Text(
                    text = "Aster Sovereign Network",
                    color = AsterStar,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onReconnect,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AsterSurfaceElevated,
                    contentColor = AsterStar
                ),
                border = androidx.compose.foundation.BorderStroke(0.75.dp, AsterBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                val btnText = when (connectionState) {
                    ConnectionState.CONNECTED -> "Refresh Connection"
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> "Connecting…"
                    ConnectionState.DISCONNECTED -> "Reconnect Now"
                }
                Text(text = btnText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun AboutCard(onOpenAbout: () -> Unit) {
    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAbout() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "About Aster", color = AsterStar, fontSize = 14.sp)
                Text(
                    text = "✦ Details ›",
                    color = AsterViolet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Version", color = AsterMuted, fontSize = 13.sp)
                Text(text = "v1.0 (Aster Sovereign)", color = AsterStar, fontSize = 13.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Architecture", color = AsterMuted, fontSize = 13.sp)
                Text(text = "Aster Sovereign Architecture", color = AsterViolet, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun AccountActionsCard(
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onLogout,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AsterSurfaceElevated,
                    contentColor = AsterStar
                ),
                border = androidx.compose.foundation.BorderStroke(0.75.dp, AsterBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Log Out", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Button(
                onClick = onDeleteAccount,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF261014),
                    contentColor = AsterCrimson
                ),
                border = androidx.compose.foundation.BorderStroke(0.75.dp, AsterCrimson.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Delete Account & Data", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DeleteAccountDialog(
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Delete Account?", color = AsterStar, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = "Permanently delete your Aster account? All your active sessions will be revoked and your identity will become 'Deleted User'. This action is irreversible.",
                color = AsterMuted,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(containerColor = AsterCrimson, contentColor = AsterNight)
            ) {
                Text(text = "Permanently Delete", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
            ) {
                Text(text = "Cancel")
            }
        },
        containerColor = AsterSurface
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "✦", color = AsterViolet, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "About Aster", color = AsterStar, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Local-first celestial messaging engineered for sovereign, high-resilience communication.",
                    color = AsterStar,
                    fontSize = 14.sp
                )
                Text(
                    text = "• Version: 1.0 (Aster Sovereign)\n• Network: Aster Secure Network (TLS 1.3)\n• Architecture: Sovereign Local-First Messaging\n• Local Storage: Room SQLite with zero-knowledge session reset\n• Visuals: Constellation 2D Graph Engine",
                    color = AsterMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight)
            ) {
                Text(text = "Close", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = AsterSurface
    )
}
