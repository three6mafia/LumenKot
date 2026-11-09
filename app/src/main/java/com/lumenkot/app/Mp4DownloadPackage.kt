package com.lumenkot.app

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.DownloadRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A completion receipt is committed only after the entire MP4 has reached its destination.
 * Staging and cached segments survive pause/process death; partial output is never playable. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Mp4DownloadPackage(private val context: Context,private val request: DownloadRequest) {
    private val data=HlsDownloads.metadata(request)
    private val root=File(data.getString("root"))
    private val directory=File(root,"mp4/${request.id}")
    private val work=File(root,"mp4-work/${request.id}")
    private val receipt=File(directory,"completed.json")
    private val tree=data.optString("treeUri").takeIf {it.isNotBlank()}?.let {TreeDocuments(context,Uri.parse(it))}
    private fun checkCanceled(canceled: ()->Boolean) {if(canceled() || Thread.currentThread().isInterrupted) throw InterruptedException()}
    fun read(): JSONObject {
        val saved=JSONObject(receipt.readText())
        check(saved.getString("id")==request.id && saved.getString("source")==request.uri.toString()) {"Данные MP4 не совпадают"}
        val uri=Uri.parse(saved.getString("uri"))
        val size=if(uri.scheme=="file") File(uri.path!!).length() else context.contentResolver.openAssetFileDescriptor(uri,"r")?.use {it.length} ?: -1
        check(size==saved.getLong("length") || size<0 && context.contentResolver.openInputStream(uri)?.use {it.read()!=-1}==true) {"Скачанный MP4 недоступен"}
        return saved
    }
    fun committed()=runCatching {read();true}.getOrDefault(false)
    fun cleanup() {work.deleteRecursively()}
    fun factory(): DataSource.Factory=DefaultDataSource.Factory(context,DataSource.Factory {androidx.media3.datasource.PlaceholderDataSource.INSTANCE})
    fun remove() {
        if(tree!=null) TreeDownloadPackage(context,request).remove()
        check(!directory.exists() || directory.deleteRecursively()) {"Не удалось удалить MP4"}
        work.deleteRecursively()
    }
    fun publish(factory: DataSource.Factory,remuxer: Mp4Remuxer,canceled: ()->Boolean,phase: (String)->Unit,progress: (Float)->Unit) {
        check(directory.isDirectory || directory.mkdirs()) {"Не удалось открыть папку MP4"}
        check(work.isDirectory || work.mkdirs()) {"Не удалось открыть папку сборки"}
        val ready=File(work,"ready.mp4")
        if(!ready.isFile) {
            val part=File(work,"output.part.mp4");part.delete()
            try {
                if(request.mimeType==MimeTypes.APPLICATION_M3U8) {
                    phase("Подготовка сборки MP4")
                    val input=LocalHlsInput(factory,File(work,"input"),canceled).prepare(request)
                    phase("Сборка MP4")
                    remuxer.remux(input.video,input.audio,input.durationMs,part,canceled,progress)
                } else {
                    phase("Сохранение MP4")
                    copySource(factory,request.uri.toString(),part,canceled)
                }
                checkCanceled(canceled)
                check(part.length()>0 && part.renameTo(ready)) {"Не удалось завершить сборку MP4"}
                File(work,"input").deleteRecursively()
            } finally {part.delete()}
        }
        checkCanceled(canceled);phase("Сохранение в выбранную папку")
        val destination=tree?.let {TreeDownloadPackage(context,request).outputDirectory()}
        fun save(file: File,name: String,mime: String): Uri {
            checkCanceled(canceled)
            if(tree==null) {
                val target=File(directory,name)
                check(file.renameTo(target)) {"Не удалось сохранить MP4"}
                return Uri.fromFile(target)
            }
            val uri=tree.find(destination!!,name)?.uri ?: tree.create(destination,mime,name)
            val output=context.contentResolver.openOutputStream(uri,"wt") ?: error("Папка недоступна для записи")
            output.use {out ->file.inputStream().use {input ->
                val buffer=ByteArray(65536)
                while(true) {checkCanceled(canceled);val count=input.read(buffer);if(count<0) break;out.write(buffer,0,count)}
            }}
            return uri
        }
        // Keep a ready staging file until the destination and receipt have both been committed.
        val videoSize=ready.length()
        val videoUri=if(tree==null) {
            val target=File(directory,"video.mp4")
            ready.inputStream().use {input ->target.outputStream().use {out ->
                val buffer=ByteArray(65536)
                while(true) {checkCanceled(canceled);val count=input.read(buffer);if(count<0) break;out.write(buffer,0,count)}
            }}
            Uri.fromFile(target)
        } else save(ready,"video.mp4","video/mp4")
        val savedSubs=JSONArray();val subs=data.optJSONArray("subtitles") ?: JSONArray()
        for(i in 0 until subs.length()) {
            val sub=subs.getJSONObject(i);val url=sub.getString("url")
            val extension=if(Uri.parse(url).path.orEmpty().endsWith(".srt",true)) "srt" else "vtt"
            val file=File(work,"subtitle-$i.$extension")
            try {
                copySource(factory,url,file,canceled)
                val uri=save(file,file.name,if(extension=="srt") "application/x-subrip" else "text/vtt")
                savedSubs.put(JSONObject(sub.toString()).put("url",uri.toString()).put("source",url))
            } catch(e: java.io.IOException) {checkCanceled(canceled)}
        }
        val poster=data.optString("poster")
        if(poster.isNotBlank()) try {val file=File(work,"poster.jpg");copySource(factory,poster,file,canceled);save(file,file.name,"image/jpeg")} catch(e: java.io.IOException) {checkCanceled(canceled)}
        checkCanceled(canceled)
        val saved=JSONObject().put("id",request.id).put("source",request.uri.toString()).put("uri",videoUri.toString()).put("length",videoSize).put("subtitles",savedSubs)
        val temp=File(directory,"completed.tmp");temp.writeText(saved.toString())
        check(temp.renameTo(receipt)) {"Не удалось сохранить состояние загрузки"}
        read() // Verify the destination before releasing resumable input.
        work.deleteRecursively()
    }
    private fun copySource(factory: DataSource.Factory,url: String,file: File,canceled: ()->Boolean) {
        DataSourceInputStream(factory.createDataSource(),DataSpec(Uri.parse(url))).use {input ->file.outputStream().use {out ->
            val buffer=ByteArray(65536)
            while(true) {checkCanceled(canceled);val count=input.read(buffer);if(count<0) break;out.write(buffer,0,count)}
        }}
    }
}
