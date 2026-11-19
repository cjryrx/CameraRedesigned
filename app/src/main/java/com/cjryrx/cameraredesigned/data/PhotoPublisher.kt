package com.cjryrx.cameraredesigned.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pending MediaStore entries are never exposed until their bytes have been written. */
fun interface PhotoSink { suspend fun publish(file: File, name: String): Uri }

class PhotoPublisher(context: Context) : PhotoSink {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val path = "DCIM/CameraRedesigned/"
    override suspend fun publish(file: File, name: String): Uri = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "Photo is empty" }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name, path))
            if (Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        @Suppress("DEPRECATION")
        val queryCollection = if (Build.VERSION.SDK_INT >= 30) collection else MediaStore.setIncludePending(collection)
        var pending: Uri? = null
        checkNotNull(resolver.query(queryCollection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING), args, null)).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                if (cursor.getInt(1) == 0) return@withContext uri
                pending = uri
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (name.endsWith(".dng")) "image/x-adobe-dng" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = pending ?: checkNotNull(resolver.insert(collection, values)) { "Unable to create photo in MediaStore" }
        // A failed/interrupted write stays private. Recovery truncates the same row and starts over.
        val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "rwt"))
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
            val bytes = file.inputStream().use { it.copyTo(out) }
            check(bytes == file.length()) { "Incomplete photo copy" }
            out.fd.sync()
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        uri
    }
}
