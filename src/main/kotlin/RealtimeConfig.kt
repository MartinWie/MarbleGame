package de.mw

internal object RealtimeConfig {
    private val rawAllowedOrigins = System.getenv("REALTIME_ALLOWED_ORIGINS") ?: ""

    val allowedOrigins: Set<String> =
        rawAllowedOrigins
            .split(',')
            .asSequence()
            .map { it.trim().removeSuffix("/").lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
}
