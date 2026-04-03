package ru.cheburmail.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Корневой экран онбординга.
 * Маршрутизирует между шагами мастера настройки аккаунта.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val isComplete by viewModel.isComplete.collectAsState()

    if (isComplete) {
        onComplete()
        return
    }

    when (currentStep) {
        OnboardingStep.PROVIDER_SELECT -> {
            ProviderSelectScreen(
                onProviderSelected = { provider ->
                    viewModel.selectProvider(provider)
                }
            )
        }

        OnboardingStep.APP_PASSWORD_GUIDE -> {
            val provider by viewModel.selectedProvider.collectAsState()
            provider?.let {
                AppPasswordGuideScreen(
                    provider = it,
                    onContinue = { viewModel.onGuideRead() },
                    onBack = { viewModel.goBack() }
                )
            }
        }

        OnboardingStep.CREDENTIALS -> {
            val provider by viewModel.selectedProvider.collectAsState()
            val email by viewModel.email.collectAsState()
            val password by viewModel.password.collectAsState()

            provider?.let {
                CredentialsScreen(
                    provider = it,
                    email = email,
                    password = password,
                    onEmailChange = viewModel::updateEmail,
                    onPasswordChange = viewModel::updatePassword,
                    onConnect = { viewModel.startConnectionTest() },
                    onBack = { viewModel.goBack() }
                )
            }
        }

        OnboardingStep.CONNECTION_TEST -> {
            val testState by viewModel.connectionTestState.collectAsState()

            ConnectionTestScreen(
                state = testState,
                onRetry = { viewModel.retryTest() },
                onDone = onComplete
            )
        }
    }
}
