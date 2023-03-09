/*
 *  RadioPlayerService.kt
 *
 *  Created by Ilya Chirkunov <xc@yar.net> on 30.12.2020.
 */

package com.cheebeez.radio_player

import com.cheebeez.radio_player.R
import java.net.URL
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.PendingIntent
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.ui.PlayerNotificationManager.BitmapCallback
import com.google.android.exoplayer2.ui.PlayerNotificationManager.MediaDescriptionAdapter
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import android.app.Notification
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log

/** Service for plays streaming audio content using ExoPlayer. */
class RadioPlayerService : Service(), Player.Listener {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "radio_channel_id"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATE_CHANGED = "state_changed"
        const val ACTION_STATE_CHANGED_EXTRA = "state"
        const val ACTION_NEW_METADATA = "matadata_changed"
        const val ACTION_NEW_METADATA_EXTRA = "matadata"
    }

    var metadataArtwork: Bitmap? = null
    var ignoreIcy: Boolean = false
    private var defaultArtwork: Bitmap? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var notificationTitle = ""
    private var isForegroundService = false
    private var currentMetadata: ArrayList<String>? = null
    private var localBinder = LocalBinder()
    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val localBroadcastManager: LocalBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(this)
    }

    inner class LocalBinder : Binder() {
        // Return this instance of RadioPlayerService so clients can call public methods.
        fun getService(): RadioPlayerService = this@RadioPlayerService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return localBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        player.setRepeatMode(Player.REPEAT_MODE_ONE)
        player.addListener(this)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        playerNotificationManager?.setPlayer(null)
        player.release()
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    fun setMediaItem(streamTitle: String, streamUrl: String) {
        val mediaItems: List<MediaItem> = runBlocking {
            GlobalScope.async {
                parseUrls(streamUrl).map { MediaItem.fromUri(it) }
            }.await()
        }

        currentMetadata = null
        defaultArtwork = null
        metadataArtwork = null
        notificationTitle = streamTitle
        playerNotificationManager?.invalidate() ?: createNotificationManager()

        player.stop()
        player.clearMediaItems()
        player.seekTo(0)
        player.addMediaItems(mediaItems)
    }

    fun setMetadata(metadata: ArrayList<String>) {
        currentMetadata = metadata
        playerNotificationManager?.invalidate()

        val metadataIntent = Intent(ACTION_NEW_METADATA)
        metadataIntent.putStringArrayListExtra(ACTION_NEW_METADATA_EXTRA, metadata)
        localBroadcastManager.sendBroadcast(metadataIntent)
    }

    fun setDefaultArtwork(image: Bitmap) {
        defaultArtwork = image
        playerNotificationManager?.invalidate()
    }

    fun play() {
        player.playWhenReady = true
    }

    fun stop() {
        player.playWhenReady = false
        player.stop()
    }

    fun pause() {
        player.playWhenReady = false
    }

    /** Extract URLs from user link. */
    private fun parseUrls(url: String): List<String> {
        var urls: List<String> = emptyList()

        when (url.substringAfterLast(".")) {
            "pls" -> {
                urls = URL(url).readText().lines().filter {
                    it.contains("=http") }.map {
                    it.substringAfter("=")
                }
            }
            "m3u" -> {
                val content = URL(url).readText().trim()
                urls = listOf<String>(content)
            }
            else -> {
                urls = listOf<String>(url)
            }
        }

        return urls
    }

    /** Creates a notification manager for background playback. */
    private fun createNotificationManager() {
        // Setup media session
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSessionCompat(context, "RadioPlayerService", null, pendingIntent)
        mediaSession?.let {
            it.isActive = true
            val mediaSessionConnector = MediaSessionConnector(it)
            mediaSessionConnector.setPlayer(player)
        }
        // Setup audio focus
        val audioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(audioAttributes, true);
        // Setup notification manager
        val mediaDescriptionAdapter = object : MediaDescriptionAdapter {
            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                return null
            }
            override fun getCurrentLargeIcon(player: Player, callback: BitmapCallback): Bitmap? {
                metadataArtwork = downloadImage(currentMetadata?.get(2))
                if (metadataArtwork != null) callback?.onBitmap(metadataArtwork!!)
                return defaultArtwork
            }
            override fun getCurrentContentTitle(player: Player): String {
                return currentMetadata?.get(0) ?: notificationTitle
            }
            override fun getCurrentContentText(player: Player): String? {
                return currentMetadata?.get(1) ?: null
            }
        }

        val notificationListener = object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                if(ongoing && !isForegroundService) {
                    startForeground(notificationId, notification)
                    isForegroundService = true
                }
            }
            override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                stopForeground(true)
                isForegroundService = false
                stopSelf()
            }
        }

        playerNotificationManager = PlayerNotificationManager.Builder(
            this, NOTIFICATION_ID, NOTIFICATION_CHANNEL_ID)
            .setChannelNameResourceId(R.string.channel_name)
            //.setChannelDescriptionResourceId(R.string.notification_Channel_Description)
            .setMediaDescriptionAdapter(mediaDescriptionAdapter)
            .setNotificationListener(notificationListener)
            .build().apply {
                setUsePlayPauseActions(true)
                setUseStopAction(true)
                setUseFastForwardAction(false)
                setUseFastForwardActionInCompactView(false)
                setUseRewindAction(false)
                setUseRewindActionInCompactView(false)
                setUsePreviousActionInCompactView(false)
                setUseNextActionInCompactView(false)
                setUsePreviousAction(false)
                setUseNextAction(false)
                setPlayer(player)
            }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, playbackState: Int) {
        if (playbackState == Player.STATE_IDLE && playWhenReady == true) {
            player.prepare()
        }

        // Notify the client if the playback state was changed
        val stateIntent = Intent(ACTION_STATE_CHANGED)
        stateIntent.putExtra(ACTION_STATE_CHANGED_EXTRA, playWhenReady)
        localBroadcastManager.sendBroadcast(stateIntent)
    }

    override fun onMetadata(md: Metadata) {
        if (ignoreIcy) return

        val icyInfo: IcyInfo = md[0] as IcyInfo
        val title: String = icyInfo.title ?: return
        if (title.length == 0) return
        val cover: String = icyInfo.url ?: ""

        var metadata: MutableList<String> = title.split(" - ").toMutableList()
        if (metadata.lastIndex == 0) metadata.add("")
        metadata.add(cover)

        setMetadata(ArrayList(metadata))
    }

    fun downloadImage(value: String?): Bitmap? {
        if (value == null) return null
        var bitmap: Bitmap? = null

        try {
            val url: URL = URL(value)
            bitmap = runBlocking {
                GlobalScope.async {
                    BitmapFactory.decodeStream(url.openStream())
                }.await()
            }
        } catch (e: Throwable) {
            println(e)
        }

        return bitmap
    }
}