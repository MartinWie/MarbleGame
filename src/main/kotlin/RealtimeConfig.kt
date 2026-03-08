package de.mw

internal object RealtimeConfig {
    private const val ENV_ALLOWED_ORIGINS = "REALTIME_ALLOWED_ORIGINS"
    private const val ENV_ALLOW_NULL_ORIGIN = "REALTIME_ALLOW_NULL_ORIGIN"
    private const val ENV_CHESS_RESUME_STALE_PING_MS = "CHESS_RESUME_STALE_PING_MS"
    private const val PROP_ALLOWED_ORIGINS = "realtime.allowed.origins"
    private const val PROP_ALLOW_NULL_ORIGIN = "realtime.allow.null.origin"
    private const val PROP_CHESS_RESUME_STALE_PING_MS = "realtime.chess.resume.stale.ping.ms"

    val allowedOrigins: Set<String>
        get() = parseAllowedOrigins(rawAllowedOrigins())

    val allowNullOrigin: Boolean
        get() = parseBoolean(rawAllowNullOrigin(), default = false)

    val chessResumeStalePingMs: Long
        get() = parseLong(rawChessResumeStalePingMs(), default = 15_000L, min = 5_000L, max = 120_000L)

    private fun rawAllowedOrigins(): String = System.getProperty(PROP_ALLOWED_ORIGINS) ?: System.getenv(ENV_ALLOWED_ORIGINS) ?: ""

    private fun rawAllowNullOrigin(): String = System.getProperty(PROP_ALLOW_NULL_ORIGIN) ?: System.getenv(ENV_ALLOW_NULL_ORIGIN) ?: ""

    private fun rawChessResumeStalePingMs(): String =
        System.getProperty(PROP_CHESS_RESUME_STALE_PING_MS) ?: System.getenv(ENV_CHESS_RESUME_STALE_PING_MS) ?: ""

    internal fun parseAllowedOrigins(raw: String): Set<String> =
        raw
            .split(',')
            .asSequence()
            .map { it.trim().removeSuffix("/").lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    internal fun parseBoolean(
        raw: String,
        default: Boolean,
    ): Boolean =
        when (raw.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }

    internal fun parseLong(
        raw: String,
        default: Long,
        min: Long,
        max: Long,
    ): Long {
        val parsed = raw.trim().toLongOrNull() ?: return default
        return parsed.coerceIn(min, max)
    }
}
