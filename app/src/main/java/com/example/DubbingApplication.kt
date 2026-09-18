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
    }

    companion object {
        lateinit var instance: DubbingApplication
            private set
    }
}
