package com.ivehement.saf.api.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.os.Build
import android.provider.DocumentsContract
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.ivehement.saf.plugin.API_19
import com.ivehement.saf.plugin.API_21
import com.ivehement.saf.plugin.API_24
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * Helper class to make more easy to handle callbacks using Kotlin syntax
 */
data class CallbackHandler<T>(
  var onSuccess: (T.() -> Unit)? = null,
  var onEnd: (() -> Unit)? = null
)

/**
 * Generate the `DocumentFile` reference from string `uri` (Single `DocumentFile`)
 */
@RequiresApi(API_21)
fun documentFromSingleUri(context: Context, uri: String): DocumentFile? =
  documentFromSingleUri(context, Uri.parse(uri))

/**
 * Generate the `DocumentFile` reference from string `uri` (Single `DocumentFile`)
 */
@RequiresApi(API_21)
fun documentFromSingleUri(context: Context, uri: Uri): DocumentFile? {
  val documentUri = DocumentsContract.buildDocumentUri(
    uri.authority,
    DocumentsContract.getDocumentId(uri)
  )

  return DocumentFile.fromSingleUri(context, documentUri)
}

/**
 * Generate the `DocumentFile` reference from string `uri`
 */
@RequiresApi(API_21)
fun documentFromUri(context: Context, uri: String): DocumentFile? =
  documentFromUri(context, Uri.parse(uri))

/**
 * Generate the `DocumentFile` reference from URI `uri`
 */
@RequiresApi(API_21)
fun documentFromUri(context: Context, uri: Uri): DocumentFile? {
  return if (isTreeUri(uri)) {
    DocumentFile.fromTreeUri(context, uri)
  } else {
    DocumentFile.fromSingleUri(
      context,
      DocumentsContract.buildDocumentUriUsingTree(
        uri,
        DocumentsContract.getDocumentId(uri)
      )
    )
  }
}

/**
 * Standard map encoding of a `DocumentFile` and must be used before returning any `DocumentFile`
 * from plugin results, like:
 * ```dart
 * result.success(createDocumentFileMap(documentFile))
 * ```
 */
fun createDocumentFileMap(documentFile: DocumentFile?): Map<String, Any?>? {
  if (documentFile == null) return null

  return mapOf(
    "isDirectory" to documentFile.isDirectory,
    "isFile" to documentFile.isFile,
    "isVirtual" to documentFile.isVirtual,
    "name" to (documentFile.name ?: ""),
    "type" to (documentFile.type ?: ""),
    "uri" to "${documentFile.uri}",
    "exists" to "${documentFile.exists()}"
  )
}


/**
 * Standard map encoding of a row result of a `DocumentFile`
 * ```dart
 * result.success(createDocumentFileMap(documentFile))
 * ```
 * Example:
 * ```py
 * input = {
 *   "last_modified": 2939496, /// Key from DocumentsContract.Document.COLUMN_LAST_MODIFIED
 *   "_display_name": "MyFile" /// Key from DocumentsContract.Document.COLUMN_DISPLAY_NAME
 * }
 *
 * output = createCursorRowMap(input)
 *
 * print(output)
 * {
 *   "lastModified": 2939496,
 *   "displayName": "MyFile"
 * }
 * ```
 */
