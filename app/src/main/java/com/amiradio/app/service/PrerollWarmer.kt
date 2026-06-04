package com.amiradio.app.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.audio.AudioAttributes

/**
 * Silently "burns through" StreamTheWorld pre-roll ads by keeping a muted ExoPlayer
 * alive past the commercial phase, then handing it over to RadioPlaybackService when
 * the user taps the station.  Because we reuse the SAME HTTP connection that already
 * streamed past the ad, the server never serves a second commercial.
 *
 * Lifecycle for each StreamTheWorld station:
 *   1. [WARMING]  Service starts → player connects, volume=0, commercial plays silently.
 *   2. [READY]    After COMMERCIAL_DURATION_MS the commercial is definitely over.
 *                 Player stays alive (muted) for up to MAX_READY_MS.
 *   3. [PROMOTED] User taps the station → promotePlayer() returns the live ExoPlayer.
 *                 A fresh warm-up cycle starts immediately for next time.
 *   4. [EXPIRED]  If the user never taps within MAX_READY_MS, the player is released.
 *                 Next session they may hear a short commercial — acceptable trade-off.
 */
class PrerollWarmer(private val context: Context) {

    companion object {
        private const val TAG = "PrerollWarmer"

        /** How long to wait before the commercial is certainly finished. */
        private const val COMMERCIAL_DURATION_MS = 90_000L

        /**
         * How long to keep a ready player alive waiting for promotion.
         * After this the player is released to avoid burning mobile data indefinitely.
         */
        private const val MAX_READY_MS = 15 * 60 * 1_000L  // 15 minutes

        /** Only StreamTheWorld stations need this treatment. */
        val STATIONS = mapOf(
            "88fm"   to "https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_88.mp3",
            "kanbet" to "https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_BET.mp3"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Players still in the commercial phase (not yet ready)
    private val warmingPlayers = mutableMapOf<String, ExoPlayer>()

    // Players that have already passed the commercial and are ready to be promoted
    private val readyPlayers = mutableMapOf<String, ExoPlayer>()

    // Timer that fires when the commercial phase is over → moves player to readyPlayers
    private val commercialTimers = mutableMapOf<String, Runnable>()

    // Timer that fires after MAX_READY_MS → releases the idle ready player
    private val expiryTimers = mutableMapOf<String, Runnable>()

    // ─────────────────────────────────────────────────────────────────────────

    /** Call once shortly after the service starts. Skips stations already warming/ready. */
    fun warmUpIfNeeded(buildUrl: (String) -> String) {
        STATIONS.forEach { (stationId, baseUrl) ->
            when {
                warmingPlayers.containsKey(stationId) ->
                    Log.d(TAG, "[$stationId] Already warming — skipping")
                readyPlayers.containsKey(stationId) ->
                    Log.d(TAG, "[$stationId] Already ready — skipping")
                else -> {
                    Log.d(TAG, "[$stationId] Starting warm-up")
                    startWarmup(stationId, buildUrl(baseUrl))
                }
            }
        }
    }

    /**
     * Returns a live ExoPlayer that has already streamed past the pre-roll commercial
     * (volume still 0 — caller must set it to 1.0f), or null if the player is not yet
     * ready (still in the commercial phase, or never started).
     *
     * After promotion a new warm-up cycle starts immediately for the next session.
     */
    fun promotePlayer(stationId: String, buildUrl: (String) -> String): ExoPlayer? {
        // Cancel expiry timer — we're taking ownership now
        expiryTimers.remove(stationId)?.let { mainHandler.removeCallbacks(it) }

        val player = readyPlayers.remove(stationId)
        if (player != null) {
            Log.d(TAG, "[$stationId] Promoted! Restarting warm-up for next time")
            // Start a fresh warm-up immediately so the next tap is also ad-free
            STATIONS[stationId]?.let { baseUrl -> startWarmup(stationId, buildUrl(baseUrl)) }
            return player
        }

        Log.d(TAG, "[$stationId] Not ready yet (still in commercial phase or never started) — new connection will be used")
        return null
    }

    /**
     * Called when the user taps a station that is still in the warming phase.
     * We stop the warm-up player so it doesn't compete with the main ExoPlayer.
     * (When promotePlayer returns null and the service creates a new connection,
     *  this ensures no duplicate players for the same station.)
     */
    fun cancelIfWarming(stationId: String) {
        commercialTimers.remove(stationId)?.let { mainHandler.removeCallbacks(it) }
        warmingPlayers.remove(stationId)?.let {
            it.stop()
            it.release()
            Log.d(TAG, "[$stationId] Warm-up cancelled (still in commercial phase)")
        }
    }

    /** Release everything (call from onDestroy). */
    fun release() {
        commercialTimers.values.forEach { mainHandler.removeCallbacks(it) }
        commercialTimers.clear()
        expiryTimers.values.forEach { mainHandler.removeCallbacks(it) }
        expiryTimers.clear()
        warmingPlayers.values.forEach { it.stop(); it.release() }
        warmingPlayers.clear()
        readyPlayers.values.forEach { it.stop(); it.release() }
        readyPlayers.clear()
        Log.d(TAG, "PrerollWarmer released")
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun startWarmup(stationId: String, streamUrl: String) {
        // Build with media-type audio attributes so the OS doesn't throttle it
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(audioAttrs, /* handleAudioFocus= */ false)
            volume = 0f  // completely silent — user hears nothing
            setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
            prepare()
            playWhenReady = true
        }
        warmingPlayers[stationId] = player
        Log.d(TAG, "[$stationId] Warm-up player started (silent, ${COMMERCIAL_DURATION_MS / 1_000}s commercial phase)")

        // After the commercial is done, promote the player to the ready pool
        val commercialDoneTask = Runnable {
            commercialTimers.remove(stationId)
            val warmPlayer = warmingPlayers.remove(stationId) ?: return@Runnable
            readyPlayers[stationId] = warmPlayer
            Log.d(TAG, "[$stationId] Commercial phase done — player is READY for promotion (kept alive for up to ${MAX_READY_MS / 60_000}m)")

            // Schedule expiry so we don't stream forever if the user never taps it
            val expiryTask = Runnable {
                expiryTimers.remove(stationId)
                readyPlayers.remove(stationId)?.let {
                    it.stop()
                    it.release()
                    Log.d(TAG, "[$stationId] Ready player expired after ${MAX_READY_MS / 60_000}m — released")
                }
            }
            expiryTimers[stationId] = expiryTask
            mainHandler.postDelayed(expiryTask, MAX_READY_MS)
        }
        commercialTimers[stationId] = commercialDoneTask
        mainHandler.postDelayed(commercialDoneTask, COMMERCIAL_DURATION_MS)
    }
}
