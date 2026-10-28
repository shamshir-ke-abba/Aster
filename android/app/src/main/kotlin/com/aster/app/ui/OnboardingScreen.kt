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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

@Composable
fun OnboardingScreen(
    onAuthenticationSuccess: (String) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val subFlow by viewModel.subFlow.collectAsState()

    val usernameInput by viewModel.usernameInput.collectAsState()
    val displayNameInput by viewModel.displayNameInput.collectAsState()
    val passwordInput by viewModel.passwordInput.collectAsState()
    val recoveryCodeInput by viewModel.recoveryCodeInput.collectAsState()
    val newPasswordInput by viewModel.newPasswordInput.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current
    var copiedToClipboard by remember { mutableStateOf(false) }

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

                // If currently displaying Recovery Code acknowledgment
                if (uiState is OnboardingUiState.ShowRecoveryCode) {
                    val codeState = uiState as OnboardingUiState.ShowRecoveryCode
                    RecoveryCodeDisplayCard(
                        recoveryCode = codeState.recoveryCode,
                        isReset = codeState.isReset,
                        copied = copiedToClipboard,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(codeState.recoveryCode))
                            copiedToClipboard = true
                        },
                        onAcknowledge = {
                            viewModel.acknowledgeRecoveryCode()
                        }
                    )
                } else if (subFlow == AuthSubFlow.FORGOT_PASSWORD) {
                    // Password Recovery View
                    PasswordRecoveryCard(
                        username = usernameInput,
                        recoveryCode = recoveryCodeInput,
                        newPassword = newPasswordInput,
                        isLoading = uiState is OnboardingUiState.Loading,
                        onUsernameChange = viewModel::onUsernameInputChanged,
                        onRecoveryCodeChange = viewModel::onRecoveryCodeInputChanged,
                        onNewPasswordChange = viewModel::onNewPasswordInputChanged,
                        onSubmit = {
                            keyboardController?.hide()
                            viewModel.submitPasswordRecovery()
                        },
                        onCancel = {
                            viewModel.setSubFlow(AuthSubFlow.NORMAL)
                        }
                    )
                } else if (subFlow == AuthSubFlow.MIGRATE_ACCOUNT) {
                    // Account Migration View
                    AccountMigrationCard(
                        username = usernameInput,
                        newPassword = newPasswordInput,
                        isLoading = uiState is OnboardingUiState.Loading,
                        onNewPasswordChange = viewModel::onNewPasswordInputChanged,
                        onSubmit = {
                            keyboardController?.hide()
                            viewModel.submitMigration()
                        },
                        onCancel = {
                            viewModel.setSubFlow(AuthSubFlow.NORMAL)
                        }
                    )
                } else {
                    // Normal Flow: Create Account vs Sign In Tabs
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
                                .background(if (currentTab == OnboardingTab.CREATE_ACCOUNT) AsterElevated else Color.Transparent)
                                .clickable { viewModel.selectTab(OnboardingTab.CREATE_ACCOUNT) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Create Account",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (currentTab == OnboardingTab.CREATE_ACCOUNT) AsterStar else AsterGlow.copy(alpha = 0.6f),
                                    fontWeight = if (currentTab == OnboardingTab.CREATE_ACCOUNT) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (currentTab == OnboardingTab.SIGN_IN) AsterElevated else Color.Transparent)
                                .clickable { viewModel.selectTab(OnboardingTab.SIGN_IN) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sign In",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (currentTab == OnboardingTab.SIGN_IN) AsterStar else AsterGlow.copy(alpha = 0.6f),
                                    fontWeight = if (currentTab == OnboardingTab.SIGN_IN) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    if (currentTab == OnboardingTab.CREATE_ACCOUNT) {
                        CreateAccountForm(
                            username = usernameInput,
                            displayName = displayNameInput,
                            password = passwordInput,
                            isLoading = uiState is OnboardingUiState.Loading,
                            onUsernameChange = viewModel::onUsernameInputChanged,
                            onDisplayNameChange = viewModel::onDisplayNameInputChanged,
                            onPasswordChange = viewModel::onPasswordInputChanged,
                            onSubmit = {
                                keyboardController?.hide()
                                viewModel.submitCreateAccount()
                            }
                        )
                    } else {
                        SignInForm(
                            username = usernameInput,
                            password = passwordInput,
                            isLoading = uiState is OnboardingUiState.Loading,
                            onUsernameChange = viewModel::onUsernameInputChanged,
                            onPasswordChange = viewModel::onPasswordInputChanged,
                            onSubmit = {
                                keyboardController?.hide()
                                viewModel.submitSignIn()
                            },
                            onForgotPassword = {
                                viewModel.setSubFlow(AuthSubFlow.FORGOT_PASSWORD)
                            }
                        )
                    }
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
            }
        }
    }
}

