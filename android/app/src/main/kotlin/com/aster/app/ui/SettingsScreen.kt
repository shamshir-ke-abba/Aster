package com.aster.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.data.network.ConnectionState
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterCard
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.motion.AsterHapticEvent
import com.aster.app.ui.motion.LocalAsterHaptics
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
    var showLicensesDialog by remember { mutableStateOf(false) }

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

                // Section: About Aster & Credits
                Text(
                    text = "ABOUT ASTER",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                AboutAndCreditsCard()

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Contact Creator
                Text(
                    text = "CONTACT CREATOR",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                ContactCreatorCard()

                Spacer(modifier = Modifier.height(20.dp))

                // Section: Legal & Open Source
                Text(
                    text = "LEGAL & LICENSES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                LegalAndLicensesCard(onOpenLicenses = { showLicensesDialog = true })

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
                        text = "Aster v1.0 • © 2026 Saa'ad Khan",
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

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showLicensesDialog = false })
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
private fun AboutAndCreditsCard() {
    val versionString = getAppVersionString()
    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AsterSurfaceElevated)
                        .border(0.75.dp, AsterBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✦", color = AsterViolet, fontSize = 18.sp)
                }
                Column {
                    Text(text = "Aster", color = AsterStar, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(text = versionString, color = AsterMuted, fontSize = 12.sp)
                }
            }

            Text(
                text = "Local-first celestial messaging engineered for sovereign, high-resilience communication.",
                color = AsterStar.copy(alpha = 0.9f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(AsterBorder)
            )

            Text(
                text = "CREDITS",
                color = AsterViolet,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Creator", color = AsterMuted, fontSize = 13.sp)
                Text(text = "Saaaaad", color = AsterStar, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Coder", color = AsterMuted, fontSize = 13.sp)
                Text(text = "Saaaaad", color = AsterStar, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Designer", color = AsterMuted, fontSize = 13.sp)
                Text(text = "Saaaaad", color = AsterStar, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ContactCreatorCard() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAsterHaptics.current

    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ContactRow(
                icon = "✉",
                title = "Email",
                subtitle = "kmsaadbinafzal@gmail.com",
                onClick = {
                    haptics.perform(AsterHapticEvent.Press)
                    openEmail(context, "kmsaadbinafzal@gmail.com", "Aster V1.0 — Contact")
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(AsterBorder)
            )

            ContactRow(
                icon = "⌨",
                title = "GitHub",
                subtitle = "shamshir-ke-abba",
                onClick = {
                    haptics.perform(AsterHapticEvent.Press)
                    openUrl(context, "https://github.com/shamshir-ke-abba/Aster")
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(AsterBorder)
            )

            ContactRow(
                icon = "🌐",
                title = "Portfolio",
                subtitle = "saad-portfolio.kmsaadbinafzal.workers.dev",
                onClick = {
                    haptics.perform(AsterHapticEvent.Press)
                    openUrl(context, "https://saad-portfolio.kmsaadbinafzal.workers.dev/")
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(AsterBorder)
            )

            ContactRow(
                icon = "📸",
                title = "Instagram",
                subtitle = "@0.omni_man.0",
                onClick = {
                    haptics.perform(AsterHapticEvent.Press)
                    openUrl(context, "https://www.instagram.com/0.omni_man.0/")
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(AsterBorder)
            )

            ContactRow(
                icon = "💬",
                title = "Discord",
                subtitle = "shamshir_ke_abba (Tap to copy)",
                onClick = {
                    haptics.perform(AsterHapticEvent.Success)
                    clipboardManager.setText(AnnotatedString("shamshir_ke_abba"))
                    Toast.makeText(context, "Discord username copied: shamshir_ke_abba", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun ContactRow(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AsterSurfaceElevated)
                .border(0.75.dp, AsterBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, fontSize = 15.sp, color = AsterViolet)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = AsterStar, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = AsterMuted, fontSize = 12.sp)
        }

        Text(text = "✦ ›", color = AsterViolet, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LegalAndLicensesCard(
    onOpenLicenses: () -> Unit
) {
    AsterCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLicenses)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Third-Party & Open Source Licenses", color = AsterStar, fontSize = 13.sp)
                Text(
                    text = "✦ View ›",
                    color = AsterViolet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(AsterBorder)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Copyright", color = AsterMuted, fontSize = 13.sp)
                Text(
                    text = "© 2026 Saa'ad Khan",
                    color = AsterStar,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "All rights reserved. Engineered for personal sovereignty and communication privacy.",
                color = AsterMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "✦", color = AsterViolet, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Open Source Notices", color = AsterStar, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Aster incorporates the following open-source libraries under the Apache License, Version 2.0. We gratefully acknowledge the authors and open-source community:",
                        color = AsterStar.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    LicenseComponentEntry(
                        name = "AndroidX & Jetpack Compose",
                        copyright = "Copyright © 2018–2026 The Android Open Source Project",
                        license = "Apache License 2.0"
                    )

                    LicenseComponentEntry(
                        name = "Kotlin & Kotlinx Coroutines",
                        copyright = "Copyright © 2010–2026 JetBrains s.r.o. and Kotlin contributors",
                        license = "Apache License 2.0"
                    )

                    LicenseComponentEntry(
                        name = "Dagger & Hilt",
                        copyright = "Copyright © 2012–2026 The Dagger Authors / Google LLC",
                        license = "Apache License 2.0"
                    )

                    LicenseComponentEntry(
                        name = "Room Persistence Library",
                        copyright = "Copyright © 2017–2026 The Android Open Source Project",
                        license = "Apache License 2.0"
                    )

                    LicenseComponentEntry(
                        name = "OkHttp",
                        copyright = "Copyright © 2014–2026 Square, Inc.",
                        license = "Apache License 2.0"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(AsterBorder)
                    )

                    Text(
                        text = "Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/\n\n" +
                                "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\n\n" +
                                "1. Definitions.\n\"License\" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.\n\n" +
                                "2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.\n\n" +
                                "3. Grant of Patent License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work.\n\n" +
                                "4. Redistribution. You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, and in Source or Object form, provided that You meet the following conditions:\n" +
                                "(a) You must give any other recipients of the Work or Derivative Works a copy of this License; and\n" +
                                "(b) You must cause any modified files to carry prominent notices stating that You changed the files; and\n" +
                                "(c) You must retain, in the Source form of any Derivative Works that You distribute, all copyright, patent, trademark, and attribution notices from the Source form of the Work; and\n" +
                                "(d) If the Work includes a \"NOTICE\" text file, You must include a readable copy of the attribution notices contained within such NOTICE file.\n\n" +
                                "Disclaimer of Warranty. Unless required by applicable law or agreed to in writing, Licensor provides the Work (and each Contributor provides its Contributions) on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied, including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.\n\n" +
                                "Limitation of Liability. In no event and under no legal theory shall any Contributor be liable to You for damages, including any direct, indirect, special, incidental, or consequential damages of any character arising as a result of this License.",
                        color = AsterMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
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

@Composable
private fun LicenseComponentEntry(
    name: String,
    copyright: String,
    license: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = name, color = AsterStar, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(text = copyright, color = AsterMuted, fontSize = 11.sp)
        Text(text = "License: $license", color = AsterViolet, fontSize = 11.sp)
    }
}

private fun getAppVersionString(): String {
    val versionName = try {
        com.aster.app.BuildConfig.VERSION_NAME
    } catch (_: Throwable) {
        "1.0"
    }
    val versionCode = try {
        com.aster.app.BuildConfig.VERSION_CODE
    } catch (_: Throwable) {
        1
    }
    return "Aster $versionName (Build $versionCode)"
}

private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Aster Link", url)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Link copied to clipboard: $url", Toast.LENGTH_SHORT).show()
    }
}

private fun openEmail(context: Context, email: String, subject: String) {
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Aster Creator Email", email)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Email copied to clipboard: $email", Toast.LENGTH_SHORT).show()
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
