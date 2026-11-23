package pl.cgconnector.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import pl.cgconnector.app.alerts.HomeSurfaces
import pl.cgconnector.app.data.repository.WorkoutQueueTrigger
import pl.cgconnector.app.work.RefreshScheduler
import javax.inject.Inject

@HiltAndroidApp
class CgApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var refreshScheduler: RefreshScheduler

    @Inject lateinit var workoutQueueTrigger: WorkoutQueueTrigger

    @Inject lateinit var homeSurfaces: HomeSurfaces

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        refreshScheduler.ensureScheduled()
        // Szkice czekające z poprzedniego uruchomienia (np. aplikacja zamknięta bez sieci).
        workoutQueueTrigger.trigger()
        homeSurfaces.start()
    }
}