fun createCursorRowMap(
  rootUri: Uri,
  parentUri: Uri,
  uri: Uri,
  data: Map<String, Any>,
  isDirectory: Boolean?
): Map<String, Any> {
  val values = DocumentFileColumn.values()

  val formattedData = mutableMapOf<String, Any>()

  for (value in values) {
    val key = parseDocumentFileColumn(value)!!

    if (data[key] != null) {
      formattedData[documentFileColumnToRawString(value)!!] = data[key]!!
    }
  }

  // Include critical raw columns that might not be in DocumentFileColumn enum
  // These are needed for file type detection and display
  val mimeType = data[DocumentsContract.Document.COLUMN_MIME_TYPE]
  val displayName = data[DocumentsContract.Document.COLUMN_DISPLAY_NAME]

  Log.d("SAF_CURSOR_ROW", "createCursorRowMap - mimeType from data: $mimeType, displayName: $displayName")
  Log.d("SAF_CURSOR_ROW", "createCursorRowMap - data keys: ${data.keys}")

  if (mimeType != null) {
    formattedData[DocumentsContract.Document.COLUMN_MIME_TYPE] = mimeType
    Log.d("SAF_CURSOR_ROW", "Added MIME_TYPE to formattedData")
  }
  if (displayName != null) {
    formattedData[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = displayName
    Log.d("SAF_CURSOR_ROW", "Added DISPLAY_NAME to formattedData")
  }

  // Explicitly add LAST_MODIFIED for sorting/filtering
  val lastModified = data[DocumentsContract.Document.COLUMN_LAST_MODIFIED]
  if (lastModified != null) {
    formattedData[DocumentsContract.Document.COLUMN_LAST_MODIFIED] = lastModified
    Log.d("SAF_CURSOR_ROW", "Added LAST_MODIFIED to formattedData: $lastModified")
  }

  Log.d("SAF_CURSOR_ROW", "formattedData keys after: ${formattedData.keys}")

  return mapOf(
    "data" to formattedData,
    "metadata" to mapOf(
      "parentUri" to "$parentUri",
      "rootUri" to "$rootUri",
      "isDirectory" to isDirectory,
      "uri" to "$uri"
    )
  )
}

/**
 * Util method to close a closeable
 */
fun closeQuietly(closeable: Closeable?) {
  if (closeable != null) {
    try {
      closeable.close()
    } catch (e: RuntimeException) {
      throw e
    } catch (ignore: Exception) {
    }
  }
}

@RequiresApi(API_21)
fun traverseDirectoryEntries(
  contentResolver: ContentResolver,
  rootUri: Uri,
  columns: Array<String>,
  rootOnly: Boolean,
  block: (data: Map<String, Any>) -> Unit
) {
  Log.d("SAF_TRAVERSE", "Starting traverseDirectoryEntries with rootUri: $rootUri, rootOnly: $rootOnly")

  // Extract the document ID based on URI structure
  // Tree-only URI (root): content://.../tree/74D0-7424%3A → use getTreeDocumentId
  // Document URI (subfolder): content://.../tree/.../document/... → use getDocumentId
  val pathSegments = rootUri.pathSegments
  val hasDocumentSegment = pathSegments.contains("document")

  val documentId = if (hasDocumentSegment) {
    DocumentsContract.getDocumentId(rootUri)
  } else {
    DocumentsContract.getTreeDocumentId(rootUri)
  }
  Log.d("SAF_TRAVERSE", "Extracted documentId: $documentId (hasDocumentSegment: $hasDocumentSegment)")

  val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
    rootUri,
    documentId
  )

  Log.d("SAF_TRAVERSE", "Built childrenUri: $childrenUri")

  /// Keep track of our directory hierarchy
  val dirNodes = mutableListOf<Pair<Uri, Uri>>(Pair(rootUri, childrenUri))
  Log.d("SAF_TRAVERSE", "Initial dirNodes size: ${dirNodes.size}")

  while (dirNodes.isNotEmpty()) {
    val (parent, children) = dirNodes.removeAt(0)
    Log.d("SAF_TRAVERSE", "Processing directory node: parent=$parent, children=$children")

    val requiredColumns = if (rootOnly) emptyArray() else arrayOf(
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_DOCUMENT_ID
    )

    val projection = arrayOf(*columns, *requiredColumns).toSet().toTypedArray()
    Log.d("SAF_TRAVERSE", "Query projection: ${projection.joinToString()}")

    val cursor = contentResolver.query(
      children,
      projection,
      /// TODO: Add support for `selection`, `selectionArgs` and `sortOrder`
      null,
      null,
      null
    )
    
    if (cursor == null) {
      Log.w("SAF_TRAVERSE", "Cursor is null for children URI: $children")
      return
    }
    
    Log.d("SAF_TRAVERSE", "Cursor created successfully, count: ${cursor.count}")

    try {
      while (cursor.moveToNext()) {
        val data = mutableMapOf<String, Any>()

        for (column in projection) {
          try {
            val columnIndex = cursor.getColumnIndex(column)
            if (columnIndex >= 0) {
              data[column] = cursorHandlerOf(typeOfColumn(column)!!)(
                cursor,
                columnIndex
              )
            }
          } catch (e: Exception) {
            Log.w("SAF_TRAVERSE", "Failed to get column $column: ${e.message}")
          }
        }

        val mimeType = data[DocumentsContract.Document.COLUMN_MIME_TYPE] as String?
        val id = data[DocumentsContract.Document.COLUMN_DOCUMENT_ID] as String?
        
        Log.d("SAF_TRAVERSE", "Raw cursor data - mimeType: $mimeType, id: $id")

        val isDirectory = if (mimeType != null) isDirectory(mimeType) else null

        val uri = DocumentsContract.buildDocumentUriUsingTree(
          parent,
          DocumentsContract.getDocumentId(
            DocumentsContract.buildDocumentUri(parent.authority, id)
          )
        )

        block(createCursorRowMap(rootUri, parent, uri, data, isDirectory = isDirectory))

        // Only traverse into subdirectories if rootOnly is false (recursive mode)
        if (!rootOnly && isDirectory != null && isDirectory) {
          val nextChildren = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, id)
          val nextNode = Pair(uri, nextChildren)

          dirNodes.add(nextNode)
          Log.d("SAF_TRAVERSE", "Added subdirectory to queue for recursive traversal: $uri")
        }
      }
    } finally {
      closeQuietly(cursor)
    }
  }
}

