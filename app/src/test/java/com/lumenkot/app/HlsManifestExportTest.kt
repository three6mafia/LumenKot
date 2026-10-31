package com.lumenkot.app

import org.junit.Assert.*
import org.junit.Test

class HlsManifestExportTest {
    @Test fun rewritesSegmentsEncryptionKeysMapsAndSignedUrls() {
        val resources=mutableListOf<Pair<String,Boolean>>()
        val text="""#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin?token=a%2Fb"
#EXT-X-MAP:URI="init.mp4"
#EXTINF:4,
segment.ts?token=c%2Fd
#EXT-X-ENDLIST
"""
        val exported=HlsManifestExport.rewrite(text,"https://cdn.example/folder/video.m3u8?signature=x",true) { url,playlist -> resources.add(url to playlist);"content://saved/${resources.size}" }
        assertEquals(listOf("https://cdn.example/folder/keys/key.bin?token=a%2Fb","https://cdn.example/folder/init.mp4","https://cdn.example/folder/segment.ts?token=c%2Fd"),resources.map { it.first })
        assertTrue(resources.none { it.second })
        assertTrue(exported.contains("URI=\"content://saved/1\""))
        assertTrue(exported.contains("content://saved/3"))
        assertFalse(exported.contains("https://"))
    }
    @Test fun byteRangesBecomeIndependentFilesIncludingInitializationMap() {
        val references=mutableListOf<HlsExportReference>()
        val text="""#EXTM3U
#EXT-X-MAP:URI="file.mp4",BYTERANGE="20@0"
#EXT-X-BYTERANGE:100@20
file.mp4
#EXT-X-BYTERANGE:80
file.mp4
#EXT-X-ENDLIST
"""
        val local=HlsManifestExport.rewriteReferences(text,"https://cdn.example/main.m3u8",true) { ref -> references.add(ref);"content://saved/${references.size}" }
        assertEquals(listOf(0L,20L,120L),references.map { it.position })
        assertEquals(listOf(20L,100L,80L),references.map { it.length })
        assertFalse(local.contains("BYTERANGE"))
        assertTrue(local.contains("URI=\"content://saved/1\""))
    }
    @Test fun excludesSubtitleRenditionsButKeepsAudioAndVariantPlaylists() {
        val references=mutableListOf<Pair<String,Boolean>>()
        val text="""#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="RU",URI="subtitles.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=800000,SUBTITLES="subs",AUDIO="audio"
video.m3u8
"""
        val local=HlsManifestExport.rewrite(text,"https://cdn.example/master.m3u8",false) { url,playlist -> references.add(url to playlist);"content://saved/file${references.size}" }
        assertFalse(local.contains("SUBTITLES"))
        assertEquals(2,references.size)
        assertTrue(references.all { it.second })
        assertTrue(references.first().first.endsWith("audio.m3u8"))
        assertTrue(references.last().first.endsWith("video.m3u8"))
    }
}
