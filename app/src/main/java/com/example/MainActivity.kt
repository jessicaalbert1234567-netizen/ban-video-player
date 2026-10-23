package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.DubbingViewModel
import com.example.ui.screens.DubbingProgressScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ModelManagerScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SettingsScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.update.AppUpdateManager
import com.example.update.MandatoryUpdateScreen
import com.example.update.UpdateUiState
import com.example.ui.screens.TestPipelineScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DubbingViewModel by viewModels()
    private lateinit var updateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateManager = AppUpdateManager(this)

        setContent {
            MyApplicationTheme {
                val updateState by updateManager.updateState.collectAsState()

                LaunchedEffect(Unit) {
                    updateManager.checkForUpdates()
                }

                when (val state = updateState) {
                    is UpdateUiState.UpToDate -> {
                        LaunchedEffect(Unit) {
                            com.example.ads.AdMobManager.initialize(this@MainActivity)
                        }
                        RequestAppPermissions()
                        AppNavigation(viewModel = viewModel)
                    }
                    else -> {
                        MandatoryUpdateScreen(
                            updateManager = updateManager,
                            uiState = state
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RequestAppPermissions() {
        val permissionsToRequest: Array<String> = remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            // Permissions handled gracefully
        }

        LaunchedEffect(Unit) {
            val missingPermissions = permissionsToRequest.filter { perm: String ->
                ContextCompat.checkSelfPermission(this@MainActivity, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPermissions.isNotEmpty()) {
                permissionLauncher.launch(missingPermissions.toTypedArray())
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: DubbingViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToModelManager = { navController.navigate("models") },
                onNavigateToProgress = { projectId -> navController.navigate("progress/$projectId") },
                onNavigateToPlayer = { navController.navigate("player") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToTest = { navController.navigate("test") }
            )
        }

        composable("models") {
            ModelManagerScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "progress/{projectId}",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            DubbingProgressScreen(
                viewModel = viewModel,
                projectId = projectId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPlayer = {
                    navController.navigate("player") {
                        popUpTo("home")
                    }
                }
            )
        }

        composable("player") {
            PlayerScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("test") {
            TestPipelineScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