@RequiresApi(API_19)
private fun isDirectory(mimeType: String): Boolean {
  return DocumentsContract.Document.MIME_TYPE_DIR == mimeType
}

fun bitmapToBase64(bitmap: Bitmap): String {
  val outputStream = ByteArrayOutputStream()

  val fullQuality = 100

  bitmap.compress(Bitmap.CompressFormat.PNG, fullQuality, outputStream)

  return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

/**
 * Trick to verify if is a tree URi even not in API 26+
 */
fun isTreeUri(uri: Uri): Boolean {
  if (Build.VERSION.SDK_INT >= API_24) {
    return DocumentsContract.isTreeUri(uri)
  }

  val paths = uri.pathSegments

  return paths.size >= 2 && "tree" == paths[0]
}

/**
 * extract the file ID i.e name from URI
 */
fun nameFromFileUri(uri: Uri): String? {
  if(!isTreeUri(uri)) return null
  try {
    val pathSegments = uri.getPath().toString().split("/")
    val fileName = pathSegments[pathSegments.size - 1]
    return fileName
  }
  catch(e: Exception) {
    Log.e("NAME_FROM_FILE_PATH_EXCEPTION", e.message.toString())
  }
  return null
}

/**
 * Get the list of Uri implementing `DocumentsContract.buildChildDocumentsUriUsingTree`
 */
fun buildChildDocumentsUriUsingTree(treeUri: Uri, contentResolver: ContentResolver): List<Uri>? {
  if(Build.VERSION.SDK_INT >= 21 && isTreeUri(treeUri)) {
    val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    var childrenUris = listOf<Uri>()
    val cursor = contentResolver.query(
      parentUri, arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ),
        null, null, null
        )
    try {
      while (cursor!!.moveToNext()) {
        val docId = cursor.getString(0)
        val eachUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, docId).toString().replace("/children", "")
        childrenUris += Uri.parse(eachUri)
      }
      return childrenUris
    }
    catch(e: Exception) {
      Log.e("CONTENT_RESOLVER_EXCEPTION: ", e.message!!)
    }
    finally {
      if (cursor != null) {
          try {
              cursor.close()
          } catch (re: RuntimeException) {
              
          }
      } 
    }
  }
  return null
}