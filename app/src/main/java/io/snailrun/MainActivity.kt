package io.snailrun

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.PermissionChecker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.snailrun.data.export.GpxExporter
import io.snailrun.data.voice.TtsState
import io.snailrun.data.prefs.Settings as AppSettings
import io.snailrun.tracking.RecordingState
import io.snailrun.tracking.RunRecordingService
import io.snailrun.ui.detail.RunDetailActions
import io.snailrun.ui.detail.RunDetailScreen
import io.snailrun.ui.detail.RunDetailTopBar
import io.snailrun.ui.detail.RunDetailViewModel
import io.snailrun.ui.history.HistoryScreen
import io.snailrun.ui.nav.ROUTE_RUN_DETAIL
import io.snailrun.ui.nav.SnailRunScaffold
import io.snailrun.ui.nav.TopLevel
import io.snailrun.ui.nav.runDetailRoute
import io.snailrun.ui.record.RecordScreen
import io.snailrun.ui.record.RecordViewModel
import io.snailrun.ui.settings.SettingsActions
import io.snailrun.ui.settings.SettingsScreen
import io.snailrun.ui.theme.SnailRunTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as SnailRunApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SnailRunTheme {
                val navController = rememberNavController()
                SnailRunScaffold(navController) { modifier ->
                    NavHost(
                        navController = navController,
                        startDestination = TopLevel.Record.route,
                        modifier = modifier,
                    ) {
                        composable(TopLevel.Record.route) {
                            RecordRoute()
                        }
                        composable(TopLevel.History.route) {
                            val runs by container.runRepository.observeHistory()
                                .collectAsStateWithLifecycle(emptyList())
                            HistoryScreen(
                                runs = runs,
                                onOpenRun = { navController.navigate(runDetailRoute(it)) },
                            )
                        }
                        composable(TopLevel.Settings.route) {
                            SettingsRoute()
                        }
                        composable(
                            route = ROUTE_RUN_DETAIL,
                            arguments = listOf(navArgument("runId") { type = NavType.LongType }),
                        ) { entry ->
                            val runId = entry.arguments?.getLong("runId") ?: return@composable
                            RunDetailRoute(runId = runId, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RecordRoute() {
        val viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory(container))
        val ui by viewModel.ui.collectAsStateWithLifecycle()
        val recording by viewModel.recording.collectAsStateWithLifecycle()

        // FINE and COARSE are requested together: on Android 12+ the dialog offers
        // "precise" or "approximate", and asking for FINE alone can still leave the app
        // with only COARSE, which is useless for a running trace.
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                RunRecordingService.start(this)
            }
        }

        val settings by container.settings.settings
            .collectAsStateWithLifecycle(initialValue = AppSettings())
        val isRecording = recording is RecordingState.Active

        // Held only while a run is in progress, and released the moment it ends, so the
        // phone does not sit awake in a pocket after the run is saved.
        DisposableEffect(isRecording, settings.keepScreenOn) {
            if (isRecording && settings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        RecordScreen(
            state = recording,
            // Demo mode invents its own fixes, so a disabled GPS is not a problem it
            // needs to warn about.
            gpsEnabled = ui.gpsEnabled || settings.demo.enabled,
            demoMode = settings.demo.enabled,
            onStart = {
                if (hasFineLocation()) {
                    RunRecordingService.start(this)
                } else {
                    permissionLauncher.launch(requiredPermissions())
                }
            },
            onPause = { RunRecordingService.pause(this) },
            onResume = { RunRecordingService.resume(this) },
            // The service owns the ordering: it completes the run, then exports it.
            onFinish = { RunRecordingService.finish(this) },
        )

        ui.unfinishedRun?.let { unfinished ->
            AlertDialog(
                onDismissRequest = viewModel::dismissRecovery,
                title = { Text("Unfinished run") },
                text = {
                    Text(
                        "A run was still being recorded when snail run last closed. " +
                            "Everything recorded up to that point is saved.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.finishRecoveredRun(unfinished.id) }) {
                        Text("Finish it")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { RunRecordingService.recover(this, unfinished.id) }) {
                        Text("Keep running")
                    }
                },
            )
        }
    }


    @Composable
    private fun RunDetailRoute(runId: Long, onBack: () -> Unit) {
        val viewModel: RunDetailViewModel =
            viewModel(factory = RunDetailViewModel.Factory(container))
        LaunchedEffect(runId) { viewModel.load(runId) }
        val state by viewModel.state.collectAsStateWithLifecycle()
        val message by viewModel.message.collectAsStateWithLifecycle()
        val snackbarHost = remember { SnackbarHostState() }

        // The auto-export already wrote this run to the chosen folder; this is the
        // "somewhere else, just this once" path.
        val saveAs = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(GpxExporter.MIME_TYPE),
        ) { uri -> if (uri != null) viewModel.exportManually(uri, runId) }

        LaunchedEffect(message) {
            message?.let {
                snackbarHost.showSnackbar(it)
                viewModel.clearMessage()
            }
        }

        Scaffold(
            topBar = {
                RunDetailTopBar(
                    RunDetailActions(
                        onBack = onBack,
                        onExport = { saveAs.launch(suggestedFileName(runId)) },
                        onShare = {
                            viewModel.share(runId) { uri ->
                                startActivity(
                                    Intent.createChooser(GpxExporter.shareIntent(uri), null)
                                )
                            }
                        },
                        onDelete = { viewModel.delete(runId, onBack) },
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
        ) { insets ->
            RunDetailScreen(
                state = state,
                onSelect = viewModel::select,
                onClearSelection = viewModel::clearSelection,
                modifier = Modifier.padding(insets),
            )
        }
    }

    private fun suggestedFileName(runId: Long) = "snail-run-$runId.gpx"

    @Composable
    private fun SettingsRoute() {
        val context = LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val settings by container.settings.settings
            .collectAsStateWithLifecycle(initialValue = AppSettings())
        val ttsState by container.voiceAnnouncer.state.collectAsStateWithLifecycle()

        // The engine forgets the rate between sessions; reapply it once it is ready.
        LaunchedEffect(ttsState, settings.speechRate) {
            if (ttsState is TtsState.Ready) container.voiceAnnouncer.setSpeechRate(settings.speechRate)
        }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                // Persist the grant, or it is gone on the next launch.
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                scope.launch { container.settings.setExportFolderUri(uri.toString()) }
            }
        }

        val actions = remember {
            SettingsActions(
                onVoiceEnabled = { scope.launch { container.settings.setVoiceEnabled(it) } },
                onAnnounceEveryMeters = {
                    scope.launch { container.settings.setAnnounceEveryMeters(it) }
                },
                onAnnounceEveryMinutes = {
                    scope.launch { container.settings.setAnnounceEveryMinutes(it) }
                },
                onSpeakElapsed = { scope.launch { container.settings.setSpeakElapsed(it) } },
                onSpeakAveragePace = { scope.launch { container.settings.setSpeakAveragePace(it) } },
                onSpeakLastSplit = { scope.launch { container.settings.setSpeakLastSplit(it) } },
                onSpeechRate = {
                    container.voiceAnnouncer.setSpeechRate(it)
                    scope.launch { container.settings.setSpeechRate(it) }
                },
                onTestVoice = { container.voiceAnnouncer.speakSample() },
                onOpenTtsSettings = { openTtsSettings() },
                onChooseExportFolder = { folderPicker.launch(null) },
                onAutoExport = { scope.launch { container.settings.setAutoExportEnabled(it) } },
                onKeepScreenOn = { scope.launch { container.settings.setKeepScreenOn(it) } },
                onAutoPause = { scope.launch { container.settings.setAutoPauseEnabled(it) } },
                onDemoEnabled = { scope.launch { container.settings.setDemoEnabled(it) } },
                onDemoSpeedFactor = { scope.launch { container.settings.setDemoSpeedFactor(it) } },
            )
        }

        SettingsScreen(
            settings = settings,
            ttsState = ttsState,
            actions = actions,
            modifier = Modifier,
        )
    }

    private fun hasFineLocation(): Boolean =
        PermissionChecker.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PermissionChecker.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Denying this hides the notification but does not stop the recording, so it is
        // asked for alongside rather than gated on.
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /**
     * Not a public Settings constant, so it is hardcoded and guarded: many builds,
     * including de-Googled ones, do not expose this screen.
     */
    private fun openTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS")
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    @Suppress("unused")
    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }
}
