package com.example.sleepsongs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.ui.PlayerNotificationManager
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.max

class SleepSongsPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: PlayerNotificationManager

    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

    private var targetLoops = 1
    private var currentLoop = 1
    private var fadeOnFinalLoop = true

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AudioManager::class.java)
        ensureNotificationChannel()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            if (shouldFadeNow() && fadeRunnable == null) {
                                startFadeForCurrentLoop()
                            }
                        }

                        Player.STATE_ENDED -> {
                            if (currentLoop < targetLoops) {
                                currentLoop += 1
                                volume = 1f
                                seekTo(0)
                                play()
                                if (!shouldFadeNow()) {
                                    cancelFade()
                                }
                            } else {
                                stopPlaybackAndResetPosition()
                            }
                        }
                    }
                }
            })
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()

        notificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            NOTIFICATION_CHANNEL_ID
        )
            .setSmallIconResourceId(android.R.drawable.ic_media_play)
            .setMediaDescriptionAdapter(NotificationDescriptionAdapter())
            .setNotificationListener(NotificationListener())
            .build()
            .apply {
                setMediaSessionToken(mediaSession.sessionCompatToken)
                setPlayer(player)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    override fun onDestroy() {
        cancelFade()
        notificationManager.setPlayer(null)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ConnectionResult {
            val sessionCommands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(PlaybackCommands.START_PLAYBACK, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.STOP_PLAYBACK, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.SET_OUTPUT_DEVICE, Bundle.EMPTY))
                .build()

            return ConnectionResult.accept(
                sessionCommands,
                ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                PlaybackCommands.START_PLAYBACK -> {
                    val uriString = args.getString(PlaybackCommands.EXTRA_URI)
                    val repeatCount = args.getInt(PlaybackCommands.EXTRA_REPEAT_COUNT, 1)
                    val fadeOnFinal = args.getBoolean(PlaybackCommands.EXTRA_FADE_ON_FINAL_LOOP, true)
                    val title = args.getString(PlaybackCommands.EXTRA_TITLE)

                    if (uriString.isNullOrBlank()) {
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    } else {
                        startPlayback(
                            uri = Uri.parse(uriString),
                            repeatCount = max(1, repeatCount),
                            fadeFinalLoop = fadeOnFinal,
                            title = title
                        )
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }

                PlaybackCommands.STOP_PLAYBACK -> {
                    stopPlaybackAndResetPosition()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlaybackCommands.SET_OUTPUT_DEVICE -> {
                    setOutputDevice(args.getInt(PlaybackCommands.EXTRA_OUTPUT_DEVICE_ID, OUTPUT_DEFAULT))
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
    }

    private fun startPlayback(
        uri: Uri,
        repeatCount: Int,
        fadeFinalLoop: Boolean,
        title: String?
    ) {
        cancelFade()
        targetLoops = repeatCount
        currentLoop = 1
        fadeOnFinalLoop = fadeFinalLoop

        player.volume = 1f
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title ?: uri.lastPathSegment ?: "Sleep Song")
                        .setArtist("Sleep Songs")
                        .build()
                )
                .build()
        )
        player.prepare()
        player.playWhenReady = true
    }

    private fun stopPlaybackAndResetPosition() {
        cancelFade()
        player.volume = 1f
        player.pause()
        player.seekTo(0)
    }

    private fun setOutputDevice(outputDeviceId: Int) {
        if (outputDeviceId == OUTPUT_DEFAULT) {
            player.setPreferredAudioDevice(null)
            return
        }

        val output = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == outputDeviceId }

        player.setPreferredAudioDevice(output)
    }

    private fun shouldFadeNow(): Boolean {
        return fadeOnFinalLoop && currentLoop == targetLoops && player.isPlaying
    }

    private fun startFadeForCurrentLoop() {
        val durationMs = player.duration
        if (durationMs <= 0) return

        cancelFade()
        val steps = 40
        val stepDelayMs = (durationMs / steps).coerceAtLeast(25)

        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                if (!shouldFadeNow()) {
                    fadeRunnable = null
                    return
                }

                val volume = 1f - (step.toFloat() / steps.toFloat())
                player.volume = volume.coerceIn(0f, 1f)

                step += 1
                if (step <= steps) {
                    handler.postDelayed(this, stepDelayMs)
                } else {
                    fadeRunnable = null
                }
            }
        }

        fadeRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelFade() {
        fadeRunnable?.let(handler::removeCallbacks)
        fadeRunnable = null
    }

    private inner class NotificationDescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return player.currentMediaItem?.mediaMetadata?.title ?: "Sleep Songs"
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent {
            val intent = Intent(this@SleepSongsPlaybackService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                this@SleepSongsPlaybackService,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        override fun getCurrentContentText(player: Player): CharSequence {
            return player.currentMediaItem?.mediaMetadata?.artist ?: "Playing"
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ) = null
    }

    private inner class NotificationListener : PlayerNotificationManager.NotificationListener {
        override fun onNotificationPosted(
            notificationId: Int,
            notification: android.app.Notification,
            ongoing: Boolean
        ) {
            if (ongoing) {
                startForeground(notificationId, notification)
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "sleep_songs_playback"
        private const val NOTIFICATION_ID = 1001
        private const val OUTPUT_DEFAULT = -1
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Sleep Songs Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Playback controls for Sleep Songs"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
