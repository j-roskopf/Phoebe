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
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val servicePlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AndroidPlaybackBridge.updateServicePlayerState()
            AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            AndroidPlaybackBridge.updateServicePlayerState()
            if (playbackState == Player.STATE_ENDED) {
                if (AndroidPlaybackBridge.suppressServiceEndedCallback) return
                AndroidPlaybackBridge.onTrackEnded?.invoke()
            } else {
                AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            AndroidPlaybackBridge.updateServicePlayerState()
            AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            AndroidPlaybackBridge.updateServicePlayerState()
            AndroidPlaybackBridge.onServicePlayerChanged?.invoke()
            updateLikeButton()
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
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setCustomLayout(likeButtonLayout())
                .setMediaButtonPreferences(likeButtonLayout())
                .build()
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
            if (customCommand.customAction != LikeTrackAction) {
                return immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            return listenableFuture("onCustomCommand:$LikeTrackAction") {
                val track = currentTrackForLike() ?: return@listenableFuture SessionResult(SessionError.ERROR_BAD_VALUE)
                if (AndroidPlaybackBridge.isLikeAvailable?.invoke(track) != true) {
                    return@listenableFuture SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                }
                AndroidPlaybackBridge.onToggleLikedTrack?.invoke(track)
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
                source.resolveTracks(mediaItems).map { playbackMediaItem(it) }
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
                    val tracks = expanded.tracks
                    if (tracks.isNotEmpty()) {
                        AndroidPlaybackBridge.onAdoptQueue?.invoke(
                            tracks,
                            expanded.startIndex.coerceIn(tracks.indices),
                            true,
                        )
                    }
                    MediaItemsWithStartPosition(expanded.items, expanded.startIndex, startPositionMs)
                } else {
                    val tracks = source.resolveTracks(mediaItems)
                    if (tracks.isEmpty()) {
                        throw UnsupportedOperationException("No playable media items resolved for request.")
                    }
                    val resolved = tracks.map { playbackMediaItem(it) }
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
            updateLikeButton(state?.track)
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
            .setCustomLayout(likeButtonLayout())
            .setMediaButtonPreferences(likeButtonLayout())
            .build()
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

        val items = tracks.map { playbackMediaItem(it) }
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
            val tracks = withContext(Dispatchers.IO) {
                source.searchTracks(query, extras)
            }
            PhoebeLog.d(TAG) { "playFromSearchIntent query=$query count=${tracks.size}" }
            if (tracks.isEmpty()) return@launch

            val items = tracks.map { playbackMediaItem(it) }
            AndroidPlaybackBridge.onAdoptQueue?.invoke(tracks, 0, true)
            mediaLibrarySession?.player?.run {
                setMediaItems(items, 0, C.TIME_UNSET)
                prepare()
                play()
            }
        }
    }

    private suspend fun currentTrackForLike(): Track? {
        val item = mediaLibrarySession?.player?.currentMediaItem ?: return null
        val source = AndroidPlaybackRuntime.ensureInstalledNow()
        return source.expandPlayableItem(item).firstOrNull()
            ?: source.resolveTracks(listOf(item)).firstOrNull()
    }

    private fun updateLikeButton(track: Track? = null) {
        val session = mediaLibrarySession ?: return
        serviceScope.launch {
            val resolved = track ?: runCatching { currentTrackForLike() }.getOrNull()
            val layout = likeButtonLayout(resolved)
            session.setCustomLayout(layout)
            session.setMediaButtonPreferences(layout)
        }
    }

    private fun handledQueueNavigationCommand(playerCommand: Int): Int? =
        handleExternalQueueNavigationCommand(
            playerCommand = playerCommand,
            isCastActive = AndroidPlaybackBridge.isCastActive?.invoke() == true,
            hasNextTrack = AndroidPlaybackBridge.hasNextTrack?.invoke() == true,
            hasPreviousTrack = AndroidPlaybackBridge.hasPreviousTrack?.invoke() == true,
            onSkipNext = AndroidPlaybackBridge.onSkipNext,
            onSkipPrevious = AndroidPlaybackBridge.onSkipPrevious,
            onCastSkipNext = AndroidPlaybackBridge.onCastSkipNext,
            onCastSkipPrevious = AndroidPlaybackBridge.onCastSkipPrevious,
        )

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
        private const val LikeTrackAction = "com.phoebe.app.action.LIKE_TRACK"
        private val LikeTrackCommand = SessionCommand(LikeTrackAction, Bundle.EMPTY)

        private fun likeButtonLayout(track: Track? = null): List<CommandButton> {
            val enabled = track?.let { AndroidPlaybackBridge.isLikeAvailable?.invoke(it) } ?: false
            val liked = track?.let { AndroidPlaybackBridge.isTrackLiked?.invoke(it) } ?: false
            return listOf(
                CommandButton.Builder(
                    if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
                )
                    .setDisplayName(if (liked) "Unlike" else "Like")
                    .setSessionCommand(LikeTrackCommand)
                    .setEnabled(enabled)
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }

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
        BrowseMediaIds.parsePlaylistShuffleId(this) != null

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
): Int? {
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
