package com.phoebe.app.player

enum class PlaybackEnginePath {
    Media3,
    Media3Crossfade,
    JavaFxMediaPlayer,
    SampledClip,
    SampledStream,
    WebAudioElement,
    AvPlayer,
}

interface PlaybackDiagnostics {
    fun engineSelected(engine: PlaybackEnginePath) = Unit

    fun playbackStartupEvent(
        engine: PlaybackEnginePath,
        event: String,
    ) = Unit

    fun platformPlaying(
        engine: PlaybackEnginePath,
        positionMs: Long,
        durationMs: Long,
    ) = Unit

    fun playbackProgress(
        engine: PlaybackEnginePath,
        positionMs: Long,
        durationMs: Long,
    ) = Unit

    fun decodedAudioEnergy(
        engine: PlaybackEnginePath,
        rms: Double,
    ) = Unit

    fun crossfadeStarted(
        engine: PlaybackEnginePath,
        outgoingTrackId: String?,
        incomingTrackId: String,
        durationMs: Long,
    ) = Unit

    fun crossfadeVolume(
        engine: PlaybackEnginePath,
        step: Int,
        outgoingVolume: Float,
        incomingVolume: Float,
    ) = Unit

    fun crossfadeCommitted(
        engine: PlaybackEnginePath,
        incomingTrackId: String,
    ) = Unit

    fun playbackError(
        engine: PlaybackEnginePath,
        message: String?,
    ) = Unit

    companion object {
        val None: PlaybackDiagnostics = object : PlaybackDiagnostics {}
    }
}
