package com.example.recordings

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class RecordedVideoItem(
  val id: Long,
  val uri: Uri,
  val displayName: String,
  val durationMs: Long,
  val sizeBytes: Long,
  val dateAddedSec: Long,
  val width: Int,
  val height: Int,
)

class RecordingsRepository(private val context: Context) {

  suspend fun loadRecordings(): List<RecordedVideoItem> =
    withContext(Dispatchers.IO) { queryMediaStoreRecordings() }

  suspend fun deleteRecording(item: RecordedVideoItem): Boolean =
    withContext(Dispatchers.IO) {
      runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false)
    }

  private fun queryMediaStoreRecordings(): List<RecordedVideoItem> {
    val results = mutableListOf<RecordedVideoItem>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection =
      arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
      )
    val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
    val selectionArgs = arrayOf("%Movies/FrameCut%")
    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

    context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use {
      cursor ->
      val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
      val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
      val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
      val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
      val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
      val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
      val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

      while (cursor.moveToNext()) {
        val id = cursor.getLong(idCol)
        val uri = ContentUris.withAppendedId(collection, id)
        results.add(
          RecordedVideoItem(
            id = id,
            uri = uri,
            displayName = cursor.getString(nameCol) ?: "Recording.mp4",
            durationMs = cursor.getLong(durCol).coerceAtLeast(0L),
            sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L),
            dateAddedSec = cursor.getLong(dateCol).coerceAtLeast(0L),
            width = cursor.getInt(wCol).coerceAtLeast(0),
            height = cursor.getInt(hCol).coerceAtLeast(0),
          ),
        )
      }
    }
    return results
  }
}
