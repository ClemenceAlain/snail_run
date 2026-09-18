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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.snailrun.data.backup.BackupResult
import io.snailrun.data.backup.DatabaseBackup
import io.snailrun.data.backup.RestoreResult
import io.snailrun.data.export.GpxExporter
import io.snailrun.data.voice.TtsState
import io.snailrun.data.prefs.Settings as AppSettings
import io.snailrun.tracking.RecordingState
import io.snailrun.tracking.RunRecordingService
import io.snailrun.data.basemap.BasemapInstall
import io.snailrun.ui.coach.CoachScreen
import io.snailrun.ui.coach.CoachViewModel
import io.snailrun.ui.components.BasemapLayer
import io.snailrun.ui.detail.RunDetailActions
import io.snailrun.ui.detail.RunDetailScreen
import io.snailrun.ui.detail.RunDetailTopBar
import io.snailrun.ui.detail.RunMapTopBar
import io.snailrun.ui.detail.RunDetailViewModel
import io.snailrun.ui.history.HistoryScreen
import io.snailrun.ui.history.HistoryViewModel
import io.snailrun.ui.map.RouteMapScreen
import io.snailrun.ui.nav.ROUTE_RUN_DETAIL
import io.snailrun.ui.nav.ROUTE_RUN_MAP
import io.snailrun.ui.nav.SnailRunScaffold
import io.snailrun.ui.nav.TopLevel
import io.snailrun.ui.nav.runDetailRoute
import io.snailrun.ui.nav.runMapRoute
import io.snailrun.ui.record.RecordScreen
import io.snailrun.ui.record.RecordViewModel
import io.snailrun.ui.settings.SettingsActions
import io.snailrun.ui.settings.SettingsScreen
import io.snailrun.ui.theme.SnailRunTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.system.exitProcess
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
                            HistoryRoute(
                                onOpenRun = { navController.navigate(runDetailRoute(it)) },
                            )
                        }
                        composable(TopLevel.Coach.route) {
                            CoachRoute()
                        }
                        composable(TopLevel.Settings.route) {
                            SettingsRoute()
                        }
                        composable(
                            route = ROUTE_RUN_DETAIL,
                            arguments = listOf(navArgument("runId") { type = NavType.LongType }),
                        ) { entry ->
                            val runId = entry.arguments?.getLong("runId") ?: return@composable
                            RunDetailRoute(
                                runId = runId,
                                onBack = { navController.popBackStack() },
                                onOpenMap = { navController.navigate(runMapRoute(runId)) },
                            )
                        }
                        composable(
                            route = ROUTE_RUN_MAP,
                            arguments = listOf(navArgument("runId") { type = NavType.LongType }),
                        ) { entry ->
                            val runId = entry.arguments?.getLong("runId") ?: return@composable
                            RunMapRoute(runId = runId, onBack = { navController.popBackStack() })
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
    private fun HistoryRoute(onOpenRun: (Long) -> Unit) {
        val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(container))
        val ui by viewModel.ui.collectAsStateWithLifecycle()

        HistoryScreen(
            state = ui,
            onOpenRun = onOpenRun,
            onSetMode = viewModel::setMode,
            onSelectDate = viewModel::selectDate,
            onShowMonth = viewModel::showMonth,
            onSetProgressPeriod = viewModel::setProgressPeriod,
        )
    }

    @Composable
    private fun CoachRoute() {
        val viewModel: CoachViewModel = viewModel(factory = CoachViewModel.Factory(container))
        val ui by viewModel.ui.collectAsStateWithLifecycle()
        CoachScreen(
            state = ui,
            onExpand = viewModel::expand,
            onMove = viewModel::move,
            onResetWeek = viewModel::resetWeek,
            onShowWeek = viewModel::showWeek,
            today = LocalDate.now(),
        )
    }

    @Composable
    private fun RunDetailRoute(runId: Long, onBack: () -> Unit, onOpenMap: () -> Unit) {
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
                onOpenMap = onOpenMap,
                modifier = Modifier.padding(insets),
                basemap = basemapLayer(),
            )
        }
    }

    /**
     * The run on a full screen.
     *
     * It loads the run again rather than being handed the one the detail screen already
     * has. A map is reached from a list as often as from a run, the reload is one query
     * against a local database, and the alternative is a piece of shared state that has
     * to be right across process death.
     */
    @Composable
    private fun RunMapRoute(runId: Long, onBack: () -> Unit) {
        val viewModel: RunDetailViewModel =
            viewModel(factory = RunDetailViewModel.Factory(container))
        LaunchedEffect(runId) { viewModel.load(runId) }
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { RunMapTopBar(title = state.run?.title ?: "Map", onBack = onBack) },
        ) { insets ->
            RouteMapScreen(
                segments = state.segments,
                modifier = Modifier.padding(insets),
                basemap = basemapLayer(),
            )
        }
    }

    /**
     * The tile layer to draw runs on: the map the user picked, or the demo map the app
     * ships with when they have not picked one.
     *
     * The two are not handed over on the same terms. A map the user chose applies to
     * every run they have, holes and all — they chose it. The bundled one covers one
     * kilometre of invented streets, so it is handed over with its coverage attached and
     * a run anywhere else is drawn as it always was, over blank.
     */
    @Composable
    private fun basemapLayer(): BasemapLayer? {
        val store = container.basemapStore
        val active by produceState<io.snailrun.data.basemap.ActiveBasemap?>(null) {
            value = store.active()
        }
        val current = active ?: return null
        return remember(current) {
            BasemapLayer(
                minZoom = current.info.minZoom,
                maxZoom = current.info.maxZoom,
                tile = { zoom, x, y -> store.tile(zoom, x, y) },
                coverage = current.info.coverage.takeIf { current.bundled },
                attribution = "Demo map — an invented town, not a real place."
                    .takeIf { current.bundled },
            )
        }
    }

    private fun suggestedFileName(runId: Long) = "snail-run-$runId.gpx"

    /** Whole megabytes once there are any, so a small map does not report itself as 0 MB. */
    private fun formatFileSize(bytes: Long): String =
        if (bytes >= 1_000_000) "${bytes / 1_000_000} MB" else "${bytes / 1_000} KB"

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

        var basemapStatus by remember { mutableStateOf("No map file yet.") }
        LaunchedEffect(settings.basemapUri) {
            val active = container.basemapStore.active()
            basemapStatus = when {
                active == null -> "No map file yet."
                active.bundled ->
                    "Using the demo map that came with the app — one invented kilometre, " +
                        "enough to try demo mode on. Pick a file to cover where you run."
                else -> with(active.info) {
                    buildString {
                        append(name ?: "Map file")
                        append(" · zoom $minZoom–$maxZoom")
                        append(" · $tileCount tiles")
                        append(" · ${formatFileSize(sizeBytes)}")
                    }
                }
            }
        }

        val basemapPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    basemapStatus = "Copying the map file…"
                    basemapStatus = when (val result = container.basemapStore.install(uri)) {
                        is BasemapInstall.Installed -> "Map file ready."
                        BasemapInstall.NotAnMbtilesFile ->
                            "That file is not an MBTiles map. Nothing was changed."
                        is BasemapInstall.Failed ->
                            "Could not use that file: ${result.error.message}"
                    }
                }
            }
        }

        var backupStatus by remember { mutableStateOf<String?>(null) }

        // The file the user picked, held while the confirmation dialog is up. Nothing is
        // read from it until they say yes.
        var pendingRestore by remember { mutableStateOf<Uri?>(null) }

        // Set only once the live database has been closed. From that point this screen is
        // reading from a handle that no longer exists, so the dialog offers no way out
        // but the restart.
        var restartPrompt by remember { mutableStateOf<String?>(null) }

        val backupPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(DatabaseBackup.MIME_TYPE),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    backupStatus = "Writing the backup…"
                    backupStatus = when (val result = container.databaseBackup.backupTo(uri)) {
                        is BackupResult.Written ->
                            "Backed up ${runsLabel(result.runCount)}, " +
                                "${result.sizeBytes / 1_000} kB. Keep the file somewhere " +
                                "other than this phone."

                        is BackupResult.Failed ->
                            "Could not write the backup: ${reason(result.error)}"
                    }
                }
            }
        }

        // Any type, for the same reason as the map file: a `.db` has no registered MIME
        // type, and pickers on de-Googled builds hide anything they cannot name.
        val restorePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) pendingRestore = uri }

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
                // Any type: MBTiles has no registered MIME type, and pickers on
                // de-Googled builds hide anything they cannot name.
                onChooseBasemap = { basemapPicker.launch(arrayOf("*/*")) },
                onRemoveBasemap = { scope.launch { container.basemapStore.remove() } },
                onCoachTarget = { meters, day ->
                    scope.launch { container.settings.setCoachTarget(meters, day) }
                },
                onDemoEnabled = { scope.launch { container.settings.setDemoEnabled(it) } },
                onDemoSpeedFactor = { scope.launch { container.settings.setDemoSpeedFactor(it) } },
                onBackup = { backupPicker.launch(DatabaseBackup.suggestedFileName(nowStamp())) },
                onRestore = { restorePicker.launch(arrayOf("*/*")) },
            )
        }

        SettingsScreen(
            settings = settings,
            ttsState = ttsState,
            actions = actions,
            modifier = Modifier,
            basemapStatus = basemapStatus,
            backupStatus = backupStatus,
        )

        pendingRestore?.let { uri ->
            AlertDialog(
                onDismissRequest = { pendingRestore = null },
                title = { Text("Replace every run?") },
                text = {
                    Text(
                        "Every run on this phone is replaced by the ones in that file. " +
                            "Anything recorded since the backup was taken is lost. " +
                            "Settings and the map file are not touched, and snail run " +
                            "reopens itself once the runs are back.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRestore = null
                            scope.launch {
                                backupStatus = "Restoring…"
                                when (val result = container.databaseBackup.restoreFrom(uri)) {
                                    is RestoreResult.Restored -> restartPrompt =
                                        "Restored ${runsLabel(result.runCount)}. snail run " +
                                            "has to reopen before it can show them."

                                    RestoreResult.NotABackupFile -> backupStatus =
                                        "That file is not a snail run backup. Nothing was " +
                                            "changed."

                                    is RestoreResult.FromNewerVersion -> backupStatus =
                                        "That backup came from a newer snail run " +
                                            "(it needs version ${result.found}; this build " +
                                            "reads ${result.supported}). Update the app " +
                                            "first. Nothing was changed."

                                    RestoreResult.RunInProgress -> backupStatus =
                                        "Finish your run first. It is not in the backup, " +
                                            "so restoring now would throw it away."

                                    is RestoreResult.Failed ->
                                        if (result.restartNeeded) {
                                            restartPrompt =
                                                "The restore failed and your previous runs " +
                                                    "were put back: ${reason(result.error)}. " +
                                                    "snail run has to reopen."
                                        } else {
                                            backupStatus =
                                                "Could not read that file: " +
                                                    "${reason(result.error)}. Nothing was " +
                                                    "changed."
                                        }
                                }
                            }
                        },
                    ) {
                        Text("Replace")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
                },
            )
        }

        restartPrompt?.let { message ->
            AlertDialog(
                // Not dismissible: the database behind this screen is already closed.
                onDismissRequest = {},
                title = { Text("Reopening snail run") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { restartApp() }) { Text("Reopen") }
                },
            )
        }
    }

    /**
     * Restores swap the database file out from under a graph that is captured by the
     * recorder, the exporter and three view models. Making every one of those swappable
     * is a large change for something run perhaps once a year; relaunching into a clean
     * process is the honest alternative, and the dialog says so before anything happens.
     */
    private fun restartApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
        exitProcess(0)
    }

    private fun runsLabel(count: Int) = if (count == 1) "1 run" else "$count runs"

    private fun reason(error: Throwable) = error.message ?: error::class.java.simpleName

    private fun nowStamp(): String =
        BACKUP_STAMP.format(Instant.now().atZone(ZoneId.systemDefault()))

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

    private companion object {
        val BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)
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
