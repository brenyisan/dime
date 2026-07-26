package com.dime.app

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class UploadWorker(appContext: android.content.Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "UploadWorker"
    private val client = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val CHUNK_SIZE = 4 * 1024 * 1024

    private fun baseApi(): String {
        val server = SessionManager.getServerUrl(applicationContext).trimEnd('/')
        return "$server/api"
    }

    override suspend fun doWork(): Result {
        val outputDirStr = inputData.getString("OUTPUT_DIR") ?: return Result.failure()
        val token = inputData.getString("TOKEN") ?: return Result.failure()
        var customName = inputData.getString("CUSTOM_NAME") ?: ""
        customName = customName.trim()
        if (customName.isEmpty()) customName = "video_${System.currentTimeMillis()}"

        val description = inputData.getString("DESCRIPTION") ?: ""
        val portadaUriStr = inputData.getString("PORTADA_URI") ?: ""

        val outputDir = File(outputDirStr)
        if (!outputDir.exists() || !outputDir.isDirectory) return Result.failure()

        val tsFiles = outputDir.listFiles { f -> f.name.endsWith(".ts") }?.sortedBy { it.name } ?: emptyList()
        if (tsFiles.isEmpty()) return Result.failure()

        // upload portada first if present
        var portadaSavedAs: String? = null
        if (portadaUriStr.isNotBlank()) {
            val portadaFile = UriUtils.uriToTempFile(applicationContext, portadaUriStr)
            if (portadaFile != null && portadaFile.exists()) {
                portadaSavedAs = uploadPortadaFile(portadaFile, token)
            }
        }

        val uploadId = "up-${UUID.randomUUID().toString().replace("-", "").take(28)}"
        val totalChunks = tsFiles.size
        var bytesUploaded = 0L
        val totalBytes = tsFiles.sumOf { it.length() }

        val already = queryReceivedChunks(uploadId, customName, token)

        try {
            for ((index, file) in tsFiles.withIndex()) {
                if (already.contains(index)) {
                    bytesUploaded += file.length()
                    setProgress(workDataOf("uploaded_bytes" to bytesUploaded, "total_bytes" to totalBytes, "part_index" to index, "part_progress" to 100, "total_parts" to totalChunks))
                    continue
                }

                // Log exact payload details before sending (helps debugging/comparison with Python client)
                Log.i(TAG, "Uploading chunk -> upload_id=$uploadId filename=$customName chunkIndex=$index partFile=${file.name}")

                // set part start
                setProgress(workDataOf("part_index" to index, "part_progress" to 0, "total_parts" to totalChunks))

                val ok = uploadTsChunk(uploadId, token, index, totalChunks, file, customName)
                if (!ok) {
                    // Try retry with appended .mp4 only if server rejects due to missing extension
                    if (!customName.contains('.')) {
                        val alt = "$customName.mp4"
                        Log.i(TAG, "Retry upload with alt filename: $alt")
                        val ok2 = uploadTsChunk(uploadId, token, index, totalChunks, file, alt)
                        if (!ok2) {
                            outputDir.deleteRecursively()
                            return Result.retry()
                        } else {
                            // update customName to alt for subsequent parts and finalization
                            customName = alt
                        }
                    } else {
                        outputDir.deleteRecursively()
                        return Result.retry()
                    }
                }

                bytesUploaded += file.length()
                val percent = if (totalBytes > 0) ((bytesUploaded.toDouble() / totalBytes) * 100).roundToInt() else 0
                setProgress(workDataOf("uploaded_bytes" to bytesUploaded, "total_bytes" to totalBytes, "part_index" to index, "part_progress" to 100, "total_parts" to totalChunks, "overall_percent" to percent))
            }

            // finalize
            val assignmentsObj = JSONObject()
            val assignArr = JSONArray()
            if (!portadaSavedAs.isNullOrBlank()) {
                val a = JSONObject()
                a.put("video", customName)
                a.put("image", "existing:${portadaSavedAs}")
                a.put("descripcion", description)
                assignArr.put(a)
            }
            assignmentsObj.put("assignments", assignArr)
            assignmentsObj.put("container_portadas", JSONObject())
            val descs = JSONObject()
            if (description.isNotBlank()) descs.put(customName, description)
            assignmentsObj.put("descriptions", descs)

            val finalized = finalizeUpload(uploadId, token, customName, assignmentsObj.toString())
            outputDir.deleteRecursively()
            return if (finalized) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "doWork error", e)
            outputDir.deleteRecursively()
            return Result.failure()
        }
    }

    private fun queryReceivedChunks(uploadId: String, filename: String, token: String): Set<Int> {
        try {
            val base = "${baseApi()}/upload/chunk/status"
            val url = base.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("upload_id", uploadId)
                ?.addQueryParameter("filename", filename)
                ?.addQueryParameter("token", token)
                ?.build() ?: return emptySet()
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptySet()
                val body = resp.body?.string().orEmpty()
                val jo = JSONObject(body)
                val arr = jo.optJSONArray("received") ?: return emptySet()
                val set = mutableSetOf<Int>()
                for (i in 0 until arr.length()) set.add(arr.getInt(i))
                return set
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryReceivedChunks error", e)
            return emptySet()
        }
    }

    private fun uploadPortadaFile(file: File, token: String): String? {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipart.addFormDataPart("carpeta", "")
        multipart.addFormDataPart("portada_mode", "container")
        multipart.addFormDataPart("generate_quick", "0")
        val mediaType = UriUtils.guessMimeType(file.name) ?: "application/octet-stream"
        multipart.addFormDataPart("files", file.name, file.asRequestBody(mediaType.toMediaTypeOrNull()))

        val request = Request.Builder()
            .url("${baseApi()}/upload")
            .addHeader("x-upload-token", token)
            .post(multipart.build())
            .build()

        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "uploadPortadaFile failed: ${resp.code} - $bodyStr")
                return null
            }
            try {
                val jo = JSONObject(bodyStr)
                val details = jo.optJSONArray("details")
                if (details != null && details.length() > 0) {
                    val d0 = details.getJSONObject(0)
                    return d0.optString("saved_as", null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "uploadPortadaFile parse error", e)
            }
            return null
        }
    }

    /**
     * Upload a single .ts part, matching Python client exactly:
     * - form fields: upload_id, filename (CUSTOM_NAME), chunk_index, total_chunks
     * - file part: field 'chunk', filename = partFile.name, content-type 'video/MP2T'
     */
    private fun uploadTsChunk(uploadId: String, token: String, chunkIndex: Int, totalChunks: Int, file: File, filenameForServer: String): Boolean {
        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipartBuilder.addFormDataPart("upload_id", uploadId)
        multipartBuilder.addFormDataPart("filename", filenameForServer)
        multipartBuilder.addFormDataPart("chunk_index", chunkIndex.toString())
        multipartBuilder.addFormDataPart("total_chunks", totalChunks.toString())
        multipartBuilder.addFormDataPart("chunk", file.name, file.asRequestBody("video/MP2T".toMediaTypeOrNull()))

        val request = Request.Builder()
            .url("${baseApi()}/upload/chunk")
            .addHeader("x-upload-token", token)
            .post(multipartBuilder.build())
            .build()

        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                return true
            } else {
                Log.w(TAG, "uploadTsChunk failed: code=${resp.code} body=$bodyStr upload_id=$uploadId filename=$filenameForServer chunkIndex=$chunkIndex")
                return false
            }
        }
    }

    private fun finalizeUpload(uploadId: String, token: String, videoName: String, assignmentsJson: String): Boolean {
        val manifestJson = "[{\"filename\":\"$videoName\"}]"

        val formBuilder = FormBody.Builder()
            .add("upload_id", uploadId)
            .add("carpeta", "")
            .add("portada_mode", "container")
            .add("generate_quick", "1")
            .add("manifest", manifestJson)
            .add("parts_kind", "ts")
            .add("assignments", assignmentsJson)

        val request = Request.Builder()
            .url("${baseApi()}/upload/finalize")
            .addHeader("x-upload-token", token)
            .post(formBuilder.build())
            .build()

        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "finalizeUpload failed: ${resp.code} - $bodyStr")
                return false
            }
            try {
                val jo = JSONObject(bodyStr)
                val details = jo.optJSONArray("details") ?: return false
                for (i in 0 until details.length()) {
                    val d = details.getJSONObject(i)
                    if (d.optString("status") != "saved") return false
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "finalizeUpload parse error", e)
                return resp.isSuccessful
            }
        }
    }
}
