package com.lumenkot.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A persisted SAF folder grant; an empty preference retains the legacy app folder. */
class DownloadLocations(private val context: Context) {
    private val prefs=Settings(context).prefs
    var always: Boolean
        get()=prefs.getBoolean("rememberDownloadPath",false)
        set(value) {prefs.edit().putBoolean("rememberDownloadPath",value).apply()}
    val tree: Uri? get()=prefs.getString("downloadTree",null)?.takeIf {it.isNotBlank()}?.let(Uri::parse)
    fun hasAccess(uri: Uri,write: Boolean=true)=context.contentResolver.persistedUriPermissions.any {it.uri==uri && it.isReadPermission && (!write || it.isWritePermission)}
    fun requireAccess(uri: Uri) {
        try {check(hasAccess(uri));TreeDocuments(context,uri).requireWritable()} catch(e: Exception) {throw DownloadFolderException(e)}
    }
    fun remember(uri: Uri,flags: Int) {
        val access=flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(access==(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {"Нужен доступ на чтение и запись в папку"}
        context.contentResolver.takePersistableUriPermission(uri,access)
        TreeDocuments(context,uri).requireWritable()
        prefs.edit().putString("downloadTree",uri.toString()).apply()
    }
    fun appFolder() {prefs.edit().remove("downloadTree").apply()}
    fun label(): String=tree?.let(::label) ?: runCatching {appRoot().absolutePath}.getOrDefault("Папка приложения недоступна")
    fun label(uri: Uri): String {
        val id=runCatching {DocumentsContract.getTreeDocumentId(uri)}.getOrDefault(uri.lastPathSegment.orEmpty())
        return if(id.startsWith("primary:")) "Внутренняя память/"+id.substringAfter(':') else id.replace(':','/')
    }
    fun picker(): Intent=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY,true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        .apply {if(android.os.Build.VERSION.SDK_INT>=26) tree?.let {putExtra(DocumentsContract.EXTRA_INITIAL_URI,it)}}
    fun appRoot(tree: Uri?=null): File {
        val roots=context.getExternalFilesDirs("downloads").filterNotNull()
        if(tree?.authority=="com.android.externalstorage.documents") {
            val volume=DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
            val chosen=if(volume=="primary") roots.firstOrNull() else roots.firstOrNull {root ->root.path.split('/').any {it.equals(volume,true)}}
            if(chosen!=null) return chosen
        }
        return roots.getOrNull(prefs.getInt("downloadStorage",0)) ?: error("Выбранный накопитель недоступен")
    }
}
class DownloadFolderException(cause: Throwable): java.io.IOException("Папка недоступна. Выберите её заново.",cause)

/** Minimal DocumentsContract wrapper: all file access stays within the granted document tree. */
class TreeDocuments(private val context: Context,val tree: Uri) {
    val resolver get()=context.contentResolver
    val root: Uri get()=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
    data class Entry(val uri: Uri,val name: String,val mime: String,val size: Long,val flags: Int)
    private val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS)
    private fun query(uri: Uri)=if(android.os.Build.VERSION.SDK_INT>=26) resolver.query(uri,columns,android.os.Bundle.EMPTY,null) else resolver.query(uri,columns,null,null,null)
    fun children(parent: Uri): List<Entry> {
        val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
        return query(uri)?.use {cursor ->buildList {
            while(cursor.moveToNext()) add(Entry(DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)),cursor.getString(1),cursor.getString(2),cursor.getLong(3),cursor.getInt(4)))
        }} ?: error("Папка недоступна. Выберите её заново.")
    }
    fun requireWritable() {
        query(root)?.use {cursor ->
            check(cursor.moveToFirst() && cursor.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR && cursor.getInt(4) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE!=0) {"Выберите доступную для записи папку"}
        } ?: error("Папка недоступна. Выберите её заново.")
    }
    fun find(parent: Uri,name: String)=children(parent).firstOrNull {it.name==name}
    fun create(parent: Uri,mime: String,name: String): Uri=DocumentsContract.createDocument(resolver,parent,mime,name) ?: error("Не удалось создать файл в выбранной папке")
    fun readJson(uri: Uri)=resolver.openInputStream(uri)?.bufferedReader()?.use {JSONObject(it.readText())} ?: error("Файл загрузки недоступен")
    fun writeJson(uri: Uri,data: JSONObject) {resolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use {it.write(data.toString())} ?: error("Не удалось записать файл загрузки")}
    fun delete(uri: Uri) {check(DocumentsContract.deleteDocument(resolver,uri)) {"Не удалось удалить загрузку"}}
}

/** Only the selected film/voice/episode/stream is needed while a folder picker recreates the Activity. */
data class PendingDownload(val film: Film,val voice: Voice,val stream: Stream,val season: String,val episode: String,val subtitles: List<Subtitle>) {
    fun json()=JSONObject().put("id",film.card.id).put("title",film.card.title).put("page",film.card.url).put("image",film.card.image)
        .put("voice",voice.id).put("voiceTitle",voice.title).put("url",stream.url).put("quality",stream.quality).put("premium",stream.premium)
        .put("season",season).put("episode",episode).put("subtitles",JSONArray(subtitles.map {JSONObject().put("title",it.title).put("url",it.url).put("language",it.language).put("default",it.default)}))
    companion object {
        fun from(data: JSONObject): PendingDownload {
            val voice=Voice(data.getString("voice"),data.optString("voiceTitle"))
            val film=Film(Card(data.getString("id"),data.getString("title"),data.getString("page"),data.optString("image")),"","","",listOf(voice),emptyList(),null,emptyList(),emptyList(),emptyList(),"","")
            val subs=data.optJSONArray("subtitles") ?: JSONArray()
            return PendingDownload(film,voice,Stream(data.getString("quality"),data.getString("url"),data.optBoolean("premium")),data.optString("season"),data.optString("episode"),
                (0 until subs.length()).map {i ->subs.getJSONObject(i).let {Subtitle(it.getString("title"),it.getString("url"),it.optString("language"),it.optBoolean("default"))}})
        }
    }
}
