package com.frontieraudio.app.domain.model

data class WordTimestamp(
    val text: String,
    val start: Long,
    val end: Long,
    val confidence: Float,
)
