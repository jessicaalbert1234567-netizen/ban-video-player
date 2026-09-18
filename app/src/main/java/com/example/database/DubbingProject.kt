package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.dubbing.ProcessingStage

@Entity(tableName = "dubbing_projects")
data class DubbingProject(
    @PrimaryKey val id: String,
    val title: String,
    val videoUriString: String,
    val durationMs: Long = 0L,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "bn",
    val currentStage: ProcessingStage = ProcessingStage.EXTRACT_AUDIO,
    val progressPercent: Int = 0,
    val statusMessage: String = "Ready to process",
    val errorMessage: String? = null,
    val projectDirPath: String,
    val originalAudioPath: String? = null,
    val transcriptJsonPath: String? = null,
    val subtitleSrtPath: String? = null,
    val dubbedAudioPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val asrModelVersion: String = "1.2.0",
    val translationModelVersion: String = "1.1.0",
    val ttsModelVersion: String = "1.0.4"
)

@Entity(tableName = "transcript_segments")
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val segmentId: Long = 0L,
    val projectId: String,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String? = null,
    val confidence: Float? = null,
    val audioSegmentPath: String? = null
)
