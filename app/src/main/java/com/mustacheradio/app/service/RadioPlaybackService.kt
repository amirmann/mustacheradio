package com.mustacheradio.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.mustacheradio.app.MainActivity
import com.mustacheradio.app.NowPlayingManager
import com.mustacheradio.app.PlayHistoryManager
import com.mustacheradio.app.R
import com.mustacheradio.app.RadioStation
import com.mustacheradio.app.Stations
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

class RadioPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "RadioPlaybackService"
        const val CHANNEL_ID    = "mustacheradio_playback_channel"
        const val NOTIFICATION_ID = 1
        const val MEDIA_ROOT_ID = "root"
        const val LIST_ROOT_ID  = "list"
        const val GRID_ROOT_ID  = "grid"

        private const val CONTENT_STYLE_PLAYABLE_KEY  = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_BROWSABLE_KEY = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var notificationManager: PlayerNotificationManager
    private lateinit var playHistoryManager: PlayHistoryManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Last ICY StreamTitle received from the currently playing stream
    private var lastIcyTitle: String? = null

    // Extracted so it can be re-attached after swapping exoPlayer for a promoted warm player
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            if (isPlaying) {
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            } else if (lastRequestedMediaId != null) {
                // Always report PAUSED when a station was selected — covers audio-focus
                // loss from other apps (e.g. YouTube Music in the car) even when ExoPlayer
                // transitions to IDLE instead of just setting playWhenReady=false.
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
        }

        override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
            Log.e(TAG, "Player error [${error.errorCode}]: ${error.message}")
        }

        override fun onMetadata(metadata: Metadata) {
            if (!isWhatsPlayingEnabled()) return
            for (i in 0 until metadata.length()) {
                val entry = metadata[i]
                if (entry is IcyInfo) {
                    val title = entry.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    if (title == lastIcyTitle) return
                    lastIcyTitle = title
                    Log.d(TAG, "ICY metadata: $title")
                    lastRequestedMediaId?.let { id ->
                        Stations.BY_ID[id]?.let { station ->
                            updateSessionMetadata(station, id, nowPlayingText = title)
                        }
                    }
                    return
                }
            }
        }
    }

    // Tracks which station was last requested so we can resume it when a Cast session ends
    private var lastRequestedMediaId: String? = null

    private lateinit var prerollWarmer: PrerollWarmer

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d(TAG, "Cast session started")
            lastRequestedMediaId?.let { id -> Stations.BY_ID[id]?.let { playOnCast(session, it, id) } }
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.d(TAG, "Cast session resumed")
            lastRequestedMediaId?.let { id -> Stations.BY_ID[id]?.let { playOnCast(session, it, id) } }
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.d(TAG, "Cast session ended — resuming local playback")
            lastRequestedMediaId?.let { playLocally(it) }
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // Keep MediaSession subtitle (lock screen / notification) in sync with now-playing cache
        NowPlayingManager.serviceListeners["service"] = { stationId, text ->
            if (stationId == lastRequestedMediaId) {
                Stations.BY_ID[stationId]?.let { station ->
                    updateSessionMetadata(station, stationId, nowPlayingText = text)
                }
            }
        }

        playHistoryManager = PlayHistoryManager(this)
        createNotificationChannel()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttrs, /* handleAudioFocus= */ true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            volume = 1.0f
        }
        exoPlayer.addListener(playerListener)

        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(mediaSessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        // Publish an initial state so Android Auto shows skip buttons immediately
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)

        try {
            CastContext.getSharedInstance(this)
                .sessionManager
                .addSessionManagerListener(castSessionListener, CastSession::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Cast not available: ${e.message}")
        }

        setupNotification()

        // Silently burn through StreamTheWorld pre-roll ads so the user never hears them.
        // 3 s delay lets the service settle before opening extra network connections.
        prerollWarmer = PrerollWarmer(this)
        mainHandler.postDelayed({
            prerollWarmer.warmUpIfNeeded { baseUrl -> buildStreamUrl(baseUrl) }
        }, 3_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildInitialNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        NowPlayingManager.serviceListeners.remove("service")
        try {
            CastContext.getSharedInstance(this)
                .sessionManager
                .removeSessionManagerListener(castSessionListener, CastSession::class.java)
        } catch (e: Exception) { /* Cast not available */ }
        prerollWarmer.release()
        mediaSession.release()
        exoPlayer.release()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaSession callback
    // ─────────────────────────────────────────────────────────────────────────

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = playStation(mediaId)

        override fun onPlay() {
            val castSession = getCastSession()
            if (castSession?.isConnected == true) {
                val client = castSession.remoteMediaClient
                val playerState = client?.mediaStatus?.playerState ?: MediaStatus.PLAYER_STATE_IDLE
                if (playerState == MediaStatus.PLAYER_STATE_PAUSED) {
                    client?.play()
                } else {
                    // Live stream was stopped — reload it
                    lastRequestedMediaId?.let { id ->
                        Stations.BY_ID[id]?.let { playOnCast(castSession, it, id) }
                    }
                }
            } else {
                exoPlayer.playWhenReady = true
            }
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        }

        override fun onPause() {
            val castSession = getCastSession()
            if (castSession?.isConnected == true) {
                // Live streams don't support Cast pause; stop so play can reload cleanly
                castSession.remoteMediaClient?.stop()
            } else {
                exoPlayer.playWhenReady = false
            }
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onStop() {
            val castSession = getCastSession()
            if (castSession?.isConnected == true) {
                castSession.remoteMediaClient?.stop()
            } else {
                exoPlayer.stop()
            }
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        }

        override fun onSkipToPrevious() = skipToAdjacentStation(forward = false)
        override fun onSkipToNext()     = skipToAdjacentStation(forward = true)

        // Some head units map the rocker to rewind / fast-forward
        override fun onRewind()       = skipToAdjacentStation(forward = false)
        override fun onFastForward()  = skipToAdjacentStation(forward = true)

        // Some head units use queue-item skip
        override fun onSkipToQueueItem(id: Long) {
            val sorted = getSortedStationIds()
            playStation(sorted[id.toInt().coerceIn(0, sorted.lastIndex)])
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Playback
    // ─────────────────────────────────────────────────────────────────────────

    private fun playStation(mediaId: String?) {
        if (mediaId == null) { Log.e(TAG, "playStation: null mediaId"); return }
        val id = mediaId
        val station = Stations.BY_ID[id] ?: run { Log.e(TAG, "Unknown station: $id"); return }

        Log.d(TAG, "playStation: ${station.name}")
        lastRequestedMediaId = id
        lastIcyTitle = null   // clear stale metadata from previous station

        playHistoryManager.recordPlay(id)
        mainHandler.post {
            notifyChildrenChanged(LIST_ROOT_ID)
            notifyChildrenChanged(GRID_ROOT_ID)
        }
        mainHandler.postDelayed({
            notifyChildrenChanged(LIST_ROOT_ID)
            notifyChildrenChanged(GRID_ROOT_ID)
        }, 300)

        val castSession = getCastSession()
        if (castSession?.isConnected == true) {
            playOnCast(castSession, station, id)
        } else {
            playLocally(id)
        }
    }

    private fun playOnCast(castSession: CastSession, station: RadioStation, id: String) {
        Log.d(TAG, "Casting: ${station.name}")
        // Remove listener BEFORE stopping so the async onIsPlayingChanged(false) callback
        // doesn't overwrite STATE_PLAYING after we set it below.
        exoPlayer.removeListener(playerListener)
        exoPlayer.stop()

        val contentType = station.castContentType ?: when {
            station.streamUrl.contains(".m3u8")                  -> "application/x-mpegurl"
            station.streamUrl.endsWith(".aac", ignoreCase = true) -> "audio/aac"
            else                                                   -> "audio/mpeg"
        }
        val castMeta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, station.name)
            putString(MediaMetadata.KEY_SUBTITLE, station.description)
            putString(MediaMetadata.KEY_ALBUM_ARTIST, "Mustache Radio")
        }
        val mediaInfo = MediaInfo.Builder(buildStreamUrl(station.streamUrl))
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(castMeta)
            .build()

        val remoteClient = castSession.remoteMediaClient ?: run {
            Log.w(TAG, "remoteMediaClient is null, cannot cast")
            return
        }

        // Stop before loading so the Cast receiver is in a clean state.
        // The Cast protocol queues commands sequentially, so the receiver will process
        // STOP then LOAD in order — no callback needed.
        // setPlayPosition is intentionally omitted: live streams don't support seeking.
        remoteClient.stop()
        @Suppress("DEPRECATION")
        remoteClient.load(mediaInfo, MediaLoadOptions.Builder().setAutoplay(true).build())

        updateSessionMetadata(station, id)
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
    }

    private fun playLocally(id: String) {
        val station = Stations.BY_ID[id] ?: return

        // For StreamTheWorld stations try to reuse the warm-up player that has already
        // streamed past the pre-roll.  Reusing the same HTTP connection means the server
        // never sends a new commercial.
        val promoted = if (PrerollWarmer.STATIONS.containsKey(id)) {
            prerollWarmer.promotePlayer(id) { baseUrl -> buildStreamUrl(baseUrl) }
        } else null

        if (promoted != null) {
            Log.d(TAG, "[$id] Promoted warm player — no commercial")
            exoPlayer.removeListener(playerListener)
            exoPlayer.stop()
            exoPlayer.release()
            exoPlayer = promoted
            exoPlayer.addListener(playerListener)
            // Warm-up players are created with handleAudioFocus=false to avoid
            // interfering with the main player. Re-enable it now that this IS the main player.
            val audioAttrs = com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            exoPlayer.setAudioAttributes(audioAttrs, /* handleAudioFocus= */ true)
            exoPlayer.volume = 1.0f
            notificationManager.setPlayer(exoPlayer)
            updateSessionMetadata(station, id)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            return
        }

        // No ready warm player — cancel any still-warming player and open a fresh connection
        prerollWarmer.cancelIfWarming(id)

        try {
            val streamUrl = buildStreamUrl(station.streamUrl)
            Log.d(TAG, "[$id] Local stream: $streamUrl")
            // Re-attach listener in case it was removed by a previous playOnCast call
            exoPlayer.removeListener(playerListener)
            exoPlayer.addListener(playerListener)
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateSessionMetadata(station, id)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing $id locally", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaBrowserServiceCompat — Android Auto
    // ─────────────────────────────────────────────────────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        val extras = Bundle().apply {
            putInt(CONTENT_STYLE_BROWSABLE_KEY, CONTENT_STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE_KEY,  CONTENT_STYLE_LIST)
        }
        return BrowserRoot(MEDIA_ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        when (parentId) {
            MEDIA_ROOT_ID -> {
                items += browsableItem(LIST_ROOT_ID, "List",  "Stations — sorted by last played", CONTENT_STYLE_LIST)
                items += browsableItem(GRID_ROOT_ID, "Grid",  "Logos — sorted by last played",    CONTENT_STYLE_GRID)
            }

            LIST_ROOT_ID, GRID_ROOT_ID -> {
                getSortedStationIds().mapNotNull { Stations.BY_ID[it] }.forEach { station ->
                    val bitmap = BitmapFactory.decodeResource(resources, station.iconRes)
                    items += MediaBrowserCompat.MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId(station.id)
                            .setTitle(station.name)
                            .setSubtitle(station.description)
                            .setMediaUri(Uri.parse(station.streamUrl))
                            .setIconBitmap(bitmap)
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                }
            }
        }

        result.sendResult(items)
    }

    private fun browsableItem(
        id: String,
        title: String,
        subtitle: String,
        style: Int
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply { putInt(CONTENT_STYLE_PLAYABLE_KEY, style) }
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setExtras(extras)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun getSortedStationIds(): List<String> =
        Stations.ALL
            .sortedByDescending { playHistoryManager.getLastPlayedTimestamp(it.id) }
            .map { it.id }

    private fun skipToAdjacentStation(forward: Boolean) {
        val ids = Stations.ALL.map { it.id }
        val currentIndex = ids.indexOf(getCurrentMediaId())
        val nextIndex = when {
            currentIndex < 0 -> if (forward) 0 else ids.lastIndex
            forward          -> (currentIndex + 1) % ids.size
            else             -> if (currentIndex == 0) ids.lastIndex else currentIndex - 1
        }
        playStation(ids[nextIndex])
    }

    private fun getCurrentMediaId(): String? =
        mediaSession.controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)

    private fun getCastSession(): CastSession? = try {
        CastContext.getSharedInstance(this).sessionManager.currentCastSession
    } catch (e: Exception) { null }

    private fun getOrCreateStreamUuid(): String {
        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        return prefs.getString("stream_uuid", null) ?: run {
            val uuid = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("stream_uuid", uuid).apply()
            uuid
        }
    }

    private fun buildStreamUrl(url: String): String =
        if (url.contains("streamtheworld.com")) {
            val sep = if ('?' in url) "&" else "?"
            "$url${sep}uuid=${getOrCreateStreamUuid()}"
        } else url

    private fun playbackActions(): Long =
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_STOP or
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_REWIND or
        PlaybackStateCompat.ACTION_FAST_FORWARD

    private fun updatePlaybackState(state: Int) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(playbackActions())
                .build()
        )
    }

    private fun isWhatsPlayingEnabled(): Boolean =
        getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("whats_playing", false)

    /**
     * Update MediaSession metadata.  When [nowPlayingText] is provided (and the
     * "What's Playing" feature is on) it replaces the station description in the
     * subtitle slot — this is what Android Auto and the notification display.
     */
    private fun updateSessionMetadata(
        station: RadioStation,
        id: String,
        nowPlayingText: String? = null
    ) {
        val bitmap: Bitmap = BitmapFactory.decodeResource(resources, station.iconRes)
        // Prefer the provided text, then fall back to the shared cache; use description if feature is off
        val cachedNp = if (isWhatsPlayingEnabled()) nowPlayingText ?: NowPlayingManager.sharedCache[id] else null
        val subtitle = cachedNp ?: station.description
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,        station.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,       subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,     id)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,    bitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                .build()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildInitialNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mustache Radio")
            .setContentText("Radio service is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Radio Playback", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Controls for radio playback" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setupNotification() {
        notificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence =
                    mediaSession.controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Mustache Radio"

                override fun createCurrentContentIntent(player: Player): PendingIntent? =
                    PendingIntent.getActivity(
                        this@RadioPlaybackService, 0,
                        Intent(this@RadioPlaybackService, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                override fun getCurrentContentText(player: Player): CharSequence? =
                    mediaSession.controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): Bitmap? = null
            })
            .build()
            .also {
                it.setMediaSessionToken(mediaSession.sessionToken)
                it.setPlayer(exoPlayer)
            }
    }
}
