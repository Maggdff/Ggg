package com.duoprog.app.data.repository

import com.duoprog.app.data.local.*
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class ProgressRepository(private val dao: ProgressDao) {

    fun observeStats(): Flow<UserStatsEntity?> = dao.observeStats()
    fun observeLessonProgress(): Flow<List<LessonProgressEntity>> = dao.observeAllLessonProgress()
    fun observeAchievements(): Flow<List<AchievementEntity>> = dao.observeAchievements()
    fun observeMistakes(): Flow<List<MistakeEntity>> = dao.observeActiveMistakes()

    suspend fun ensureStatsInitialized() {
        if (dao.getStatsOnce() == null) {
            dao.upsertStats(UserStatsEntity())
        }
    }

    suspend fun addXp(amount: Int): UserStatsEntity {
        val current = dao.getStatsOnce() ?: UserStatsEntity()
        val todayEpochDay = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())
        val lastDay = current.lastStudyEpochDay
        val newStreak = when {
            lastDay == todayEpochDay -> current.streakDays
            lastDay == todayEpochDay - 1 -> current.streakDays + 1
            else -> 1
        }
        val newXp = current.xp + amount
        val newLevel = (newXp / 100) + 1
        val updated = current.copy(
            xp = newXp,
            level = newLevel,
            streakDays = newStreak,
            lastStudyEpochDay = todayEpochDay
        )
        dao.upsertStats(updated)
        return updated
    }

    suspend fun incrementCorrectAnswers() {
        val current = dao.getStatsOnce() ?: UserStatsEntity()
        dao.upsertStats(current.copy(totalExercisesCorrect = current.totalExercisesCorrect + 1))
    }

    suspend fun markLessonCompleted(lessonId: String, unitId: String, stars: Int) {
        dao.upsertLessonProgress(
            LessonProgressEntity(
                lessonId = lessonId,
                unitId = unitId,
                completed = true,
                stars = stars,
                lastAttemptEpochMillis = System.currentTimeMillis()
            )
        )
        val current = dao.getStatsOnce() ?: UserStatsEntity()
        dao.upsertStats(current.copy(totalLessonsCompleted = current.totalLessonsCompleted + 1))
    }

    suspend fun isLessonCompleted(lessonId: String): Boolean =
        dao.getLessonProgress(lessonId)?.completed ?: false

    suspend fun recordAttempt(exerciseId: String, lessonId: String, isCorrect: Boolean, userAnswer: String) {
        dao.insertAttempt(
            ExerciseAttemptEntity(
                exerciseId = exerciseId,
                lessonId = lessonId,
                isCorrect = isCorrect,
                userAnswer = userAnswer,
                timestampEpochMillis = System.currentTimeMillis()
            )
        )
        if (!isCorrect) {
            val existing = dao.getMistake(exerciseId)
            dao.upsertMistake(
                MistakeEntity(
                    exerciseId = exerciseId,
                    lessonId = lessonId,
                    timesWrong = (existing?.timesWrong ?: 0) + 1,
                    lastWrongEpochMillis = System.currentTimeMillis(),
                    resolved = false
                )
            )
        } else {
            dao.getMistake(exerciseId)?.let { dao.resolveMistake(exerciseId) }
        }
    }

    suspend fun unlockAchievement(id: String) {
        dao.upsertAchievement(
            AchievementEntity(achievementId = id, unlocked = true, unlockedAtEpochMillis = System.currentTimeMillis())
        )
    }
}
