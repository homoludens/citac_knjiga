package com.homoludens.citacknjiga.playback.export

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

public data class SafDocument(
    public val uri: Uri,
    public val name: String,
    public val mimeType: String?,
    public val isDirectory: Boolean,
)

public data class SafProviderCapabilities(
    public val supportsDocumentRename: Boolean,
    /** Free bytes reported by the provider, or null when SAF exposes no safe capacity value. */
    public val availableBytes: Long? = null,
)

/** The narrow provider boundary used by export; it never exposes filesystem paths. */
public interface SafDocumentTree {
    public val capabilities: SafProviderCapabilities get() = SafProviderCapabilities(supportsDocumentRename = false)

    public fun listChildren(): List<SafDocument>

    public fun createFile(name: String, mimeType: String): Uri?

    public fun openForWrite(uri: Uri): OutputStream?

    public fun openForRead(uri: Uri): InputStream? = null

    /** Returns the renamed document URI, or null when the provider cannot rename it. */
    public fun rename(uri: Uri, name: String): Uri? = null

    public fun delete(uri: Uri): Boolean
}

/** ContentResolver implementation for a URI returned by ACTION_OPEN_DOCUMENT_TREE. */
public class ContentResolverDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : SafDocumentTree {
    override val capabilities: SafProviderCapabilities = SafProviderCapabilities(supportsDocumentRename = true)

    private val documentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    override fun listChildren(): List<SafDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(documentUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return buildList {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(id))
                    val childMime = cursor.getString(mime)
                    add(SafDocument(childUri, cursor.getString(name), childMime, childMime == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
    }

    override fun createFile(name: String, mimeType: String): Uri? =
        DocumentsContract.createDocument(resolver, documentUri, mimeType, name)

    override fun openForWrite(uri: Uri): OutputStream? = resolver.openOutputStream(uri, "wt")

    override fun openForRead(uri: Uri): InputStream? = resolver.openInputStream(uri)

    override fun rename(uri: Uri, name: String): Uri? = DocumentsContract.renameDocument(resolver, uri, name)

    override fun delete(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)
}

/** Persists write access when the selected provider supports it. */
public object SafDocumentTreePermissions {
    public fun persistWritePermission(resolver: ContentResolver, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
    }
}
