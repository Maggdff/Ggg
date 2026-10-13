package com.duoprog.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val id: Int = 1,
    val xp: Int = 0,
    val level: Int = 1,
    val streakDays: Int = 0,
    val lastStudyEpochDay: Long = 0L,
    val totalLessonsCompleted: Int = 0,
    val totalExercisesCorrect: Int = 0
)

@Entity(tableName = "lesson_progress")
data class LessonProgressEntity(
    @PrimaryKey val lessonId: String,
    val unitId: String,
    val completed: Boolean = false,
    val stars: Int = 0,
    val lastAttemptEpochMillis: Long = 0L
)

@Entity(tableName = "exercise_attempts")
data class ExerciseAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: String,
    val lessonId: String,
    val isCorrect: Boolean,
    val userAnswer: String,
    val timestampEpochMillis: Long
)

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val achievementId: String,
    val unlocked: Boolean = false,
    val unlockedAtEpochMillis: Long = 0L
)

@Entity(tableName = "mistakes")
data class MistakeEntity(
    @PrimaryKey val exerciseId: String,
    val lessonId: String,
    val timesWrong: Int = 1,
    val lastWrongEpochMillis: Long = 0L,
    val resolved: Boolean = false
)
