package com.frontieraudio.app.domain.model

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
)
