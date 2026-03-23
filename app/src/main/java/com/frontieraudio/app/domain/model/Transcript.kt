package com.frontieraudio.app.domain.model

data class Transcript(
    val id: String,
    val text: String,
    val correctedText: String?,
    val words: List<WordTimestamp>,
    val locationPoint: LocationPoint?,
    val sessionId: String,
    val createdAt: Long,
)
