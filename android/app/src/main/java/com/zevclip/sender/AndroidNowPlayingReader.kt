package com.zevclip.sender

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.zevclip.sender.airplay.RaopTestToneClient

object AndroidNowPlayingReader {
    fun current(context: Context): RaopTestToneClient.NowPlayingMetadata? {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, AndroidNotificationMirrorService::class.java)
        val sessions = runCatching { manager.getActiveSessions(listener) }.getOrElse { emptyList() }
        if (sessions.isEmpty()) return null

        val controller = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null
        } ?: sessions.firstOrNull { it.metadata != null } ?: return null

        val metadata = controller.metadata ?: return null
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM)
        val nowPlaying = RaopTestToneClient.NowPlayingMetadata(title, artist, album)
        return if (nowPlaying.isEmpty()) null else nowPlaying
    }

    private fun MediaMetadata.text(key: String): String? {
        return getString(key)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
