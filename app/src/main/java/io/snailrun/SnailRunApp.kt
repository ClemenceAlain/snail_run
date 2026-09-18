package io.snailrun

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SnailRunApp : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Runs recorded under an older position filter are re-derived once, in the
        // background. It touches only figures computed from the raw track, so the worst
        // a failure here costs is that a run keeps its old numbers until next launch.
        scope.launch { runCatching { container.runRepository.reprocessOutdatedRuns() } }
    }
}
