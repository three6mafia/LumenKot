package com.lumenkot.app

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

class TestFolderProvider: android.content.ContentProvider() {
    companion object { lateinit var root: File }
    override fun onCreate()=true
    private fun file(id: String)=File(root,id.removePrefix("root").trimStart('/'))
    private fun cursor(projection: Array<out String>?,ids: List<String>): Cursor {
        val columns=projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { ids.forEach { id ->
            val file=file(id)
            if(file.exists()) addRow(columns.map<String,Any?> { column -> when(column) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> file.name
                Document.COLUMN_MIME_TYPE -> if(file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or if(file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0
                Document.COLUMN_SIZE -> file.length()
                Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                else -> null
            } }.toTypedArray())
        } }
    }
    fun queryDocument(documentId: String,projection: Array<out String>?)=cursor(projection,listOf(documentId))
    fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?)=cursor(projection,file(parentDocumentId).listFiles().orEmpty().map { "$parentDocumentId/${it.name}" })
    fun openDocument(documentId: String,mode: String,signal: CancellationSignal?)=ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
    fun createDocument(parentDocumentId: String,mimeType: String,displayName: String): String {
        val id="$parentDocumentId/$displayName";val file=file(id)
        if(mimeType==Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        return id
    }
    fun deleteDocument(documentId: String) { check(file(documentId).deleteRecursively()) }
    fun isChildDocument(parentDocumentId: String,documentId: String)=documentId.startsWith("$parentDocumentId/")
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor {
        val id=android.provider.DocumentsContract.getDocumentId(uri)
        return if(uri.lastPathSegment=="children") queryChildDocuments(id,projection,sortOrder) else queryDocument(id,projection)
    }
    override fun getType(uri: Uri)=if(file(android.provider.DocumentsContract.getDocumentId(uri)).isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
    override fun openFile(uri: Uri,mode: String)=openDocument(android.provider.DocumentsContract.getDocumentId(uri),mode,null)
    override fun insert(uri: Uri,values: android.content.ContentValues?): Uri? = null
    override fun update(uri: Uri,values: android.content.ContentValues?,selection: String?,selectionArgs: Array<out String>?)=0
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=0
    override fun call(method: String,arg: String?,extras: android.os.Bundle?): android.os.Bundle {
        val uri=extras!!.getParcelable("uri",Uri::class.java)!!
        val id=android.provider.DocumentsContract.getDocumentId(uri)
        return when(method) {
            "android:createDocument" -> android.os.Bundle().apply {
                val child=createDocument(id,extras.getString("mime_type")!!,extras.getString("_display_name")!!)
                putParcelable("uri",android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,child))
            }
            "android:deleteDocument" -> android.os.Bundle().also { deleteDocument(id) }
            else -> error("Unexpected document method: $method")
        }
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FolderExportTest {
    @Test fun hlsExportsTwoDistinctSegmentsIntoChosenFolderWithoutNetwork()=checkExport(true)
    @Test fun mp4ExportsIntoChosenFolderWithoutNetwork()=checkExport(false)
    private fun checkExport(hls: Boolean) {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        TestFolderProvider.root=File(context.cacheDir,"user-folder").apply { deleteRecursively();mkdirs() }
        val provider=Robolectric.buildContentProvider(TestFolderProvider::class.java).create(ProviderInfo().apply {
            authority="test.folders";exported=true;grantUriPermissions=true
            readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission
        }).get()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("test.folders",provider)
        context.grantUriPermission(context.packageName,Uri.parse("content://test.folders/tree/root"),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        // Robolectric does not model tree URI grants; emulate Android's picker grant for this provider only.
        val granted=object: android.content.ContextWrapper(context) {
            override fun checkCallingOrSelfUriPermission(uri: Uri,modeFlags: Int): Int =
                if(uri.authority=="test.folders") android.content.pm.PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfUriPermission(uri,modeFlags)
        }
        val server=MockWebServer();server.start()
        val playlist="#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nfirst.ts\n#EXTINF:1,\nsecond.ts\n#EXT-X-ENDLIST\n"
        server.enqueue(MockResponse().setBody(if(hls) playlist else "mp4 video bytes"))
        if(hls) { server.enqueue(MockResponse().setBody("first segment"));server.enqueue(MockResponse().setBody("second segment")) }
        val url=server.url(if(hls) "/video.m3u8" else "/video.mp4").toString()
        val database=StandaloneDatabaseProvider(context)
        val cache=SimpleCache(File(context.cacheDir,"video-cache").apply { deleteRecursively() },NoOpCacheEvictor(),database)
        try {
            val factory=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            val item=MediaItem.Builder().setUri(url).setMimeType(if(hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4).build()
            if(hls) HlsDownloader(item,factory).download(null) else ProgressiveDownloader(item,factory).download(null)
            server.shutdown()
            val metadata=JSONObject().put("title","Test movie").put("format",if(hls) "HLS" else "MP4").put("tree","content://test.folders/tree/root").put("includeSubtitles",false)
            val rootDoc=DocumentFile.fromTreeUri(granted,Uri.parse("content://test.folders/tree/root"))!!
            assertTrue(rootDoc.isDirectory);assertTrue(rootDoc.canWrite())
            val media=SavedMediaExporter(granted,CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null)).export("download-id",url,metadata)
            fun read(uri: String)=context.contentResolver.openInputStream(Uri.parse(uri))!!.bufferedReader().use { it.readText() }
            val local=read(media.getString("url"))
            assertTrue(media.getString("url").startsWith("content://test.folders/"))
            if(hls) {
                val segments=local.lines().filter { it.startsWith("content:") }
                assertEquals(2,segments.distinct().size)
                assertEquals(setOf("first segment","second segment"),segments.map { read(it) }.toSet())
                assertFalse(local.contains("http://"))
            } else assertEquals("mp4 video bytes",local)
            assertEquals(1,TestFolderProvider.root.listFiles()!!.size)
            DocumentFile.fromTreeUri(granted,Uri.parse("content://test.folders/tree/root"))!!.listFiles().single().delete()
            assertTrue(TestFolderProvider.root.listFiles()!!.isEmpty())
        } finally { cache.release();database.close();runCatching { server.shutdown() } }
    }
}
