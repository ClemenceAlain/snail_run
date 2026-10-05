package io.snailrun.domain.coach

import java.time.LocalDate

/** One recorded run, as much of it as the coach reads. */
data class CoachRun(
    val date: LocalDate,
    val meters: Double,
    val movingMs: Long,
)
