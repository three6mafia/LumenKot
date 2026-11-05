package com.lumenkot.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Real DocumentsContract calls with file-backed content URIs in a test-only provider. */
class FixtureDocumentsProvider: DocumentsProvider() {
    lateinit var directory: File
    var writable=true
    override fun onCreate(): Boolean {directory=File(context!!.filesDir,"document-fixture").apply {mkdirs()};return true}
    private fun file(id: String)=File(directory,id.removePrefix("root").trimStart('/')).canonicalFile.also {check(it==directory.canonicalFile || it.path.startsWith(directory.canonicalPath+"/"))}
    private fun id(file: File)="root"+file.relativeTo(directory).path.takeIf {it.isNotBlank()}?.let {"/$it"}.orEmpty()
    private val documentColumns=arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_FLAGS)
    private fun add(cursor: MatrixCursor,file: File) {
        val values=mapOf<String,Any>(Document.COLUMN_DOCUMENT_ID to id(file),Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if(file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",Document.COLUMN_SIZE to file.length(),
            Document.COLUMN_FLAGS to if(!writable) 0 else Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE or if(file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        cursor.addRow(cursor.columnNames.map {values[it]})
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS)).apply {addRow(arrayOf<Any>("root","root","Fixture",Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY))}
    override fun queryDocument(documentId: String,projection: Array<out String>?): Cursor=MatrixCursor(projection ?: documentColumns).apply {val f=file(documentId);if(f.exists()) add(this,f)}
    override fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?): Cursor=MatrixCursor(projection ?: documentColumns).apply {file(parentDocumentId).listFiles()?.forEach {add(this,it)}}
    override fun createDocument(parentDocumentId: String,mimeType: String,displayName: String): String {
        check(writable);val child=File(file(parentDocumentId),displayName)
        check(if(mimeType==Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile());return id(child)
    }
    override fun openDocument(documentId: String,mode: String,signal: CancellationSignal?): ParcelFileDescriptor=ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
    override fun deleteDocument(documentId: String) {val f=file(documentId);check(f!=directory.canonicalFile && f.deleteRecursively())}
    override fun isChildDocument(parentDocumentId: String,documentId: String)=file(documentId).path.startsWith(file(parentDocumentId).path+"/")
}
