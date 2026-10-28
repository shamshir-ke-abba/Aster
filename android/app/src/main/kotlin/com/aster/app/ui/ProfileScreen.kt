package com.aster.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.data.network.ConnectionState
import com.aster.app.ui.components.AsterAvatar
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterCard
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm

@Composable
fun ProfileScreen(
    onNavigate: (AsterNavDestination) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    var editDisplayName by remember(uiState.displayName) { mutableStateOf(uiState.displayName) }

    LaunchedEffect(uiState.feedbackMessage) {
        uiState.feedbackMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearFeedback()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { err ->
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            viewModel.clearFeedback()
        }
    }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Your Profile",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = "Permanent Aster identity & presence",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = AsterGlow.copy(alpha = 0.7f)
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (statusColor, statusText) = when (uiState.connectionState) {
                        ConnectionState.CONNECTED -> Pair(AsterOnline, "Connected")
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Pair(AsterWarm, "Reconnecting…")
                        ConnectionState.DISCONNECTED -> Pair(AsterCrimson, "Offline")
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AsterSurface)
                            .border(0.75.dp, AsterBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (uiState.isLoading) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = AsterViolet,
                            strokeWidth = 2.dp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AsterSurface)
                            .border(0.75.dp, AsterBorder, RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToSettings)
                            .semantics { contentDescription = "Open Settings" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "⚙", color = AsterStar, fontSize = 16.sp)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                // Aster ID Identity Card matching design reference
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(AsterSurface)
                        .border(0.75.dp, AsterBorder, RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AsterAvatar(
                            name = uiState.displayName.ifBlank { "User" },
                            size = 80.dp,
                            isOnline = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = uiState.displayName.ifBlank { "Beta Explorer" },
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = AsterStar,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "@${uiState.username.ifBlank { "anonymous" }}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = AsterMuted
                            )
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Permanent Aster ID display
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(AsterNight.copy(alpha = 0.8f))
                                .border(0.75.dp, AsterBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = uiState.asterId.ifBlank { "AST-PENDING" },
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    color = AsterViolet,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Actions: Copy ID & Share ID
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Aster ID", uiState.asterId)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Aster ID copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                            ) {
                                Text(text = "📋 Copy ID", fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Connect with me on Aster: ${uiState.asterId}")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Aster ID"))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight)
                            ) {
                                Text(text = "↗ Share ID", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Edit Display Name Section
                Text(
                    text = "PROFILE SETTINGS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                AsterCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Display Name",
                            style = MaterialTheme.typography.bodySmall.copy(color = AsterMuted)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BasicTextField(
                            value = editDisplayName,
                            onValueChange = { editDisplayName = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = AsterStar, fontSize = 15.sp),
                            cursorBrush = SolidColor(AsterViolet),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AsterElevated)
                                        .border(0.75.dp, AsterBorder, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.updateDisplayName(editDisplayName) },
                            enabled = editDisplayName.isNotBlank() && editDisplayName != uiState.displayName && !uiState.isSaving,
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AsterViolet,
                                contentColor = AsterNight,
                                disabledContainerColor = AsterElevated,
                                disabledContentColor = AsterMuted
                            )
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AsterNight, strokeWidth = 2.dp)
                            } else {
                                Text(text = "Save Changes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Account Information
                Text(
                    text = "ACCOUNT IDENTITY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                AsterCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Username", color = AsterMuted, fontSize = 14.sp)
                            Text(text = "@${uiState.username}", color = AsterStar, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Network Tier", color = AsterMuted, fontSize = 14.sp)
                            Text(text = "Sovereign Beta", color = AsterViolet, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Identity Model", color = AsterMuted, fontSize = 14.sp)
                            Text(text = "Permanent Aster ID", color = AsterStar, fontSize = 14.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Client Version", color = AsterMuted, fontSize = 14.sp)
                            Text(text = "v1.0 (Aster Sovereign)", color = AsterStar, fontSize = 14.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Network Transport", color = AsterMuted, fontSize = 14.sp)
                            Text(text = "Aster Secure Network", color = AsterViolet, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // System & Preferences Section
                Text(
                    text = "PREFERENCES & SYSTEM",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                AsterCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNavigateToSettings
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .semantics { contentDescription = "Navigate to App Settings" },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(AsterSurfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "⚙", color = AsterViolet, fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "App Settings & Preferences",
                                    color = AsterStar,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Appearance, notification policy, diagnostics & account",
                                    color = AsterMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Text(
                            text = "→",
                            color = AsterMuted,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Bottom Navigation
            AsterBottomNavigation(
                currentDestination = AsterNavDestination.PROFILE,
                onNavigate = onNavigate
            )
        }
    }
}
