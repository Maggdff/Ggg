package com.duoprog.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {

    @Query("SELECT * FROM user_stats WHERE id = 1")
    fun observeStats(): Flow<UserStatsEntity?>

    @Query("SELECT * FROM user_stats WHERE id = 1")
    suspend fun getStatsOnce(): UserStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(stats: UserStatsEntity)

    @Query("SELECT * FROM lesson_progress")
    fun observeAllLessonProgress(): Flow<List<LessonProgressEntity>>

    @Query("SELECT * FROM lesson_progress WHERE lessonId = :lessonId")
    suspend fun getLessonProgress(lessonId: String): LessonProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLessonProgress(progress: LessonProgressEntity)

    @Insert
    suspend fun insertAttempt(attempt: ExerciseAttemptEntity)

    @Query("SELECT * FROM achievements")
    fun observeAchievements(): Flow<List<AchievementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAchievement(achievement: AchievementEntity)

    @Query("SELECT * FROM mistakes WHERE resolved = 0")
    fun observeActiveMistakes(): Flow<List<MistakeEntity>>

    @Query("SELECT * FROM mistakes WHERE exerciseId = :exerciseId")
    suspend fun getMistake(exerciseId: String): MistakeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMistake(mistake: MistakeEntity)

    @Query("UPDATE mistakes SET resolved = 1 WHERE exerciseId = :exerciseId")
    suspend fun resolveMistake(exerciseId: String)
}