@Composable
private fun CreateAccountForm(
    username: String,
    displayName: String,
    password: String,
    isLoading: Boolean,
    onUsernameChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
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
                text = "Choose your username and password. You will receive a unique recovery code for account restoration.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = AsterGlow.copy(alpha = 0.8f),
                    lineHeight = 18.sp
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Username Input
    AuthInputField(
        value = username,
        onValueChange = onUsernameChange,
        placeholder = "Username (e.g. maya_lin)",
        enabled = !isLoading,
        imeAction = ImeAction.Next
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Display Name Input (optional)
    AuthInputField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        placeholder = "Display Name (optional)",
        enabled = !isLoading,
        imeAction = ImeAction.Next
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Password Input
    AuthInputField(
        value = password,
        onValueChange = onPasswordChange,
        placeholder = "Password (min 8 characters)",
        enabled = !isLoading,
        isPassword = true,
        imeAction = ImeAction.Done,
        onDone = onSubmit
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = !isLoading && username.isNotBlank() && password.length >= 8,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AsterViolet,
            contentColor = AsterNight,
            disabledContainerColor = AsterElevated,
            disabledContentColor = AsterGlow.copy(alpha = 0.3f)
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AsterNight,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Creating account...")
        } else {
            Text("Create Account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SignInForm(
    username: String,
    password: String,
    isLoading: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit
) {
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
                text = "Welcome Back",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = AsterStar,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Sign in to reconnect to your conversations across the Aster network.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = AsterGlow.copy(alpha = 0.8f),
                    lineHeight = 18.sp
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Username Input
    AuthInputField(
        value = username,
        onValueChange = onUsernameChange,
        placeholder = "Username",
        enabled = !isLoading,
        imeAction = ImeAction.Next
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Password Input
    AuthInputField(
        value = password,
        onValueChange = onPasswordChange,
        placeholder = "Password",
        enabled = !isLoading,
        isPassword = true,
        imeAction = ImeAction.Done,
        onDone = onSubmit
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = "Forgot password?",
            color = AsterViolet,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onForgotPassword)
                .padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }

    Spacer(modifier = Modifier.height(18.dp))

    Button(
        onClick = onSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AsterViolet,
            contentColor = AsterNight,
            disabledContainerColor = AsterElevated,
            disabledContentColor = AsterGlow.copy(alpha = 0.3f)
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AsterNight,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Signing in...")
        } else {
            Text("Sign In", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PasswordRecoveryCard(
    username: String,
    recoveryCode: String,
    newPassword: String,
    isLoading: Boolean,
    onUsernameChange: (String) -> Unit,
    onRecoveryCodeChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
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
                text = "Password Recovery",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = AsterStar,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Enter your username and your secret 16-character recovery code to set a new password.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = AsterGlow.copy(alpha = 0.8f),
                    lineHeight = 18.sp
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    AuthInputField(
        value = username,
        onValueChange = onUsernameChange,
        placeholder = "Username",
        enabled = !isLoading,
        imeAction = ImeAction.Next
    )

    Spacer(modifier = Modifier.height(12.dp))

    AuthInputField(
        value = recoveryCode,
        onValueChange = onRecoveryCodeChange,
        placeholder = "Recovery Code (e.g. XXXX-XXXX-XXXX-XXXX)",
        enabled = !isLoading,
        imeAction = ImeAction.Next
    )

    Spacer(modifier = Modifier.height(12.dp))

    AuthInputField(
        value = newPassword,
        onValueChange = onNewPasswordChange,
        placeholder = "New Password (min 8 characters)",
        enabled = !isLoading,
        isPassword = true,
        imeAction = ImeAction.Done,
        onDone = onSubmit
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = onSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = !isLoading && username.isNotBlank() && recoveryCode.isNotBlank() && newPassword.length >= 8,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AsterViolet,
            contentColor = AsterNight,
            disabledContainerColor = AsterElevated,
            disabledContentColor = AsterGlow.copy(alpha = 0.3f)
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AsterNight,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Resetting password...")
        } else {
            Text("Reset Password", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Back to Sign In",
        color = AsterGlow.copy(alpha = 0.7f),
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(onClick = onCancel)
            .padding(8.dp)
    )
}

@Composable
private fun AccountMigrationCard(
    username: String,
    newPassword: String,
    isLoading: Boolean,
    onNewPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
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
                text = "Account Security Upgrade",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = AsterStar,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Account '$username' was created under an earlier Aster version. Establish a password now to secure your account and generate recovery credentials.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = AsterGlow.copy(alpha = 0.8f),
                    lineHeight = 18.sp
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    AuthInputField(
        value = newPassword,
        onValueChange = onNewPasswordChange,
        placeholder = "Set New Password (min 8 characters)",
        enabled = !isLoading,
        isPassword = true,
        imeAction = ImeAction.Done,
        onDone = onSubmit
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = onSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = !isLoading && newPassword.length >= 8,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AsterViolet,
            contentColor = AsterNight,
            disabledContainerColor = AsterElevated,
            disabledContentColor = AsterGlow.copy(alpha = 0.3f)
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AsterNight,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Migrating...")
        } else {
            Text("Complete Migration", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Cancel",
        color = AsterGlow.copy(alpha = 0.7f),
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(onClick = onCancel)
            .padding(8.dp)
    )
}

@Composable
private fun RecoveryCodeDisplayCard(
    recoveryCode: String,
    isReset: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    onAcknowledge: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AsterSurface)
            .border(1.dp, AsterElevated, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isReset) "Password Reset Successful" else "Save Your Recovery Code",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = AsterStar,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isReset) {
                    "Your password has been updated. Here is your NEW recovery code. Any prior recovery codes are now invalidated."
                } else {
                    "This secret recovery code is the ONLY way to recover your account if you forget your password. Aster never stores plaintext passwords."
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    color = AsterGlow.copy(alpha = 0.85f),
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Highlighted Recovery Code Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF13151F))
                    .border(1.5.dp, AsterViolet, RoundedCornerShape(12.dp))
                    .clickable(onClick = onCopy)
                    .padding(vertical = 16.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = recoveryCode,
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = AsterStar,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.8.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (copied) "✓ Copied to clipboard" else "Tap to copy",
                        color = if (copied) Color(0xFF66FFAA) else AsterViolet,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onAcknowledge,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AsterViolet,
                    contentColor = AsterNight
                )
            ) {
                Text(
                    text = "I Have Saved My Recovery Code",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun AuthInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = AsterStar,
            fontSize = 15.sp
        ),
        cursorBrush = SolidColor(AsterViolet),
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text
        ),
        keyboardActions = KeyboardActions(
            onDone = { onDone?.invoke() }
        ),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AsterElevated)
                    .border(1.dp, AsterViolet.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
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
