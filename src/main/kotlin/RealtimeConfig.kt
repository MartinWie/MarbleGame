package de.mw

internal object RealtimeConfig {
    private val rawAllowedOrigins = System.getenv("REALTIME_ALLOWED_ORIGINS") ?: ""

    const val transportMode: String = "auto"
    const val wsEnabled: Boolean = true
    const val sseEnabled: Boolean = true

    val allowedOrigins: Set<String> =
        rawAllowedOrigins
            .split(',')
            .asSequence()
            .map { it.trim().removeSuffix("/").lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
}
