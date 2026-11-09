package com.lumenkot.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.File

/** Makes FFmpeg input exclusively from completed local cache spans. No HTTP fallback is allowed.
 * Each byte range becomes its own file, so gaps in a cached resource are never read as zeroes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LocalHlsInput(private val factory: DataSource.Factory,private val directory: File,private val canceled: ()->Boolean) {
    data class Input(val video: File,val audio: File?,val durationMs: Long)
    private var sequence=0
    private fun checkCanceled() {if(canceled() || Thread.currentThread().isInterrupted) throw InterruptedException()}
    private fun parse(uri: Uri,master: HlsMultivariantPlaylist?=null): HlsPlaylist {
        checkCanceled()
        val source=factory.createDataSource()
        return DataSourceInputStream(source,DataSpec(uri)).use {input ->
            input.open()
            (if(master==null) HlsPlaylistParser() else HlsPlaylistParser(master,null)).parse(source.uri ?: uri,input)
        }
    }
    private fun copy(uri: Uri,offset: Long,length: Long,extension: String): File {
        checkCanceled()
        val file=File(directory,"resource-${sequence++}.$extension")
        DataSourceInputStream(factory.createDataSource(),DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build()).use {input ->
            file.outputStream().use {out ->
                val buffer=ByteArray(65536)
                while(true) {checkCanceled();val count=input.read(buffer);if(count<0) break;out.write(buffer,0,count)}
            }
        }
        return file
    }
    fun prepare(request: DownloadRequest): Input {
        directory.deleteRecursively()
        check(directory.isDirectory || directory.mkdirs()) {"Не удалось подготовить папку сборки"}
        val manifest=parse(request.uri)
        val master=(manifest as? HlsMultivariantPlaylist)?.let {if(request.streamKeys.isEmpty()) it else it.copy(request.streamKeys)}
        val video=if(master==null) manifest as HlsMediaPlaylist else parse(master.variants.first().url,master) as HlsMediaPlaylist
        val audio=master?.audios?.firstOrNull {it.url!=null}?.url?.let {parse(it,master) as HlsMediaPlaylist}
        return Input(writePlaylist(video,"video.m3u8"),audio?.let {writePlaylist(it,"audio.m3u8")},video.durationUs/1000)
    }
    private fun writePlaylist(playlist: HlsMediaPlaylist,name: String): File {
        check(playlist.hasEndTag && playlist.protectionSchemes==null) {"Сборка доступна только для завершённого видео без DRM"}
        val text=StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:${(playlist.targetDurationUs+999999)/1000000}\n#EXT-X-MEDIA-SEQUENCE:${playlist.mediaSequence}\n")
        val keys=mutableMapOf<String,File>()
        var previousInit: HlsMediaPlaylist.Segment?=null
        var previousDiscontinuity=0
        var encrypted=false
        fun resolve(value: String)=androidx.media3.common.util.UriUtil.resolveToUri(playlist.baseUri,value)
        fun key(segment: HlsMediaPlaylist.Segment) {
            val url=segment.fullSegmentEncryptionKeyUri
            if(url!=null) {
                val file=keys.getOrPut(url) {copy(resolve(url),0,C.LENGTH_UNSET.toLong(),"key")}
                val iv=segment.encryptionIV ?: error("В HLS отсутствует IV шифрования")
                val normalizedIv="0x"+iv.removePrefix("0x").removePrefix("0X").padStart(32,'0')
                text.append("#EXT-X-KEY:METHOD=AES-128,URI=\"${file.name}\",IV=$normalizedIv\n");encrypted=true
            } else if(encrypted) {text.append("#EXT-X-KEY:METHOD=NONE\n");encrypted=false}
        }
        for(segment in playlist.segments) {
            checkCanceled()
            check(!segment.hasGapTag) {"HLS содержит отсутствующий сегмент"}
            if(segment.relativeDiscontinuitySequence!=previousDiscontinuity) {text.append("#EXT-X-DISCONTINUITY\n");previousDiscontinuity=segment.relativeDiscontinuitySequence}
            val init=segment.initializationSegment
            if(init!=null && init!==previousInit) {
                key(init)
                val file=copy(resolve(init.url),init.byteRangeOffset,init.byteRangeLength,"mp4")
                text.append("#EXT-X-MAP:URI=\"${file.name}\"\n");previousInit=init
            }
            key(segment)
            val file=copy(resolve(segment.url),segment.byteRangeOffset,segment.byteRangeLength,"bin")
            text.append("#EXTINF:${segment.durationUs/1000000.0},\n${file.name}\n")
        }
        text.append("#EXT-X-ENDLIST\n")
        return File(directory,name).apply {writeText(text.toString())}
    }
}
