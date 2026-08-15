package com.phoebe.app.player

/**
 * Why a track failed to start. Used to decide retry vs. stop, and to keep the queue
 * from marching through every song when the music server is unreachable.
 */
enum class PlaybackFailureKind {
    Unreachable,
    Unauthorized,
    NotFound,
    NotAudio,
    Unsupported,
    Transient,
    Unknown,
}

data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val message: String,
    val statusCode: Int? = null,
    val streamUri: String? = null,
    val cause: String? = null,
) {
    /** Retry the same stream a limited number of times. */
    val shouldRetry: Boolean
        get() = kind == PlaybackFailureKind.Unreachable || kind == PlaybackFailureKind.Transient

    /**
     * Only codec / player-creation problems are worth trying a different engine against
     * the same URI. Connection, auth, and HTML-instead-of-audio failures will fail again.
     */
    val shouldTryAlternateEngine: Boolean
        get() = kind == PlaybackFailureKind.Unsupported

    /** Server / network problems that must not walk the queue or retry every engine. */
    val isInfrastructureFailure: Boolean
        get() = kind == PlaybackFailureKind.Unreachable ||
            kind == PlaybackFailureKind.Unauthorized ||
            kind == PlaybackFailureKind.NotAudio ||
            kind == PlaybackFailureKind.Transient

    /**
     * Infrastructure / unknown start failures must not advance the queue. A down server
     * would otherwise look like "every song is broken."
     */
    val holdsQueue: Boolean
        get() = kind != PlaybackFailureKind.NotFound && kind != PlaybackFailureKind.Unsupported

    fun userMessage(trackTitle: String?): String {
        val title = trackTitle?.takeIf { it.isNotBlank() }
        return when (kind) {
            PlaybackFailureKind.Unreachable ->
                "Can't reach the music server. Check your connection and try again."
            PlaybackFailureKind.Unauthorized ->
                "The music server rejected this stream. Try reconnecting, then play again."
            PlaybackFailureKind.NotAudio ->
                "The music server didn't send audio. Check the server connection and try again."
            PlaybackFailureKind.NotFound ->
                title?.let { "Couldn't find a playable file for $it." } ?: "Couldn't find a playable file."
            PlaybackFailureKind.Unsupported ->
                title?.let { "Phoebe couldn't play $it on this device." } ?: "Phoebe couldn't play that song on this device."
            PlaybackFailureKind.Transient ->
                "The music server hit a temporary error. Try playing again."
            PlaybackFailureKind.Unknown ->
                title?.let { "Couldn't play $it." } ?: "Couldn't play that song."
        }
    }

    fun logLine(): String = buildString {
        append("playback failed: ")
        append(kind.name)
        message.takeIf { it.isNotBlank() }?.let {
            append(" message=").append(PlaybackFailureClassifier.redactSensitiveText(it))
        }
        statusCode?.let { append(" status=").append(it) }
        streamUri?.let { append(" uri=").append(PlaybackFailureClassifier.redactStreamUri(it)) }
        cause?.takeIf { it.isNotBlank() }?.let {
            append(" cause=").append(PlaybackFailureClassifier.redactSensitiveText(it))
        }
    }
}

object PlaybackFailureClassifier {
    // Media3 PlaybackException error codes (kept here so common tests can classify them).
    const val Media3IoUnspecified = 2000
    const val Media3IoNetworkConnectionFailed = 2001
    const val Media3IoNetworkConnectionTimeout = 2002
    const val Media3IoInvalidHttpContentType = 2003
    const val Media3IoBadHttpStatus = 2004
    const val Media3IoFileNotFound = 2005
    const val Media3ParsingContainerMalformed = 3001
    const val Media3ParsingContainerUnsupported = 3003
    const val Media3DecoderInitFailed = 4001
    const val Media3DecodingFormatUnsupported = 4004

    fun fromThrowable(error: Throwable?, streamUri: String? = null): PlaybackFailure {
        if (error == null) {
            return fromMessage(null, streamUri)
        }
        val chain = generateSequence(error) { it.cause }.toList()
        val statusCode = chain.firstNotNullOfOrNull { statusCodeFromMessage(it.message) }
        return fromSignals(
            texts = chain.flatMap { throwable ->
                listOfNotNull(throwable::class.simpleName, throwable.message)
            },
            streamUri = streamUri,
            statusCode = statusCode,
        )
    }

    fun fromMessage(message: String?, streamUri: String? = null): PlaybackFailure =
        fromSignals(
            texts = listOfNotNull(message),
            streamUri = streamUri,
            statusCode = statusCodeFromMessage(message),
        )

    fun fromMedia3(
        errorCode: Int,
        message: String?,
        causeChain: List<String> = emptyList(),
        httpStatus: Int? = null,
        streamUri: String? = null,
    ): PlaybackFailure {
        val texts = (listOfNotNull(message) + causeChain).filter { it.isNotBlank() }
        val statusCode = httpStatus ?: texts.firstNotNullOfOrNull { statusCodeFromMessage(it) }
        val fromCode = when (errorCode) {
            Media3IoNetworkConnectionFailed,
            Media3IoNetworkConnectionTimeout,
            -> PlaybackFailureKind.Unreachable
            Media3IoInvalidHttpContentType -> PlaybackFailureKind.NotAudio
            Media3IoFileNotFound -> PlaybackFailureKind.NotFound
            Media3IoBadHttpStatus -> kindForHttpStatus(statusCode)
            Media3ParsingContainerMalformed -> PlaybackFailureKind.NotAudio
            Media3ParsingContainerUnsupported,
            Media3DecoderInitFailed,
            Media3DecodingFormatUnsupported,
            -> PlaybackFailureKind.Unsupported
            else -> null
        }
        if (fromCode != null) {
            return PlaybackFailure(
                kind = fromCode,
                message = message?.takeIf { it.isNotBlank() } ?: fromCode.name,
                statusCode = statusCode,
                streamUri = streamUri,
                cause = causeChain.firstOrNull { it.isNotBlank() },
            )
        }
        return fromSignals(texts, streamUri, statusCode)
    }

