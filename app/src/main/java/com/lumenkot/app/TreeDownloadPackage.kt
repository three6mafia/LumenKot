package com.lumenkot.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** SAF destinations cannot back SimpleCache's File API. Export finished spans to the granted tree,
 * then replay their original byte positions through a strictly local DataSource. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TreeDownloadPackage(private val context: Context,private val request: DownloadRequest) {
    companion object {
        private const val OWNER="lumen-owner.json"
        private const val INDEX="lumen-index.json"
        fun folderName(title: String,id: String)=title.replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").take(60).ifBlank {"Видео"}+"_"+id.takeLast(16)
    }
    private val data=HlsDownloads.metadata(request)
    private val documents=TreeDocuments(context,Uri.parse(data.getString("treeUri")))
    private val folderName=data.getString("folderName")
    private fun folder(create: Boolean=false): Uri? {
        val existing=documents.find(documents.root,folderName)
        val directory=existing?.uri ?: if(create) documents.create(documents.root,DocumentsContract.Document.MIME_TYPE_DIR,folderName) else return null
        if(existing!=null) check(existing.mime==DocumentsContract.Document.MIME_TYPE_DIR) {"В выбранной папке уже есть файл с именем загрузки"}
        val marker=documents.find(directory,OWNER)
        if(marker!=null) check(documents.readJson(marker.uri).optString("id")==request.id) {"Папка не принадлежит этой загрузке"}
        else if(create) {
            check(documents.children(directory).isEmpty()) {"В папке уже есть другие файлы. Выберите другое место."}
            documents.writeJson(documents.create(directory,"application/json",OWNER),JSONObject().put("id",request.id))
        } else error("Не найдены данные загрузки")
        return directory
    }
    private fun fingerprint(): String = request.uri.toString()+"|"+request.streamKeys.joinToString()
    fun readIndex(): JSONObject {
        val directory=folder() ?: error("Папка видео не найдена. Проверьте накопитель.")
        val index=documents.find(directory,INDEX) ?: error("Сохранение видео ещё не завершено")
        return documents.readJson(index.uri).also {check(it.optString("id")==request.id && it.optString("fingerprint")==fingerprint()) {"Данные загрузки не совпадают"}}
    }
    fun committed(): Boolean {
        val directory=folder() ?: return false
        val entry=documents.find(directory,INDEX) ?: return false
        val index=documents.readJson(entry.uri)
        if(index.optString("id")!=request.id || index.optString("fingerprint")!=fingerprint()) return false
        val files=documents.children(directory).associateBy {it.uri.toString()}
        val resources=index.getJSONObject("resources")
        return resources.keys().asSequence().all {key ->
            val spans=resources.getJSONObject(key).getJSONArray("spans")
            (0 until spans.length()).all {i ->val span=spans.getJSONObject(i);val file=files[span.getString("uri")];file!=null && file.size==span.getLong("length")}
        }
    }
    fun export(cache: SimpleCache,readFactory: CacheDataSource.Factory,progress: (Long,Long)->Unit={_,_->},canceled: ()->Boolean) {
        documents.requireWritable()
        val directory=folder(true)!!
        val resources=JSONObject();val prefix="${request.id}|"
        val files=documents.children(directory).associateBy {it.name}.toMutableMap()
        val total=cache.keys.filter {it.startsWith(prefix)}.sumOf {key ->cache.getCachedSpans(key).sumOf {it.length}}
        var copied=0L
        fun checkCanceled() {if(canceled() || Thread.currentThread().isInterrupted) throw InterruptedException()}
        fun copy(name: String,mime: String,size: Long,input: ()->java.io.InputStream): Uri {
            checkCanceled()
            val existing=files[name]
            if(existing!=null && existing.size==size) {copied+=size;progress(copied,total);return existing.uri}
            val uri=existing?.uri ?: documents.create(directory,mime,name)
            val output=documents.resolver.openOutputStream(uri,"wt") ?: error("Не удалось записать видео в выбранную папку")
            var written=0L
            output.use {out ->input().use {source ->
                val buffer=ByteArray(65536)
                while(true) {checkCanceled();val count=source.read(buffer);if(count<0) break;out.write(buffer,0,count);written+=count;copied+=count;progress(copied,total)}
            }}
            check(written==size) {"Не удалось полностью сохранить файл"}
            files[name]=TreeDocuments.Entry(uri,name,mime,size,0)
            return uri
        }
        for(key in cache.keys.filter {it.startsWith(prefix)}.sorted()) {
            checkCanceled()
            val original=key.removePrefix(prefix);val metadata=cache.getContentMetadata(key)
            val length=ContentMetadata.getContentLength(metadata)
            val complete=length>=0 && cache.isCached(key,0,length)
            val spans=JSONArray()
            if(request.mimeType==MimeTypes.VIDEO_MP4 && original==request.uri.toString()) {
                check(complete) {"Видео ещё не скачано полностью"}
                val uri=copy("video.mp4","video/mp4",length) {DataSourceInputStream(readFactory.createDataSource(),DataSpec(request.uri))}
                spans.put(JSONObject().put("start",0).put("length",length).put("uri",uri.toString()))
            } else for(span in cache.getCachedSpans(key)) {
                val hash=MessageDigest.getInstance("SHA-256").digest(original.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
                val uri=copy("${hash}_${span.position}_${span.length}.bin","application/octet-stream",span.length) {span.file!!.inputStream()}
                spans.put(JSONObject().put("start",span.position).put("length",span.length).put("uri",uri.toString()))
            }
            resources.put(original,JSONObject().put("length",length).put("complete",complete).put("resolved",ContentMetadata.getRedirectedUri(metadata)?.toString() ?: original).put("spans",spans))
        }
        checkCanceled()
        val index=documents.find(directory,INDEX)?.uri ?: documents.create(directory,"application/json",INDEX)
        documents.writeJson(index,JSONObject().put("id",request.id).put("fingerprint",fingerprint()).put("resources",resources))
        check(committed()) {"Не удалось проверить сохранённые файлы"}
    }
    fun isComplete(url: String)=readIndex().getJSONObject("resources").optJSONObject(url)?.optBoolean("complete") ?: false
    fun requireRemovable() {documents.requireWritable();folder()}
    fun remove() {folder()?.let {documents.delete(it)}}
    fun factory(): DataSource.Factory {
        val resources=readIndex().getJSONObject("resources")
        val entries=mutableMapOf<String,JSONObject>()
        resources.keys().forEach {key ->entries[key]=resources.getJSONObject(key)}
        entries.values.toList().forEach {entry ->entries.putIfAbsent(entry.getString("resolved"),entry)}
        return DataSource.Factory {TreeSpanDataSource(context,entries)}
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TreeSpanDataSource(private val context: Context,private val resources: Map<String,JSONObject>): BaseDataSource(false) {
    private var spec: DataSpec?=null
    private var resource: JSONObject?=null
    private var position=0L
    private var remaining=0L
    private var source: ContentDataSource?=null
    private var spanRemaining=0L
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val entry=resources[dataSpec.uri.toString()] ?: throw IOException("Сохранённый сегмент не найден")
        resource=entry;position=dataSpec.position
        val size=entry.getLong("length")
        val spans=entry.getJSONArray("spans")
        val end=(0 until spans.length()).maxOfOrNull {i ->spans.getJSONObject(i).let {it.getLong("start")+it.getLong("length")}} ?: 0L
        remaining=if(dataSpec.length!=C.LENGTH_UNSET.toLong()) dataSpec.length else (if(size>=0) size else end)-position
        if(remaining<0) throw IOException("Позиция за пределами сохранённого файла")
        spec=dataSpec;transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        if(source==null || spanRemaining==0L) {
            source?.close();source=null
            val spans=resource!!.getJSONArray("spans")
            val span=(0 until spans.length()).map {spans.getJSONObject(it)}.firstOrNull {position>=it.getLong("start") && position<it.getLong("start")+it.getLong("length")}
                ?: throw IOException("Сохранённый сегмент отсутствует. Повторите скачивание.")
            val start=position-span.getLong("start");spanRemaining=minOf(remaining,span.getLong("length")-start)
            source=ContentDataSource(context).also {it.open(DataSpec.Builder().setUri(span.getString("uri")).setPosition(start).setLength(spanRemaining).build())}
        }
        val read=source!!.read(buffer,offset,minOf(length.toLong(),spanRemaining,remaining).toInt())
        if(read<0) throw IOException("Сохранённый файл повреждён или недоступен")
        position+=read;remaining-=read;spanRemaining-=read;bytesTransferred(read)
        return read
    }
    override fun getUri(): Uri?=resource?.getString("resolved")?.let(Uri::parse)
    override fun close() {try {source?.close()} finally {source=null;resource=null;if(spec!=null) {spec=null;transferEnded()}}}
}
