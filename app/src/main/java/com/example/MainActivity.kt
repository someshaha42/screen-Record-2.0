package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.compat.PlatformCompat
import com.example.service.RecordingPhase
import com.example.ui.RecorderViewModel
import com.example.ui.components.RegionSelectorOverlay
import com.example.ui.screens.MainScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.theme.FrameCutTheme

private const val ROUTE_MAIN = "main"
private const val ROUTE_RECORDINGS = "recordings"

class MainActivity : ComponentActivity() {

  private val viewModel: RecorderViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      FrameCutTheme {
        FrameCutApp(
          viewModel = viewModel,
          onOpenOverlaySettings = { openOptionalOverlaySettings() },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshSystemState()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    viewModel.refreshSystemState()
  }

  private fun openOptionalOverlaySettings() {
    val intent =
      Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
      )
    runCatching { startActivity(intent) }
  }
}

@Composable
private fun FrameCutApp(
  viewModel: RecorderViewModel,
  onOpenOverlaySettings: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val navController = rememberNavController()
  val context = androidx.compose.ui.platform.LocalContext.current

  var showAudioDeniedDialog by remember { mutableStateOf(false) }

  val projectionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
      val data = result.data
      if (result.resultCode == Activity.RESULT_OK && data != null) {
        viewModel.startRecording(result.resultCode, data)
      }
    }

  fun launchMediaProjectionConsent() {
    if (uiState.session.phase != RecordingPhase.IDLE) return
    val mgr =
      context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    projectionLauncher.launch(PlatformCompat.createScreenCaptureIntent(mgr))
  }

  val recordAudioLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
      if (granted) {
        launchMediaProjectionConsent()
      } else {
        showAudioDeniedDialog = true
      }
    }

  val notificationLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
      val hasAudioPerm =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
      if (hasAudioPerm) {
        launchMediaProjectionConsent()
      } else {
        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }

  fun beginRecordFlow() {
    if (uiState.session.phase != RecordingPhase.IDLE) return
    val hasNotify =
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasNotify) {
      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }

    val hasAudioPerm =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (hasAudioPerm) {
      launchMediaProjectionConsent()
    } else {
      recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    NavHost(navController = navController, startDestination = ROUTE_MAIN) {
      composable(ROUTE_MAIN) {
        MainScreen(
          uiState = uiState,
          onStartRecordClick = { beginRecordFlow() },
          onPauseResumeClick = remember(viewModel) { viewModel::togglePauseResume },
          onStopClick = remember(viewModel) { viewModel::stopRecording },
          onOpenRecordings =
            remember(viewModel, navController) {
              {
                viewModel.refreshRecordings()
                navController.navigate(ROUTE_RECORDINGS) { launchSingleTop = true }
              }
            },
          onSelectMode = remember(viewModel) { viewModel::setRecordingMode },
          onSelectOrientationMode = remember(viewModel) { viewModel::setOrientationMode },
          onSelectAudioSource = remember(viewModel) { viewModel::setAudioSource },
          onSelectBitrate = remember(viewModel) { viewModel::setVideoBitrate },
          onSelectRegionAnimation = remember(viewModel) { viewModel::setRegionAnimation },
          onEditRegion = remember(viewModel) { viewModel::openRegionEditor },
          onRequestOverlayPermission = onOpenOverlaySettings,
          onDismissMessages = remember(viewModel) { viewModel::clearMessages },
        )
      }

      composable(ROUTE_RECORDINGS) {
        RecordingsScreen(
          recordings = uiState.recordings,
          isLoading = uiState.isLoadingRecordings,
          onBack = { navController.popBackStack() },
          onDelete = { viewModel.deleteRecording(it) },
        )
      }
    }

    if (uiState.isEditingRegion) {
      RegionSelectorOverlay(
        initialRegion = uiState.settings.selectedRegion,
        screenWidthPx = uiState.canvasWidthPx,
        screenHeightPx = uiState.canvasHeightPx,
        regionAnimation = uiState.settings.regionAnimation,
        onConfirm = { region -> viewModel.saveSelectedRegion(region) },
        onCancel = { viewModel.closeRegionEditor() },
      )
    }
  }

  if (showAudioDeniedDialog) {
    AlertDialog(
      onDismissRequest = { showAudioDeniedDialog = false },
      title = { Text("Audio permission not granted") },
      text = {
        Text(
          "Recording internal or microphone audio requires the Android audio permission. " +
            "You can still record video without audio, or grant permission now.",
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showAudioDeniedDialog = false
            launchMediaProjectionConsent()
          },
        ) {
          Text("Record Video Only")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showAudioDeniedDialog = false
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
          },
        ) {
          Text("Grant Permission")
        }
      },
    )
  }
}
