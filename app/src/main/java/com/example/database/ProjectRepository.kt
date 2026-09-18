package com.example.database

import com.example.dubbing.ProcessingStage
import kotlinx.coroutines.flow.Flow
import java.io.File

class ProjectRepository(private val projectDao: DubbingProjectDao) {

    val allProjects: Flow<List<DubbingProject>> = projectDao.getAllProjects()

    fun getProject(id: String): Flow<DubbingProject?> = projectDao.getProjectById(id)

    suspend fun findProject(id: String): DubbingProject? = projectDao.findProjectById(id)

    suspend fun saveProject(project: DubbingProject) = projectDao.insertProject(project)

    suspend fun updateProjectProgress(
        projectId: String,
        stage: ProcessingStage,
        percent: Int,
        statusMessage: String,
        errorMessage: String? = null
    ) {
        val project = projectDao.findProjectById(projectId) ?: return
        val updated = project.copy(
            currentStage = stage,
            progressPercent = percent,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
            updatedAt = System.currentTimeMillis()
        )
        projectDao.updateProject(updated)
    }

    suspend fun deleteProject(id: String, deleteFiles: Boolean = true) {
        val project = projectDao.findProjectById(id)
        if (project != null && deleteFiles) {
            val dir = File(project.projectDirPath)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
        projectDao.deleteSegmentsForProject(id)
        projectDao.deleteProjectById(id)
    }

    fun getSegments(projectId: String): Flow<List<TranscriptSegmentEntity>> =
        projectDao.getSegmentsForProject(projectId)

    suspend fun findSegments(projectId: String): List<TranscriptSegmentEntity> =
        projectDao.findSegmentsForProject(projectId)

    suspend fun saveSegments(segments: List<TranscriptSegmentEntity>) =
        projectDao.insertSegments(segments)

    suspend fun deleteSegmentsForProject(projectId: String) =
        projectDao.deleteSegmentsForProject(projectId)
}
