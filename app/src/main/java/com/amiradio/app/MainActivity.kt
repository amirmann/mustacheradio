package com.amiradio.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.amiradio.app.databinding.ActivityMainBinding
import com.amiradio.app.service.RadioPlaybackService
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaBrowser: MediaBrowserCompat
    private lateinit var radioAdapter: RadioStationAdapter
    private lateinit var audioManager: AudioManager
    private lateinit var playHistoryManager: PlayHistoryManager

    // Updates the Cast banner and volume slider whenever a Cast session changes state
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastBanner(true); syncVolumeSlider()
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            updateCastBanner(true); syncVolumeSlider()
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            updateCastBanner(false); syncVolumeSlider()
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val mediaController = MediaControllerCompat(this@MainActivity, mediaBrowser.sessionToken)
            MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
            mediaController.registerCallback(controllerCallback)
            // Restore state immediately — critical after activity recreation (e.g. dark mode toggle)
            updatePlaybackState(mediaController.playbackState)
            updateMetadata(mediaController.metadata)
            Log.d(TAG, "MediaBrowser connected")
        }
        override fun onConnectionSuspended() { Log.w(TAG, "MediaBrowser connection suspended") }
        override fun onConnectionFailed()     { Log.e(TAG, "MediaBrowser connection failed") }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = updatePlaybackState(state)
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            updateMetadata(metadata)
            refreshStationListOrder()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        audioManager       = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        playHistoryManager = PlayHistoryManager(this)

        try {
            CastButtonFactory.setUpMediaRouteButton(this, binding.mediaRouteButton)
        } catch (e: Exception) {
            Log.w(TAG, "Cast button setup failed: ${e.message}")
            binding.mediaRouteButton.visibility = android.view.View.GONE
        }

        setupRecyclerView()
        setupPlayPauseButton()
        setupVolumeControl()

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, RadioPlaybackService::class.java),
            connectionCallbacks,
            null
        )
    }

    override fun onStart() {
        super.onStart()
        startService(Intent(this, RadioPlaybackService::class.java))
        mediaBrowser.connect()

        try {
            val sessionManager = CastContext.getSharedInstance(this).sessionManager
            sessionManager.addSessionManagerListener(castSessionListener, CastSession::class.java)
            updateCastBanner(sessionManager.currentCastSession?.isConnected == true)
            syncVolumeSlider()
        } catch (e: Exception) { /* Cast not available */ }
    }

    override fun onStop() {
        super.onStop()
        MediaControllerCompat.getMediaController(this)?.unregisterCallback(controllerCallback)
        mediaBrowser.disconnect()
        try {
            CastContext.getSharedInstance(this)
                .sessionManager
                .removeSessionManagerListener(castSessionListener, CastSession::class.java)
        } catch (e: Exception) { /* Cast not available */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        radioAdapter = RadioStationAdapter(
            playHistoryManager.sortStationsByLastPlayed(Stations.ALL)
        ) { playStation(it) }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = radioAdapter
        }
    }

    private fun setupPlayPauseButton() {
        binding.playPauseButton.setOnClickListener {
            val controller = MediaControllerCompat.getMediaController(this)
            if (controller == null) { Log.e(TAG, "MediaController is null"); return@setOnClickListener }
            if (controller.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                controller.transportControls?.pause()
            } else {
                controller.transportControls?.play()
            }
        }
    }

    private fun setupVolumeControl() {
        syncVolumeSlider()
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val castSession = getCastSession()
                if (castSession?.isConnected == true) {
                    try { castSession.setVolume(progress / 15.0) }
                    catch (e: Exception) { Log.e(TAG, "Cast volume error: ${e.message}") }
                } else {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Playback actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun playStation(station: RadioStation) {
        playHistoryManager.recordPlay(station.id)
        refreshStationListOrder()
        binding.nowPlayingText.text = station.name
        binding.nowPlayingIcon.setImageResource(station.iconRes)

        val controller = MediaControllerCompat.getMediaController(this)
        if (controller == null) {
            binding.nowPlayingText.text = "Error: Not connected"
            Log.e(TAG, "MediaController is null")
            return
        }
        controller.transportControls?.playFromMediaId(station.id, null)
    }

    private fun refreshStationListOrder() {
        radioAdapter = RadioStationAdapter(
            playHistoryManager.sortStationsByLastPlayed(Stations.ALL)
        ) { playStation(it) }
        binding.recyclerView.adapter = radioAdapter
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State & metadata updates
    // ─────────────────────────────────────────────────────────────────────────

    private fun updatePlaybackState(state: PlaybackStateCompat?) {
        binding.playPauseButton.text =
            if (state?.state == PlaybackStateCompat.STATE_PLAYING) "⏸" else "▶"
    }

    private fun updateMetadata(metadata: MediaMetadataCompat?) {
        metadata ?: return
        binding.nowPlayingText.text = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        val mediaId = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        Stations.BY_ID[mediaId]?.let { binding.nowPlayingIcon.setImageResource(it.iconRes) }
    }

    private fun updateCastBanner(isCasting: Boolean) {
        val deviceName = getCastSession()?.castDevice?.friendlyName
        if (isCasting && deviceName != null) {
            binding.nowPlayingText.text = "Casting to $deviceName"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Volume — physical buttons
    // ─────────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }

        val castSession = getCastSession()
        return if (castSession?.isConnected == true) {
            // When casting: route to Cast device and consume event so local volume is untouched
            val step = 1.0 / 15.0
            val newVol = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> (castSession.volume + step).coerceIn(0.0, 1.0)
                else                       -> (castSession.volume - step).coerceIn(0.0, 1.0)
            }
            try {
                castSession.setVolume(newVol)
                binding.volumeSlider.progress = (newVol * 15).toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Cast volume error: ${e.message}")
            }
            true
        } else {
            // Local playback: let system handle it, then sync slider
            val result = super.onKeyDown(keyCode, event)
            binding.volumeSlider.postDelayed({
                binding.volumeSlider.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }, 100)
            result
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun getCastSession(): CastSession? = try {
        CastContext.getSharedInstance(this).sessionManager.currentCastSession
    } catch (e: Exception) { null }

    private fun syncVolumeSlider() {
        val castSession = getCastSession()
        binding.volumeSlider.progress = if (castSession?.isConnected == true) {
            (castSession.volume * 15).toInt()
        } else {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }
}
