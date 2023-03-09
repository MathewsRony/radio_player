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
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.MetadataOutput
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

/** Service for plays streaming audio content using ExoPlayer. */
class RadioPlayerService : Service(), Player.EventListener, MetadataOutput {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "radio_channel_id"
        const val NOTIFICATION_ID = 1
        const val STREAM_TITLE = "stream_title"
        const val STREAM_URL = "stream_url"
        const val ACTION_STATE_CHANGED = "state_changed"
        const val ACTION_STATE_CHANGED_EXTRA = "state"
        const val ACTION_NEW_METADATA = "matadata_changed"
        const val ACTION_NEW_METADATA_EXTRA = "matadata"
    }

    private lateinit var playerNotificationManager: PlayerNotificationManager
    private var isForegroundService = false
    private var metadataList: MutableList<String>? = null
    private var localBinder = LocalBinder()
    private val player: SimpleExoPlayer by lazy {
        SimpleExoPlayer.Builder(this).build()
    }
    private val localBroadcastManager: LocalBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(this)
    }

    fun play() {
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    inner class LocalBinder : Binder() {
        // Return this instance of RadioPlayerService so clients can call public methods.
        fun getService(): RadioPlayerService = this@RadioPlayerService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return localBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val streamUrl = intent?.getStringExtra(STREAM_URL)!!
        val streamTitle = intent?.getStringExtra(STREAM_TITLE)!!

        setUrls(runBlocking { GlobalScope.async { parseUrls(streamUrl) }.await() })
        player.setRepeatMode(Player.REPEAT_MODE_ONE)
        createNotificationManager(streamTitle)
        player.addListener(this)
        player.addMetadataOutput(this)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        playerNotificationManager.setPlayer(null)
        player.release()
        super.onDestroy()
    }

    /** Extract stream URLs from user link. */
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

    /** Add URLs to exoplayer playlist. */
    private fun setUrls(urls: List<String>) {
        for (url in urls) {
            val mediaItem = MediaItem.fromUri(url)
            player.addMediaItem(mediaItem)
        }
    }

    /** Creates a notification manager for background playback. */
    private fun createNotificationManager(streamTitle: String) {
        val mediaDescriptionAdapter = object : MediaDescriptionAdapter {
            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                return null
            }
            override fun getCurrentLargeIcon(player: Player, callback: BitmapCallback): Bitmap? {
                val resID = getResources().getIdentifier("ic_launcher", "mipmap", packageName)
                val bitmap = BitmapFactory.decodeResource(resources, resID)
                //callback?.onBitmap(bitmap)
                //return bitmap
                return null
            }
            override fun getCurrentContentTitle(player: Player): String {
                return metadataList?.get(0) ?: streamTitle
            }
            override fun getCurrentContentText(player: Player): String? {
                return metadataList?.get(1) ?: null
            }
        }

        val notificationListener = object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                if(ongoing && !isForegroundService) {
                    startForeground(notificationId, notification)
                    isForegroundService = true
                }
            }
            override fun onNotificationCancelled(notificationId: Int) {
                stopForeground(true)
                isForegroundService = false
                stopSelf()
            }
        }

        playerNotificationManager = PlayerNotificationManager.createWithNotificationChannel(
            this, NOTIFICATION_CHANNEL_ID, R.string.channel_name, NOTIFICATION_ID,
            mediaDescriptionAdapter, notificationListener
        ).apply {
            setUsePlayPauseActions(true)
            setUseNavigationActionsInCompactView(true)
            setUseNavigationActions(false)
            setRewindIncrementMs(0)
            setFastForwardIncrementMs(0)
            setPlayer(player)
        }
    }

    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) {
            player.prepare()
        }

        // Notify the client if the playback state was changed
        val stateIntent = Intent(ACTION_STATE_CHANGED)
        stateIntent.putExtra(ACTION_STATE_CHANGED_EXTRA, playWhenReady)
        localBroadcastManager.sendBroadcast(stateIntent)
    }

    override fun onMetadata(metadata: Metadata) {
        val icyInfo: IcyInfo = metadata[0] as IcyInfo
        val title: String = icyInfo.title ?: return

        metadataList = title.split(" - ").toMutableList()
        metadataList!!.add("")
        playerNotificationManager.invalidate()

        val metadataIntent = Intent(ACTION_NEW_METADATA)
        metadataIntent.putStringArrayListExtra(ACTION_NEW_METADATA_EXTRA, metadataList!! as ArrayList<String>)
        localBroadcastManager.sendBroadcast(metadataIntent)
    }
}