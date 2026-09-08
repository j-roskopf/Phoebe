@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.phoebe.app.player

import android.app.PendingIntent
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaConstants
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import androidx.media3.common.PlaybackException
import com.phoebe.app.domain.RadioNowPlayingMetadata

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioNowPlayingJob: Job? = null
    private var radioStartupTimeoutJob: Job? = null

    /** Browse parents served before an origin existed, so their artwork came back empty. */
    private val unboundBrowseParents: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val servicePlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AndroidPlaybackBridge.updateServicePlayerState()
            AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            AndroidPlaybackBridge.updateServicePlayerState()
            AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
            lastLikeButtonTrackId = null
            lastLikeButtonLiked = null
            val track = mediaItem?.let(::trackFromMediaItemForLike)
            updateLikeButton(track)
            updateSessionTransportExtras(track)
            syncRadioNowPlaying(track)
            armRadioStartupTimeout(track)
        }

        override fun onPlayerError(error: PlaybackException) {
            AndroidPlaybackBridge.updateServicePlayerState()
            clearRadioStartupTimeout()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            AndroidPlaybackBridge.updateServicePlayerState()
            if (playbackState == Player.STATE_ENDED) {
                if (AndroidPlaybackBridge.suppressServiceEndedCallback) return
                AndroidPlaybackBridge.onTrackEnded?.invoke()
            } else {
                AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
            }
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_IDLE) {
                clearRadioStartupTimeout()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            AndroidPlaybackBridge.updateServicePlayerState()
            AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
        }
    }

    private val librarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            PhoebeLog.d(TAG) { "onConnect package=${controller.packageName}" }
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(LikeTrackCommand)
                .add(UnlikeTrackCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setCustomLayout(likeButtonLayout(currentTrackHintForLike()))
                .setMediaButtonPreferences(likeButtonLayout(currentTrackHintForLike()))
                .build()
                .also { updateLikeButton() }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            handledQueueNavigationCommand(playerCommand)?.let { result ->
                return result
            }
            return when (playerCommand) {
                else -> super.onPlayerCommandRequest(session, controller, playerCommand)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootParams = androidAutoRootParams(params)
            // Android Auto can browse before Compose ever starts, so nothing has published a
            // live Plex base yet. Warm one off the critical path and re-announce the tree once
            // it binds, so thumbs stop resolving to host-less paths.
            AndroidPlaybackRuntime.warmLiveOrigin(onBound = ::notifyBrowseTreeChanged)
            return listenableFuture("onGetLibraryRoot") {
                val source = AndroidPlaybackRuntime.ensureInstalledNow()
                runCatching {
                    LibraryResult.ofItem(source.getLibraryRoot(), rootParams)
                }.getOrElse {
                    LibraryResult.ofItem(browseFolderItem(BrowseMediaIds.ROOT, "Phoebe"), rootParams)
                }
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return listenableFuture("onGetItem") {
                val source = AndroidPlaybackRuntime.ensureInstalledNow()
                val item = source.getItem(mediaId)
                if (item == null) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(item, null)
                }
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // Browsing must not stall behind an origin probe, so serve what the catalog has and
            // re-announce this parent once a base binds — until then its thumbs are unusable.
            if (ArtworkOriginHolder.liveOrigin == null) {
                unboundBrowseParents += parentId
                AndroidPlaybackRuntime.warmLiveOrigin(onBound = ::notifyBrowseTreeChanged)
            }
            return listenableFuture("onGetChildren") {
                val source = AndroidPlaybackRuntime.ensureInstalledNow()
                val children = source.getChildren(parentId).paged(page, pageSize)
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            return listenableFuture("onSearch") {
                val source = AndroidPlaybackRuntime.ensureInstalledNow()
                val count = source.searchTracks(query, params?.extras).size
                PhoebeLog.d(TAG) { "onSearch package=${browser.packageName} query=$query count=$count" }
                if (query.isNotBlank()) {
                    session.notifySearchResultChanged(browser, query, count, params)
                }
                LibraryResult.ofVoid()
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != LikeTrackAction &&
                customCommand.customAction != UnlikeTrackAction
            ) {
                return immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            return listenableFuture("onCustomCommand:${customCommand.customAction}") {
                val track = currentTrackForLike()
                if (track == null) {
                    PhoebeLog.d(TAG) { "like ignored: no current track resolved" }
                    return@listenableFuture SessionResult(SessionError.ERROR_BAD_VALUE)
                }
                if (!AndroidPlaybackRuntime.isLikeAvailable(track)) {
                    PhoebeLog.d(TAG) { "like ignored: unavailable for ${track.id}" }
                    return@listenableFuture SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                }
                // Await the catalog mutation so the heart reflects the post-toggle state.
                // Local MediaSession ticks no longer call updateLikeButton (they flash AA).
                AndroidPlaybackRuntime.toggleLikedTrack(track)
                // Force a preference refresh even when track id is unchanged.
                lastLikeButtonTrackId = null
                lastLikeButtonLiked = null
                updateLikeButton(track)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return listenableFuture("onGetSearchResult") {
                val source = AndroidPlaybackRuntime.ensureInstalledNow()
                val items = source.searchTracks(query, params?.extras)
                    .map { browseTrackItem(it) }
                    .paged(page, pageSize)
                PhoebeLog.d(TAG) {
                    "onGetSearchResult package=${browser.packageName} query=$query page=$page pageSize=$pageSize count=${items.size}"
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            if (mediaItems.isInAppPlaybackQueue()) {
                return immediateFuture(mediaItems)
            }
            return listenableFuture("onAddMediaItems") {
                val source = AndroidPlaybackRuntime.ensureInstalledNow()
                AndroidPlaybackRuntime.ensureLiveOriginNow()
                source.resolveTracks(mediaItems).map { playbackMediaItem(it) }.requireBoundUris()
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            if (mediaItems.isInAppPlaybackQueue()) {
                return immediateFuture(MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
            }
            return listenableFuture("onSetMediaItems") {
                val source = AndroidPlaybackRuntime.ensureInstalledNow()
                // Bind an origin *before* any URI is built. The in-app player waits on
                // PlaybackOriginResolver for exactly this; Android Auto had no equivalent, so a
                // car-only process handed ExoPlayer `/library/parts/...` and it reported
                // "Source error".
                AndroidPlaybackRuntime.ensureLiveOriginNow()
                PhoebeLog.d(TAG) {
                    "onSetMediaItems package=${controller.packageName} count=${mediaItems.size} item=${mediaItems.firstOrNull()?.debugSummary()}"
                }
                // Voice requests carry a search query and no resolvable media id, so try the
                // search path first: expanding them only burns a lookup that cannot succeed.
                if (mediaItems.isVoiceSearchRequest()) {
                    return@listenableFuture resolveSearchMediaItems(
                        source,
                        mediaItems,
                        startIndex,
                        startPositionMs,
                    ) ?: throw UnsupportedOperationException(
                        "No results for voice search \"${mediaItems.first().requestMetadata.searchQuery}\".",
                    )
                }
                val expanded = expandMediaItems(source, mediaItems, startIndex)
                if (expanded != null) {
                    // Validate before adopting: a queue the app mirrors but the player cannot
                    // open would leave the in-app UI showing a track that never starts.
                    val items = expanded.items.requireBoundUris()
                    val tracks = expanded.tracks
                    if (tracks.isNotEmpty()) {
                        AndroidPlaybackBridge.onAdoptQueue?.invoke(
                            tracks,
                            expanded.startIndex.coerceIn(tracks.indices),
                            true,
                        )
                    }
                    MediaItemsWithStartPosition(items, expanded.startIndex, startPositionMs)
                } else {
                    val tracks = source.resolveTracks(mediaItems)
                    if (tracks.isEmpty()) {
                        throw UnsupportedOperationException("No playable media items resolved for request.")
                    }
                    val resolved = tracks.map { playbackMediaItem(it) }.requireBoundUris()
                    AndroidPlaybackBridge.onAdoptQueue?.invoke(
                        tracks,
                        startIndex.coerceIn(tracks.indices),
                        true,
                    )
                    MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AndroidPlaybackRuntime.ensureInstalled()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .build()
            .apply { setSmallIcon(resolvePhoebeNotificationIcon()) }
        setMediaNotificationProvider(notificationProvider)
        val player = AndroidPlaybackDiagnostics.newPlayerBuilder(this, PlaybackEnginePath.Media3)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { it.applyPhoebeAudioOffloadPreference() }
        val sessionPlayer = CastMediaSessionPlayer(player)
        AndroidPlaybackBridge.onCastMediaSessionState = { state ->
            sessionPlayer.updateCastState(state)
            updateLikeButton(state?.track)
        }
        AndroidPlaybackBridge.onLocalMediaSessionState = { state ->
            sessionPlayer.updateLocalState(state)
            // Like button is refreshed from media-item transitions / explicit track publishes,
            // not on every position tick — setCustomLayout flashes wide-screen Android Auto.
        }
        AndroidPlaybackBridge.onLikeStateMayHaveChanged = {
            lastLikeButtonTrackId = null
            lastLikeButtonLiked = null
            updateLikeButton()
        }
        AndroidPlaybackBridge.onCurrentTrackChanged = { track ->
            lastLikeButtonTrackId = null
            lastLikeButtonLiked = null
            updateLikeButton(track)
            updateSessionTransportExtras(track)
            syncRadioNowPlaying(track)
            armRadioStartupTimeout(track)
        }
        AndroidPlaybackBridge.attachServicePlayer(player, servicePlayerListener)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, sessionPlayer, librarySessionCallback)
            .setSessionActivity(openAppIntent)
            .setBitmapLoader(AndroidPlaybackHttp.sessionBitmapLoader(this))
            .setCustomLayout(likeButtonLayout(currentTrackHintForLike()))
            .setMediaButtonPreferences(likeButtonLayout(currentTrackHintForLike()))
            .build()
            .also { session ->
                // Custom heart preferences otherwise steal skip slots in the legacy PlaybackState
                // Android Auto reads — reserve them so next/prev (and the seek bar layout) stay.
                session.setSessionExtras(
                    Bundle().apply {
                        putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, true)
                        putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, true)
                    },
                )
                updateLikeButton()
            }
        serviceScope.launch {
            runCatching { AndroidPlaybackRuntime.ensureInstalledNow() }
            AndroidPlaybackRuntime.likedSongsMembershipFlow()?.collect {
                lastLikeButtonTrackId = null
                lastLikeButtonLiked = null
                updateLikeButton()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            playFromSearchIntent(intent)
        }
        return result
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaLibrarySession?.player?.let { player ->
            AndroidPlaybackBridge.onCastMediaSessionState = null
            AndroidPlaybackBridge.onLocalMediaSessionState = null
            AndroidPlaybackBridge.onLikeStateMayHaveChanged = null
            AndroidPlaybackBridge.onCurrentTrackChanged = null
            AndroidPlaybackBridge.detachServicePlayer(servicePlayerListener)
            player.release()
        }
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        super.onDestroy()
    }

    private suspend fun expandMediaItems(
        source: CatalogBrowseSource,
        mediaItems: List<MediaItem>,
        startIndex: Int,
    ): ExpandedPlaybackItems? =
        expandPlaybackMediaItems(source, mediaItems, startIndex)

    private suspend fun resolveSearchMediaItems(
        source: CatalogBrowseSource,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition? {
        if (mediaItems.size != 1) return null
        val request = mediaItems.first().requestMetadata
        val query = request.searchQuery?.trim().orEmpty()
        val extras = request.extras
        if (!isSearchRequest(query, extras)) return null

        val tracks = source.searchTracks(query, extras)
        if (tracks.isEmpty()) return null

        val items = tracks.map { playbackMediaItem(it) }.requireBoundUris()
        val resolvedStartIndex = startIndex.takeIf { it in items.indices } ?: 0
        AndroidPlaybackBridge.onAdoptQueue?.invoke(tracks, resolvedStartIndex, true)
        return MediaItemsWithStartPosition(items, resolvedStartIndex, startPositionMs)
    }

    private fun playFromSearchIntent(intent: Intent) {
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val extras = intent.extras?.let(::Bundle)
        serviceScope.launch {
            val source = withContext(Dispatchers.Default) {
                AndroidPlaybackRuntime.ensureInstalledNow()
            }
            AndroidPlaybackRuntime.ensureLiveOriginNow()
            val tracks = withContext(Dispatchers.IO) {
                source.searchTracks(query, extras)
            }
            PhoebeLog.d(TAG) { "playFromSearchIntent query=$query count=${tracks.size}" }
            if (tracks.isEmpty()) return@launch

            val items = runCatching { tracks.map { playbackMediaItem(it) }.requireBoundUris() }
                .getOrElse { error ->
                    PhoebeLog.d(TAG) { "playFromSearchIntent query=$query unplayable: ${error.message}" }
                    return@launch
                }
            AndroidPlaybackBridge.onAdoptQueue?.invoke(tracks, 0, true)
            mediaLibrarySession?.player?.run {
                setMediaItems(items, 0, C.TIME_UNSET)
                prepare()
                play()
            }
        }
    }

    private suspend fun currentTrackForLike(): Track? {
        AndroidPlaybackBridge.currentTrack?.invoke()?.let { return it }
        val item = mediaLibrarySession?.player?.currentMediaItem ?: return null
        val source = AndroidPlaybackRuntime.ensureInstalledNow()
        source.expandPlayableItem(item).firstOrNull()?.let { return it }
        source.resolveTracks(listOf(item)).firstOrNull()?.let { return it }
        return trackFromMediaItemForLike(item)
    }

    private fun trackFromMediaItemForLike(item: MediaItem): Track? {
        val id = item.mediaId.takeIf { it.isNotBlank() } ?: return null
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return Track(
            id = id,
            title = title,
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            durationMs = metadata.durationMs ?: 0L,
            streamUrl = item.localConfiguration?.uri?.toString().orEmpty(),
            downloadUrl = "",
            thumbUrl = metadata.artworkUri?.toString(),
        )
    }

    private var lastLikeButtonTrackId: String? = null
    private var lastLikeButtonLiked: Boolean? = null
    private val likeButtonMutex = Mutex()
    private val likeButtonGeneration = AtomicInteger(0)

    private fun currentTrackHintForLike(): Track? =
        AndroidPlaybackBridge.currentTrack?.invoke()
            ?: mediaLibrarySession?.player?.currentMediaItem?.let(::trackFromMediaItemForLike)

    private fun updateLikeButton(track: Track? = null) {
        val session = mediaLibrarySession ?: return
        val generation = likeButtonGeneration.incrementAndGet()
        serviceScope.launch {
            likeButtonMutex.withLock {
                if (generation != likeButtonGeneration.get()) return@withLock
                // Prefer the live app queue track over a stale MediaItem hint from an older skip.
                val resolved = AndroidPlaybackBridge.currentTrack?.invoke()
                    ?: track
                    ?: runCatching { currentTrackForLike() }.getOrNull()
                runCatching { AndroidPlaybackRuntime.ensureLikedSongsLoaded() }
                if (generation != likeButtonGeneration.get()) return@withLock
                // Re-read after ensure — AppState may have advanced during the suspend.
                val current = AndroidPlaybackBridge.currentTrack?.invoke() ?: resolved
                val liked = current?.let { AndroidPlaybackRuntime.isTrackLiked(it) } == true
                if (current?.id == lastLikeButtonTrackId && liked == lastLikeButtonLiked) {
                    return@withLock
                }
                lastLikeButtonTrackId = current?.id
                lastLikeButtonLiked = liked
                val layout = androidAutoLikeButtonLayout(current, liked = liked)
                publishLikeButtonLayout(session, layout)
            }
        }
    }

    private fun updateSessionTransportExtras(track: Track?) {
        val session = mediaLibrarySession ?: return
        val isRadio = track?.id?.startsWith("radio:") == true
        session.setSessionExtras(
            Bundle().apply {
                // Catalog tracks reserve skip slots so the heart does not steal them.
                // Radio hides skip entirely — do not reserve empty chrome.
                if (!isRadio) {
                    putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, true)
                    putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, true)
                }
            },
        )
    }

    private fun syncRadioNowPlaying(track: Track?) {
        radioNowPlayingJob?.cancel()
        if (track?.id?.startsWith("radio:") != true) return
        // Prefer the full app-queue track, which retains the station's configured metadata source
        // (BBC RMS / KEXP / ICY). The MediaItem hint rebuilt from session metadata drops it, which
        // would otherwise fall back to default ICY/HLS probing for stations with a custom endpoint.
        val authoritative = AndroidPlaybackBridge.currentTrack?.invoke()
            ?.takeIf { it.id == track.id }
            ?: track
        val stationName = authoritative.album.ifBlank { authoritative.title }
        radioNowPlayingJob = serviceScope.launch {
            while (isActive) {
                val deps = runCatching {
                    AndroidPlaybackRuntime.ensureInstalledNow()
                    AndroidPlaybackRuntime.radioNowPlayingRepository()
                }.getOrNull()
                val metadata = deps?.let { repo ->
                    runCatching { repo.resolve(authoritative) }.getOrNull()
                }
                if (metadata != null && metadata.hasTrack) {
                    applyRadioNowPlayingMetadata(authoritative, stationName, metadata)
                }
                delay(RadioNowPlayingRefreshMs)
            }
        }
    }

    private fun applyRadioNowPlayingMetadata(
        stationTrack: Track,
        stationName: String,
        metadata: RadioNowPlayingMetadata,
    ) {
        val player = mediaLibrarySession?.player ?: return
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        if (current.mediaId != stationTrack.id && !current.mediaId.startsWith("radio:")) return
        val title = metadata.title.ifBlank { metadata.rawTitle ?: stationTrack.title }
        val artist = metadata.artist.ifBlank { stationTrack.artist }
        val updated = current.buildUpon()
            .setMediaMetadata(
                current.mediaMetadata.buildUpon()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtist(artist)
                    .setAlbumArtist(artist)
                    .setSubtitle(artist)
                    .setAlbumTitle(stationName)
                    .setDescription(listOf(artist, stationName).filter { it.isNotBlank() }.distinct().joinToString(" - "))
                    .build(),
            )
            .build()
        if (index in 0 until player.mediaItemCount) {
            player.replaceMediaItem(index, updated)
        }
    }

    private fun armRadioStartupTimeout(track: Track?) {
        clearRadioStartupTimeout()
        if (track?.id?.startsWith("radio:") != true) return
        val trackId = track.id
        radioStartupTimeoutJob = serviceScope.launch {
            delay(InternetRadioStartupTimeoutMs)
            val player = mediaLibrarySession?.player ?: return@launch
            val currentId = player.currentMediaItem?.mediaId
            if (currentId != trackId && AndroidPlaybackBridge.currentTrack?.invoke()?.id != trackId) {
                return@launch
            }
            if (player.playbackState == Player.STATE_READY && player.playWhenReady) return@launch
            if (player.playbackState != Player.STATE_BUFFERING && !player.isLoading) return@launch
            PhoebeLog.d(TAG) { "radio startup timeout for $trackId" }
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun clearRadioStartupTimeout() {
        radioStartupTimeoutJob?.cancel()
        radioStartupTimeoutJob = null
    }

    private fun publishLikeButtonLayout(session: MediaLibrarySession, layout: List<CommandButton>) {
        session.setCustomLayout(layout)
        session.setMediaButtonPreferences(layout)
        // Android Auto often adopts preferences from onConnect; push per-controller so a later
        // filled/unfilled swap actually reaches the already-connected DHU session.
        session.connectedControllers.forEach { controller ->
            session.setCustomLayout(controller, layout)
            session.setMediaButtonPreferences(controller, layout)
        }
    }

    /** Re-announce every parent served while thumbs were still unbindable. */
    private fun notifyBrowseTreeChanged() {
        val session = mediaLibrarySession ?: return
        val parents = unboundBrowseParents.toList() + BrowseMediaIds.ROOT
        unboundBrowseParents.clear()
        serviceScope.launch {
            val source = runCatching { AndroidPlaybackRuntime.ensureInstalledNow() }.getOrNull()
                ?: return@launch
            parents.distinct().forEach { parentId ->
                val count = runCatching { source.getChildren(parentId).size }.getOrNull() ?: return@forEach
                session.notifyChildrenChanged(parentId, count, null)
            }
        }
    }

    private fun handledQueueNavigationCommand(playerCommand: Int): Int? {
        val player = mediaLibrarySession?.player
        return handleExternalQueueNavigationCommand(
            playerCommand = playerCommand,
            isCastActive = AndroidPlaybackBridge.isCastActive?.invoke() == true,
            hasNextTrack = AndroidPlaybackBridge.hasNextTrack?.invoke() == true,
            hasPreviousTrack = AndroidPlaybackBridge.hasPreviousTrack?.invoke() == true,
            onSkipNext = AndroidPlaybackBridge.onSkipNext,
            onSkipPrevious = AndroidPlaybackBridge.onSkipPrevious,
            onCastSkipNext = AndroidPlaybackBridge.onCastSkipNext,
            onCastSkipPrevious = AndroidPlaybackBridge.onCastSkipPrevious,
            platformHasNext = player?.hasNextMediaItem() == true,
            platformHasPrevious = player?.hasPreviousMediaItem() == true,
        )
    }

    private fun List<MediaItem>.isInAppPlaybackQueue(): Boolean =
        isNotEmpty() && all { it.requestMetadata.extras?.getBoolean(InAppPlaybackExtra, false) == true }

    /** True for Assistant/Android Auto voice requests, which arrive as a lone query-bearing item. */
    private fun List<MediaItem>.isVoiceSearchRequest(): Boolean {
        val request = singleOrNull()?.requestMetadata ?: return false
        return isSearchRequest(request.searchQuery?.trim().orEmpty(), request.extras)
    }

    private fun MediaItem.debugSummary(): String {
        val extras = requestMetadata.extras
        return "mediaId=$mediaId search=${requestMetadata.searchQuery} title=${mediaMetadata.title} " +
            "artist=${mediaMetadata.artist} extras=${extras?.keySet()?.joinToString()}"
    }

    private fun isSearchRequest(query: String, extras: Bundle?): Boolean =
        query.isNotBlank() ||
            extras?.containsKey(SearchManager.QUERY) == true ||
            extras?.containsKey(MediaStore.EXTRA_MEDIA_FOCUS) == true ||
            extras?.containsKey(MediaStore.EXTRA_MEDIA_TITLE) == true ||
            extras?.containsKey(MediaStore.EXTRA_MEDIA_ARTIST) == true ||
            extras?.containsKey(MediaStore.EXTRA_MEDIA_ALBUM) == true ||
            extras?.containsKey(MediaStoreSearchExtras.EXTRA_MEDIA_PLAYLIST) == true ||
            extras?.containsKey(MediaStore.EXTRA_MEDIA_GENRE) == true

    private fun <T> List<T>.paged(page: Int, pageSize: Int): List<T> {
        if (page < 0) return emptyList()
        if (pageSize <= 0) return this
        val fromIndex = page * pageSize
        if (fromIndex >= size) return emptyList()
        return subList(fromIndex, minOf(fromIndex + pageSize, size))
    }

    private companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val InternetRadioStartupTimeoutMs = 30_000L
        private const val RadioNowPlayingRefreshMs = 30_000L
        private val LikeTrackCommand = SessionCommand(LikeTrackAction, Bundle.EMPTY)
        private val UnlikeTrackCommand = SessionCommand(UnlikeTrackAction, Bundle.EMPTY)

        private fun likeButtonLayout(track: Track? = null): List<CommandButton> =
            androidAutoLikeButtonLayout(track)

        private fun androidAutoRootParams(
            @Suppress("UNUSED_PARAMETER") incoming: MediaLibraryService.LibraryParams?,
        ): MediaLibraryService.LibraryParams {
            val extras = Bundle().apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                )
            }
            return MediaLibraryService.LibraryParams.Builder()
                .setExtras(extras)
                .build()
        }
    }
}

internal const val LikeTrackAction = "com.phoebe.app.action.LIKE_TRACK"
internal const val UnlikeTrackAction = "com.phoebe.app.action.UNLIKE_TRACK"

/** Heart control for Android Auto Now Playing / song detail. */
internal fun androidAutoLikeButtonLayout(
    track: Track? = null,
    liked: Boolean? = null,
): List<CommandButton> {
    // Product: hide the heart entirely while internet radio is playing.
    if (track?.id?.startsWith("radio:") == true) return emptyList()
    val isLiked = liked ?: (track?.let { AndroidPlaybackRuntime.isTrackLiked(it) } == true)
    // Liked vs unliked use *different* session commands. Android Auto keys legacy custom actions
    // by action string and often keeps the previous icon when only ICON_HEART_* changes.
    val action = if (isLiked) UnlikeTrackAction else LikeTrackAction
    return listOf(
        CommandButton.Builder(
            if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
        )
            .setDisplayName(if (isLiked) "Unlike" else "Like")
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            // Must stay enabled: Media3 drops disabled buttons from the legacy PlaybackState that
            // Android Auto / DHU render. Availability is enforced in onCustomCommand instead.
            .setEnabled(true)
            // Overflow keeps Next/Previous in their primary slots; with skip-slot reservation the
            // heart still lands as a Now Playing custom action on Android Auto.
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )
}

/**
 * Fail the session request instead of handing ExoPlayer a URI it cannot open.
 *
 * A host-less `/library/parts/...` survives every emptiness check but has no authority, so
 * ExoPlayer reinterprets it as a local file and surfaces a bare "Source error" in the car with
 * nothing in the log tying it to a missing origin. A failed future gives the user a real message.
 */
internal fun List<MediaItem>.requireBoundUris(): List<MediaItem> {
    val unbound = firstOrNull { item ->
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        uri.isBlank() || isUnboundServerPath(uri)
    } ?: return this
    throw UnsupportedOperationException(
        "Music server is unreachable; no origin bound for \"${unbound.mediaMetadata.title}\".",
    )
}

internal data class ExpandedPlaybackItems(
    val items: List<MediaItem>,
    val tracks: List<Track>,
    val startIndex: Int,
)

internal suspend fun expandPlaybackMediaItems(
    source: CatalogBrowseSource,
    mediaItems: List<MediaItem>,
    startIndex: Int,
): ExpandedPlaybackItems? {
    if (mediaItems.isEmpty()) return null
    val selectedIndex = startIndex.takeIf { it in mediaItems.indices } ?: 0
    val item = mediaItems[selectedIndex]
    val shouldExpand = mediaItems.size == 1 || item.mediaId.shouldExpandFromPagedBrowseSelection()
    if (!shouldExpand) return null

    val tracks = source.expandPlayableItem(item)
    if (tracks.isEmpty()) return null

    val fallbackStartIndex = if (mediaItems.size == 1) startIndex else 0
    return ExpandedPlaybackItems(
        items = tracks.map { playbackMediaItem(it) },
        tracks = tracks,
        startIndex = source.startIndexForMediaItem(item, tracks, fallbackStartIndex),
    )
}

private fun String.shouldExpandFromPagedBrowseSelection(): Boolean =
    BrowseMediaIds.parseTrackId(this) != null ||
        BrowseMediaIds.parseAlbumPlayId(this) != null ||
        BrowseMediaIds.parsePlaylistPlayId(this) != null ||
        BrowseMediaIds.parsePlaylistShuffleId(this) != null ||
        BrowseMediaIds.parseRadioStationId(this) != null

private fun PlaybackService.resolvePhoebeNotificationIcon(): Int {
    val notificationIcon = resources.getIdentifier("ic_notification", "drawable", packageName)
    if (notificationIcon != 0) return notificationIcon
    val launcherIcon = resources.getIdentifier("ic_launcher", "mipmap", packageName)
    if (launcherIcon != 0) return launcherIcon
    return android.R.drawable.ic_media_play
}

internal fun handleExternalQueueNavigationCommand(
    playerCommand: Int,
    isCastActive: Boolean,
    hasNextTrack: Boolean,
    hasPreviousTrack: Boolean,
    onSkipNext: (() -> Unit)?,
    onSkipPrevious: (() -> Unit)?,
    onCastSkipNext: (() -> Unit)?,
    onCastSkipPrevious: (() -> Unit)?,
    platformHasNext: Boolean = false,
    platformHasPrevious: Boolean = false,
): Int? {
    // When the Media3 playlist already contains the next/previous item, let the session
    // player seek natively. Routing through Phoebe next()/play() rebuilds app state and
    // briefly mutates the MediaSession timeline — on wide-screen Android Auto that kicks
    // the user off the song-detail / Now Playing surface back to browse.
    if (!isCastActive) {
        when (playerCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> if (platformHasNext) return null
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> if (platformHasPrevious) return null
        }
    }

    val handler = when (playerCommand) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        -> if (isCastActive) {
            onCastSkipNext
        } else {
            onSkipNext.takeIf { hasNextTrack }
        }
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> if (isCastActive) {
            onCastSkipPrevious
        } else {
            onSkipPrevious.takeIf { hasPreviousTrack }
        }
        else -> null
    } ?: return null

    handler()
    return SessionResult.RESULT_INFO_SKIPPED
}
