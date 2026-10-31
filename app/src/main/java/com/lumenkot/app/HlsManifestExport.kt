package com.lumenkot.app

import java.net.URI

data class HlsExportReference(val url: String,val playlist: Boolean,val position: Long=0,val length: Long=-1)

/** Each byte-range becomes its own local resource; keys, maps and signed URLs are preserved. */
object HlsManifestExport {
    fun rewrite(text: String,base: String,subtitles: Boolean,resource: (String,Boolean)->String): String =
        rewriteReferences(text,base,subtitles) { resource(it.url,it.playlist) }

    fun rewriteReferences(text: String,base: String,subtitles: Boolean,resource: (HlsExportReference)->String): String {
        var variant=false
        var pendingRange: String?=null
        val ends=mutableMapOf<String,Long>()
        fun reference(raw: String,playlist: Boolean,range: String?=null): String {
            val url=URI(base).resolve(raw).toString()
            val length=range?.substringBefore('@')?.toLong() ?: -1
            val position=if(range==null) 0 else range.substringAfter('@',"").toLongOrNull() ?: ends[url] ?: 0
            if(range!=null) { require(length>0 && position>=0);ends[url]=position+length }
            return resource(HlsExportReference(url,playlist,position,length))
        }
        fun withoutAttribute(line: String,name: String): String {
            val attrs=line.substringAfter(':').split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
            return line.substringBefore(':')+":"+attrs.filterNot { it.trimStart().startsWith("$name=") }.joinToString(",")
        }
        return text.lineSequence().mapNotNull { original ->
            var line=original.trim()
            // Partial/live preload hints are not part of the completed segment snapshot.
            if(listOf("#EXT-X-PART:","#EXT-X-PRELOAD-HINT:","#EXT-X-RENDITION-REPORT:","#EXT-X-SESSION-DATA:").any { line.startsWith(it) }) return@mapNotNull null
            if(!subtitles && line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=SUBTITLES")) return@mapNotNull null
            if(line.startsWith("#EXT-X-BYTERANGE:")) { pendingRange=line.substringAfter(':');return@mapNotNull null }
            if(line.startsWith("#EXT-X-STREAM-INF:")) {
                variant=true
                if(!subtitles) line=withoutAttribute(line,"SUBTITLES")
            }
            if(line.isNotEmpty() && !line.startsWith('#')) {
                val playlist=variant || URI(base).resolve(line).path.orEmpty().endsWith(".m3u8",true)
                variant=false
                val result=reference(line,playlist,pendingRange)
                pendingRange=null
                result
            } else {
                val playlist=line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                val range=if(line.startsWith("#EXT-X-MAP:")) Regex("BYTERANGE=\"([^\"]+)\"").find(line)?.groupValues?.get(1) else null
                if(range!=null) line=withoutAttribute(line,"BYTERANGE")
                Regex("URI=\"([^\"]+)\"").replace(line) { match -> "URI=\"${reference(match.groupValues[1],playlist,range)}\"" }
            }
        }.joinToString("\n")
    }
}
