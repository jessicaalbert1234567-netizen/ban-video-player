package com.example

import android.app.Application
import com.example.database.AppDatabase
import com.example.database.ProjectRepository
import com.example.dubbing.DubbingPipeline
import com.example.models.ModelManager
import com.example.player.MediaPlayerManager
import com.example.settings.SettingsManager
import com.example.storage.StorageManager

class DubbingApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: ProjectRepository
        private set

    lateinit var modelManager: ModelManager
        private set

    lateinit var storageManager: StorageManager
        private set

    lateinit var settingsManager: SettingsManager
        private set

    lateinit var dubbingPipeline: DubbingPipeline
        private set

    lateinit var playerManager: MediaPlayerManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        repository = ProjectRepository(database.projectDao())
        modelManager = ModelManager(this)
        storageManager = StorageManager(this)
        settingsManager = SettingsManager(this)
        dubbingPipeline = DubbingPipeline(this, repository, storageManager, settingsManager)
        playerManager = MediaPlayerManager(this)

        // Initialize Anti-Tamper Security Guard & Clean WebView Variations Seed
        cleanupStaleVariationsSeeds()
        com.example.security.AntiTamperGuard.verifyIntegrity(this)
    }

    private fun cleanupStaleVariationsSeeds() {
        try {
            val dataDir = java.io.File(applicationInfo.dataDir)
            val webviewDir = java.io.File(dataDir, "app_webview")
            if (webviewDir.exists()) {
                webviewDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("variations")) {
                        file.delete()
                    }
                }
            }
            val prefsDir = java.io.File(dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.listFiles()?.forEach { file ->
                    if (file.name.contains("variations", ignoreCase = true)) {
                        file.delete()
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    companion object {
        lateinit var instance: DubbingApplication
            private set
    }
}
