package com.frontieraudio.app.service

import android.os.Build

enum class OemType {
    SAMSUNG,
    XIAOMI,
    GENERIC,
}

data class OemInstructions(
    val title: String,
    val steps: String,
)

object OemDetector {

    fun detect(): OemType {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("samsung") -> OemType.SAMSUNG
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> OemType.XIAOMI
            else -> OemType.GENERIC
        }
    }

    fun getInstructions(oem: OemType): OemInstructions? = when (oem) {
        OemType.SAMSUNG -> OemInstructions(
            title = "Samsung Battery Settings",
            steps = "Settings > Battery > Background usage limits > Never sleeping apps > Add \"Selective Speaker\"",
        )
        OemType.XIAOMI -> OemInstructions(
            title = "Xiaomi Background Autostart",
            steps = "Settings > Apps > Manage apps > Selective Speaker > Autostart (enable)",
        )
        OemType.GENERIC -> null
    }
}
