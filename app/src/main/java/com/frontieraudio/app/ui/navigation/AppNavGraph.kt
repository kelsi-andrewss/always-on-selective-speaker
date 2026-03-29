package com.frontieraudio.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.frontieraudio.app.service.audio.AudioCaptureManager
import com.frontieraudio.app.service.audio.SileroVadProcessor
import com.frontieraudio.app.service.speaker.EnrollmentManager
import com.frontieraudio.app.service.speaker.SherpaOnnxVerifier
import com.frontieraudio.app.ui.screens.DashboardScreen
import com.frontieraudio.app.ui.screens.EnrollmentScreen
import com.frontieraudio.app.ui.screens.OnboardingScreen
import com.frontieraudio.app.ui.screens.SignInScreen

object Routes {
    const val SIGN_IN = "sign_in"
    const val ONBOARDING = "onboarding"
    const val ENROLLMENT = "enrollment"
    const val DASHBOARD = "dashboard"
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    audioCaptureManager: AudioCaptureManager,
    vadProcessor: SileroVadProcessor,
    verifier: SherpaOnnxVerifier,
    enrollmentManager: EnrollmentManager,
    onStartService: () -> Unit,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onAuthenticated = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.ENROLLMENT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.ENROLLMENT) {
            EnrollmentScreen(
                audioCaptureManager = audioCaptureManager,
                vadProcessor = vadProcessor,
                verifier = verifier,
                enrollmentManager = enrollmentManager,
                onEnrollmentComplete = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.ENROLLMENT) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onReEnroll = {
                    navController.navigate(Routes.ENROLLMENT) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
            )
        }
    }
}
