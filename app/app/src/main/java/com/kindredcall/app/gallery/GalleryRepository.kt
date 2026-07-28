package com.kindredcall.app.gallery

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.kindredcall.app.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

class GalleryRepository(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson = Gson(),
) {
    suspend fun fetchImageUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(IMAGES_API_URL)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Server error: ${response.code}"))
                }

                val body = response.body?.string() ?: return@withContext Result.success(emptyList())
                val filenames = gson.fromJson(body, Array<String>::class.java) ?: return@withContext Result.success(emptyList())

                // Latest first
                val urls = filenames.reversed().map { filename ->
                    "$UPLOADS_BASE_URL/$filename"
                }
                Result.success(urls)
            }
        } catch (e: Exception) {
            Log.e("GalleryRepository", "Fetch error", e)
            Result.failure(e)
        }
    }

    suspend fun uploadImage(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "image.jpg"

            val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("Cannot read file"))
            val bytes = inputStream.readBytes()
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    fileName,
                    bytes.toRequestBody("image/*".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url(UPLOAD_API_URL)
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Server error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e("GalleryRepository", "Upload error", e)
            Result.failure(e)
        }
    }

    fun getLatestImageUri(context: Context): Uri? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            } else null
        }
    }

    companion object {
        private val BASE_URL = BuildConfig.API_BASE_URL
        private val IMAGES_API_URL = "$BASE_URL/api/images"
        private val UPLOAD_API_URL = "$BASE_URL/api/upload"
        private val UPLOADS_BASE_URL = "$BASE_URL/uploads"
    }
}
