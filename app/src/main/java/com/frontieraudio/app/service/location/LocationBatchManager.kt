package com.frontieraudio.app.service.location

import com.frontieraudio.app.domain.model.LocationPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class LocationBatchManager @Inject constructor() {

    private val buffer = ArrayDeque<LocationPoint>(MAX_ENTRIES)

    @Synchronized
    fun addLocation(point: LocationPoint) {
        if (buffer.size >= MAX_ENTRIES) {
            buffer.removeFirst()
        }
        buffer.addLast(point)
    }

    @Synchronized
    fun getNearestLocation(timestamp: Long): LocationPoint? {
        if (buffer.isEmpty()) return null

        val index = binarySearchClosest(timestamp)
        val candidate = buffer[index]
        val delta = abs(candidate.timestamp - timestamp)
        if (delta > TOLERANCE_MS) return null
        return candidate
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    private fun binarySearchClosest(timestamp: Long): Int {
        var low = 0
        var high = buffer.size - 1

        while (low < high) {
            val mid = (low + high) / 2
            if (buffer[mid].timestamp < timestamp) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        if (low == 0) return 0
        val prev = low - 1
        val deltaLow = abs(buffer[low].timestamp - timestamp)
        val deltaPrev = abs(buffer[prev].timestamp - timestamp)
        return if (deltaPrev <= deltaLow) prev else low
    }

    companion object {
        private const val MAX_ENTRIES = 100
        private const val TOLERANCE_MS = 30_000L // 30 seconds
    }
}
