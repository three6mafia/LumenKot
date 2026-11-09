package com.lumenkot.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.IOException

/** Select one variant and its audio before persisting a Media3 download request. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object HlsDownloadPlan {
    fun hasHlsExtension(url: String)=Uri.parse(url).path.orEmpty().endsWith(".m3u8",true)

    fun isHls(url: String, factory: DataSource.Factory): Boolean {
        val path=Uri.parse(url).path.orEmpty()
        if(hasHlsExtension(url)) return true
        if(path.endsWith(".mp4",true)) return false
        val source=factory.createDataSource()
        return DataSourceInputStream(source,DataSpec(Uri.parse(url))).use {input ->
            input.open()
            val type=source.responseHeaders.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value?.firstOrNull().orEmpty()
            if(type.contains("mpegurl",true)) return@use true
            val prefix=ByteArray(256);var size=0
            while(size<prefix.size) {val read=input.read(prefix,size,prefix.size-size);if(read<0) break;size+=read}
            size>0 && String(prefix,0,size,Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n').startsWith("#EXTM3U")
        }
    }

    fun prepare(url: String, quality: String, subtitles: Boolean, factory: DataSource.Factory): List<StreamKey> {
        val manifest=parse(Uri.parse(url),factory)
        if(manifest is HlsMediaPlaylist) {requireVod(manifest);return emptyList()}
        val master=manifest as HlsMultivariantPlaylist
        check(master.variants.isNotEmpty()) {"В HLS нет видеопотоков"}
        check(master.sessionKeyDrmInitData.isEmpty()) {"Для этого видео нужна отдельная офлайн-лицензия"}
        val videoIndex=selectVariant(master,quality)
        val variant=master.variants[videoIndex]
        val keys=mutableListOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT,videoIndex))
        val playlists=mutableListOf(variant.url)
        val audio=master.audios.withIndex().filter {it.value.groupId==variant.audioGroupId && it.value.url!=null}
        val chosenAudio=audio.firstOrNull {it.value.format.selectionFlags and C.SELECTION_FLAG_DEFAULT!=0} ?: audio.firstOrNull()
        chosenAudio?.let {keys.add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO,it.index));playlists.add(it.value.url!!)}
        if(subtitles) master.subtitles.forEachIndexed {index,rendition ->
            if(rendition.groupId==variant.subtitleGroupId && rendition.url!=null) {
                keys.add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE,index));playlists.add(rendition.url!!)
            }
        }
        playlists.distinct().forEach {uri ->
            val playlist=parse(uri,factory,master)
            check(playlist is HlsMediaPlaylist) {"Сервис вернул неподдерживаемый HLS-плейлист"}
            requireVod(playlist)
        }
        return keys
    }

    private fun selectVariant(master: HlsMultivariantPlaylist,quality: String): Int {
        val height=when(quality.lowercase()) {"4k"->2160;"2k"->1440;else->Regex("\\d+").find(quality)?.value?.toIntOrNull() ?: Int.MAX_VALUE}
        val all=master.variants.withIndex().filter {it.value.format.roleFlags and C.ROLE_FLAG_TRICK_PLAY==0}
        check(all.isNotEmpty()) {"В HLS нет обычной видеодорожки"}
        val sized=all.filter {it.value.format.height>0}
        val eligible=sized.filter {it.value.format.height<=height}
        return when {
            eligible.isNotEmpty() -> eligible.maxWith(compareBy({it.value.format.height},{it.value.format.bitrate})).index
            sized.isNotEmpty() -> sized.minBy {it.value.format.height}.index
            else -> all.maxBy {it.value.format.bitrate}.index
        }
    }

    private fun parse(uri: Uri,factory: DataSource.Factory,master: HlsMultivariantPlaylist?=null): HlsPlaylist {
        val source=factory.createDataSource()
        return DataSourceInputStream(source,DataSpec(uri)).use {input ->
            input.open()
            val resolved=source.uri ?: uri
            (if(master==null) HlsPlaylistParser() else HlsPlaylistParser(master,null)).parse(resolved,input)
        }
    }
    private fun requireVod(playlist: HlsMediaPlaylist) {
        if(!playlist.hasEndTag) throw IOException("Это прямая трансляция. Скачать можно завершённое видео, фильм или серию.")
        check(playlist.protectionSchemes==null) {"Для этого видео нужна отдельная офлайн-лицензия"}
        check(playlist.segments.isNotEmpty()) {"В HLS-плейлисте нет сегментов"}
    }
}
