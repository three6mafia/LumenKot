package com.lumenkot.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Copies completed cached media into a user-granted SAF tree; all HLS references become local. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedMediaExporter(private val applicationContext: Context,private val source: DataSource.Factory,private val canceled: ()->Boolean = { false }) {
    fun export(downloadId: String,mediaUrl: String,metadata: JSONObject,onFolder: (Uri)->Unit = {}): JSONObject {
        val tree=metadata.getString("tree")
        val parent=DocumentFile.fromTreeUri(applicationContext,Uri.parse(tree)) ?: error("Папка недоступна")
        check(parent.canWrite()) { "Нет доступа к папке. Выберите её заново." }
        val name=metadata.optString("title").replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").take(80)+"-"+downloadId.take(8)
        val folder=parent.findFile(name) ?: parent.createDirectory(name) ?: error("Не удалось создать папку")
        onFolder(folder.uri)
        val written=mutableMapOf<String,String>()
        fun export(reference: HlsExportReference): String {
            val url=reference.url;val playlist=reference.playlist
            val key="$url|${reference.position}|${reference.length}"
            written[key]?.let { return it }
            check(!canceled()) { "Сохранение отменено" }
            val suffix=if(playlist) "m3u8" else Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.',"bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,6}")) } ?: "bin"
            val filename=if(url==mediaUrl && reference.position==0L && reference.length<0) "video.$suffix" else MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }+"."+suffix
            val file=folder.findFile(filename) ?: folder.createFile(if(playlist) "application/vnd.apple.mpegurl" else if(metadata.optString("format")=="MP4") "video/mp4" else "application/octet-stream",filename) ?: error("Не удалось создать файл")
            written[key]=file.uri.toString()
            val dataSource=source.createDataSource()
            DataSourceInputStream(dataSource,DataSpec.Builder().setUri(url).setPosition(reference.position).setLength(reference.length).build()).use { input ->
                input.open()
                val resolvedBase=dataSource.uri?.toString() ?: url
                applicationContext.contentResolver.openOutputStream(file.uri,"wt")!!.use { output ->
                    if(playlist) {
                        val text=input.bufferedReader().readText()
                        val local=HlsManifestExport.rewriteReferences(text,resolvedBase,metadata.optBoolean("includeSubtitles",true)) { child -> export(child) }
                        output.write(local.toByteArray())
                    } else {
                        val buffer=ByteArray(64*1024)
                        while(true) { check(!canceled()) { "Сохранение отменено" };val size=input.read(buffer);if(size<0) break;output.write(buffer,0,size) }
                    }
                }
            }
            return file.uri.toString()
        }
        val local=export(HlsExportReference(mediaUrl,metadata.optString("format")=="HLS"))
        val subtitles=JSONArray()
        if(metadata.optBoolean("includeSubtitles")) {
            val api=RezkaApi(Settings(applicationContext))
            val list=metadata.optJSONArray("subtitles") ?: JSONArray()
            for(index in 0 until list.length()) {
                check(!canceled()) { "Сохранение отменено" }
                val sub=list.getJSONObject(index)
                val extension=if(sub.optString("mime").contains("subrip")) "srt" else "vtt"
                val file=folder.findFile("subtitle-$index.$extension") ?: folder.createFile("text/plain","subtitle-$index.$extension") ?: error("Ошибка сохранения субтитров")
                val request=okhttp3.Request.Builder().url(sub.getString("url"));api.headers().forEach { (k,v) -> request.header(k,v) }
                api.client.newCall(request.build()).execute().use { response ->
                    check(response.isSuccessful) { "Субтитры: HTTP ${response.code}" }
                    applicationContext.contentResolver.openOutputStream(file.uri,"wt")!!.use { output -> response.body!!.byteStream().use { it.copyTo(output) } }
                }
                subtitles.put(JSONObject(sub.toString()).put("url",file.uri.toString()))
            }
        }
        val poster=metadata.optString("poster")
        if(poster.isNotBlank()) runCatching {
            val api=RezkaApi(Settings(applicationContext))
            val url=if(poster.startsWith("http")) poster else if(poster.startsWith("//")) "https:$poster" else Links.static+"/"+poster.trimStart('/')
            api.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if(response.isSuccessful) {
                    val image=folder.findFile("poster.jpg") ?: folder.createFile("image/jpeg","poster.jpg")!!
                    applicationContext.contentResolver.openOutputStream(image.uri,"wt")!!.use { output -> response.body!!.byteStream().use { it.copyTo(output) } }
                }
            }
        }
        check(!canceled()) { "Сохранение отменено" }
        return JSONObject(metadata.toString()).put("url",local).put("offlineHls",false).put("mime",if(metadata.optString("format")=="HLS") "application/x-mpegURL" else "video/mp4").put("subtitles",subtitles)
    }
}
