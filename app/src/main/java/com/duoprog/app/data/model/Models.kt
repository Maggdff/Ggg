package com.duoprog.app.data.model

enum class ExerciseType {
    MCQ, CODE_COMPLETION, LINE_ORDERING, BUG_FINDING, OUTPUT_PREDICTION, RUN_CODE
}

data class CurriculumUnit(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val order: Int,
    val lessons: List<Lesson>
)

data class Lesson(
    val id: String,
    val unitId: String,
    val title: String,
    val order: Int,
    val sourcePage: Int,
    val contentBlocks: List<ContentBlock>,
    val exercises: List<Exercise>
)

data class ContentBlock(
    val type: String,      // text | code | note
    val lines: List<String>
)

data class Exercise(
    val id: String,
    val lessonId: String,
    val type: ExerciseType,
    val question: String,
    val sourcePage: Int,
    val xpReward: Int = 10,
    val options: List<String>? = null,
    val correctOptionIndex: Int? = null,
    val codeTemplate: List<String>? = null,
    val blankAnswers: List<String>? = null,
    val shuffledLines: List<String>? = null,
    val correctOrder: List<Int>? = null,
    val buggyCode: List<String>? = null,
    val correctLineIndex: Int? = null,
    val fixedLineText: String? = null,
    val code: List<String>? = null,
    val stdin: List<String>? = null,
    val expectedOutput: String? = null,
    val explanation: String? = null
)
