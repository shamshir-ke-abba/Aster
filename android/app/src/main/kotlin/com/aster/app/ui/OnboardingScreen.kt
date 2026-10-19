package com.aster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet
import kotlinx.coroutines.flow.StateFlow

@Composable
fun OnboardingScreen(
    onAuthenticationSuccess: (String) -> Unit,
    pendingInviteToken: StateFlow<String?>? = null,
    pendingInviteError: StateFlow<String?>? = null,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val tokenInput by viewModel.tokenInput.collectAsState()
    val usernameInput by viewModel.usernameInput.collectAsState()
    val displayNameInput by viewModel.displayNameInput.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Observe external deep link tokens or extras
    val externalToken by pendingInviteToken?.collectAsState() ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) }
    val externalError by pendingInviteError?.collectAsState() ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) }

    LaunchedEffect(externalToken) {
        externalToken?.let { token ->
            if (token.isNotBlank()) {
                viewModel.selectTab(OnboardingTab.INVITE_TOKEN)
                viewModel.onTokenInputChanged(token)
                viewModel.submitToken(token)
            }
        }
    }

    LaunchedEffect(externalError) {
        externalError?.let { err ->
            viewModel.setLinkError(err)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is OnboardingUiState.Success) {
            onAuthenticationSuccess((uiState as OnboardingUiState.Success).userId)
        }
    }

    Scaffold(
        containerColor = AsterNight,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Atmospheric background constellation
            ConstellationBackground(
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 24.dp)
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Constellation Star Icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(AsterElevated)
                        .border(1.dp, AsterViolet.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✦",
                        color = AsterViolet,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "A S T E R",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = AsterStar,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Private Constellation Messaging",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = AsterGlow.copy(alpha = 0.7f)
                    )
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AsterSurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (currentTab == OnboardingTab.REGISTER) AsterElevated else Color.Transparent)
                            .clickable { viewModel.selectTab(OnboardingTab.REGISTER) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Create Account",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (currentTab == OnboardingTab.REGISTER) AsterStar else AsterGlow.copy(alpha = 0.6f),
                                fontWeight = if (currentTab == OnboardingTab.REGISTER) FontWeight.SemiBold else FontWeight.Normal
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (currentTab == OnboardingTab.INVITE_TOKEN) AsterElevated else Color.Transparent)
                            .clickable { viewModel.selectTab(OnboardingTab.INVITE_TOKEN) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Invite Token",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (currentTab == OnboardingTab.INVITE_TOKEN) AsterStar else AsterGlow.copy(alpha = 0.6f),
                                fontWeight = if (currentTab == OnboardingTab.INVITE_TOKEN) FontWeight.SemiBold else FontWeight.Normal
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (currentTab == OnboardingTab.REGISTER) {
                    // Registration Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AsterSurface)
                            .border(1.dp, AsterElevated, RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Column {
                            Text(
                                text = "Join the Constellation",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = AsterStar,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Choose a unique username and display name. You will be assigned a permanent, collision-resistant Aster ID (AST-XXXXXX).",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = AsterGlow.copy(alpha = 0.8f),
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Username Input
                    BasicTextField(
                        value = usernameInput,
                        onValueChange = { viewModel.onUsernameInputChanged(it) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AsterStar,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(AsterViolet),
                        singleLine = true,
                        enabled = uiState !is OnboardingUiState.Loading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AsterElevated)
                                    .border(1.dp, AsterViolet.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                if (usernameInput.isEmpty()) {
                                    Text(
                                        text = "Username (e.g. maya_lin)",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = AsterGlow.copy(alpha = 0.35f),
                                            fontSize = 14.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Display Name Input
                    BasicTextField(
                        value = displayNameInput,
                        onValueChange = { viewModel.onDisplayNameInputChanged(it) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AsterStar,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(AsterViolet),
                        singleLine = true,
                        enabled = uiState !is OnboardingUiState.Loading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            viewModel.submitRegistration()
                        }),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AsterElevated)
                                    .border(1.dp, AsterViolet.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                if (displayNameInput.isEmpty()) {
                                    Text(
                                        text = "Display Name (e.g. Maya Lin)",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = AsterGlow.copy(alpha = 0.35f),
                                            fontSize = 14.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                } else {
                    // Explanatory card for Invite Token
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AsterSurface)
                            .border(1.dp, AsterElevated, RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Column {
                            Text(
                                text = "Invitation Required",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = AsterStar,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Aster is a private, self-hosted messaging network. To join with an existing invite token, enter or paste it below.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = AsterGlow.copy(alpha = 0.8f),
                                    lineHeight = 19.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Token Input Field
                    BasicTextField(
                        value = tokenInput,
                        onValueChange = { viewModel.onTokenInputChanged(it) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AsterStar,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(AsterViolet),
                        singleLine = true,
                        enabled = uiState !is OnboardingUiState.Loading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            if (tokenInput.isNotBlank()) {
                                viewModel.submitToken()
                            }
                        }),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AsterElevated)
                                    .border(
                                        1.dp,
                                        if (uiState is OnboardingUiState.Error) Color(0xFFFF6B6B).copy(alpha = 0.8f)
                                        else AsterViolet.copy(alpha = 0.3f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                if (tokenInput.isEmpty()) {
                                    Text(
                                        text = "Paste invitation token (ast_tok_...)",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = AsterGlow.copy(alpha = 0.35f),
                                            fontSize = 14.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                // Error State Display
                if (uiState is OnboardingUiState.Error) {
                    val error = uiState as OnboardingUiState.Error
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A151D))
                            .border(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFFF8E8E),
                                lineHeight = 18.sp
                            ),
                            textAlign = TextAlign.Start
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Button / Loading State
                Button(
                    onClick = {
                        keyboardController?.hide()
                        if (currentTab == OnboardingTab.REGISTER) {
                            viewModel.submitRegistration()
                        } else {
                            viewModel.submitToken()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = uiState !is OnboardingUiState.Loading &&
                            ((currentTab == OnboardingTab.REGISTER && usernameInput.isNotBlank() && displayNameInput.isNotBlank()) ||
                             (currentTab == OnboardingTab.INVITE_TOKEN && tokenInput.isNotBlank())),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AsterViolet,
                        contentColor = AsterNight,
                        disabledContainerColor = AsterElevated,
                        disabledContentColor = AsterGlow.copy(alpha = 0.3f)
                    )
                ) {
                    if (uiState is OnboardingUiState.Loading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = AsterViolet,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Contacting Aster...",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = AsterStar,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    } else {
                        Text(
                            text = if (currentTab == OnboardingTab.REGISTER) "Create Account ✦" else "Enter Constellation ✦",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