    fun fromSignals(
        texts: List<String>,
        streamUri: String? = null,
        statusCode: Int? = null,
    ): PlaybackFailure {
        val haystack = texts.joinToString(" ").lowercase()
        val kind = when {
            statusCode != null && statusCode !in 200..299 -> kindForHttpStatus(statusCode)
            haystack.contains("unrecognized file signature") ||
                haystack.contains("didn't send audio") ||
                haystack.contains("instead of audio") ||
                haystack.contains("invalid content type") ||
                (haystack.contains("source error") && looksLikeHttpUri(streamUri) && haystack.contains("html"))
                -> kindForUnrecognizedSignature(streamUri)
            haystack.contains("could not create player") ||
                haystack.contains("unsupported") && haystack.contains("format")
                -> PlaybackFailureKind.Unsupported
            haystack.contains("401") || haystack.contains("unauthorized") || haystack.contains("403")
                -> PlaybackFailureKind.Unauthorized
            haystack.contains("404") || haystack.contains("not found")
                -> PlaybackFailureKind.NotFound
            haystack.contains("media_inaccessible") ||
                haystack.contains("media_unavailable") ||
                haystack.contains("connectexception") ||
                haystack.contains("connection refused") ||
                haystack.contains("connection reset") ||
                haystack.contains("failed to connect") ||
                haystack.contains("network is unreachable") ||
                haystack.contains("unknownhost") ||
                haystack.contains("unable to resolve host") ||
                haystack.contains("no address associated") ||
                haystack.contains("connect timed out") ||
                haystack.contains("connection timed out") ||
                haystack.contains("http connect timed out") ||
                haystack.contains("socket timeout") ||
                haystack.contains("timed out while buffering") ||
                haystack.contains("did not become ready") ||
                haystack.contains("never started playing") ||
                haystack.contains("took too long to start") ||
                haystack.contains("handshake") && haystack.contains("terminat")
                -> if (streamUri == null || looksLikeHttpUri(streamUri) || haystack.contains("timed out while buffering")) {
                    PlaybackFailureKind.Unreachable
                } else {
                    PlaybackFailureKind.Unknown
                }
            haystack.contains("source error") ->
                if (looksLikeHttpUri(streamUri)) PlaybackFailureKind.Unreachable else PlaybackFailureKind.Unknown
            haystack.contains("503") || haystack.contains("502") || haystack.contains("500")
                -> PlaybackFailureKind.Transient
            else -> PlaybackFailureKind.Unknown
        }
        return PlaybackFailure(
            kind = kind,
            message = texts.firstOrNull { it.isNotBlank() } ?: kind.name,
            statusCode = statusCode,
            streamUri = streamUri,
            cause = texts.drop(1).firstOrNull { it.isNotBlank() },
        )
    }

    fun redactStreamUri(uri: String): String {
        val withoutQuery = redactSensitiveText(uri).substringBefore('?')
        return if (withoutQuery.length <= 240) withoutQuery else withoutQuery.take(240)
    }

    fun redactSensitiveText(text: String): String =
        text.replace(Regex("""(https?://[^\s"'<>?]+)(\?[^\s"'<>]*)""", RegexOption.IGNORE_CASE)) { match ->
            match.groupValues[1]
        }

    private fun kindForHttpStatus(statusCode: Int?): PlaybackFailureKind = when (statusCode) {
        401, 403 -> PlaybackFailureKind.Unauthorized
        404, 410 -> PlaybackFailureKind.NotFound
        408, 429 -> PlaybackFailureKind.Transient
        in 500..599 -> PlaybackFailureKind.Transient
        in 400..499 -> PlaybackFailureKind.Unauthorized
        else -> PlaybackFailureKind.Transient
    }

    private fun kindForUnrecognizedSignature(streamUri: String?): PlaybackFailureKind =
        if (looksLikeHttpUri(streamUri)) PlaybackFailureKind.NotAudio else PlaybackFailureKind.Unsupported

    private fun looksLikeHttpUri(uri: String?): Boolean =
        uri?.startsWith("http://", ignoreCase = true) == true ||
            uri?.startsWith("https://", ignoreCase = true) == true

    private fun statusCodeFromMessage(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        val labeled = Regex(
            """(?:failed\s*\(|response code:?\s*|status(?: code)?:?\s*)([4-5]\d{2})\b""",
            RegexOption.IGNORE_CASE,
        ).find(message)
        if (labeled != null) return labeled.groupValues[1].toInt()
        val match = Regex("""\b(401|403|404|408|410|429|500|502|503|504)\b""").find(message) ?: return null
        return match.groupValues[1].toInt()
    }
}
