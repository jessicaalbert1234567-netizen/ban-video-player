package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DubbingProjectDao {

    @Query("SELECT * FROM dubbing_projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<DubbingProject>>

    @Query("SELECT * FROM dubbing_projects WHERE id = :id")
    fun getProjectById(id: String): Flow<DubbingProject?>

    @Query("SELECT * FROM dubbing_projects WHERE id = :id")
    suspend fun findProjectById(id: String): DubbingProject?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: DubbingProject)

    @Update
    suspend fun updateProject(project: DubbingProject)

    @Query("DELETE FROM dubbing_projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    @Query("SELECT * FROM transcript_segments WHERE projectId = :projectId ORDER BY `index` ASC")
    fun getSegmentsForProject(projectId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE projectId = :projectId ORDER BY `index` ASC")
    suspend fun findSegmentsForProject(projectId: String): List<TranscriptSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)

    @Query("DELETE FROM transcript_segments WHERE projectId = :projectId")
    suspend fun deleteSegmentsForProject(projectId: String)
}
