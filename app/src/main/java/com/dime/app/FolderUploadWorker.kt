package com.dime.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class FolderUploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "FolderUploadWorker"
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
        val folderUriStr = inputData.getString("FOLDER_URI") ?: return Result.failure()
        val token = inputData.getString("TOKEN") ?: return Result.failure()
        val customName = inputData.getString("CUSTOM_NAME") ?: "Folder_${System.currentTimeMillis()}"
        val description = inputData.getString("DESCRIPTION") ?: ""
        val portadaUriStr = inputData.getString("PORTADA_URI") ?: ""

        val ctx = applicationContext
        val tree = Uri.parse(folderUriStr)
        val docFile = DocumentFile.fromTreeUri(ctx, tree) ?: return Result.failure()

        val files = mutableListOf<Pair<DocumentFile, String>>()
        collectFilesRec(docFile, "", files, docFile)
        if (files.isEmpty()) return Result.failure()

        var portadaSavedAs: String? = null
        if (portadaUriStr.isNotBlank()) {
            val tmp = UriUtils.uriToTempFile(ctx, portadaUriStr)
            if (tmp != null) {
                portadaSavedAs = uploadPortadaFile(tmp, token)
            }
        }

        try {
            val manifestArr = JSONArray()
            for ((doc, relPath) in files) {
                val rel = relPath.trim()
                val size = doc.length()
                val totalChunks = if (size <= 0) 1 else ceil(size.toDouble() / CHUNK_SIZE.toDouble()).toInt()
                val uploadId = "up-${UUID.randomUUID().toString().replace("-", "").take(28)}"

                val received = queryReceivedChunks(uploadId, rel, token)

                ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                    var idx = 0
                    val buf = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        if (received.contains(idx)) {
                            idx++
                            continue
                        }
                        val bytes = if (read == buf.size) buf else buf.copyOf(read)

                        // Report progress start for this chunk
                        setProgress(Data.Builder().putInt("part_index", idx).putInt("part_progress", 0).putInt("total_parts_for_file", totalChunks).build())

                        val ok = uploadChunkBytesWithRecovery(uploadId, token, rel, idx, totalChunks, bytes)
                        if (!ok) return Result.retry()

                        // Report finished chunk
                        setProgress(Data.Builder().putInt("part_index", idx).putInt("part_progress", 100).putInt("total_parts_for_file", totalChunks).build())
                        idx++
                    }
                }

                val o = JSONObject()
                o.put("filename", rel)
                manifestArr.put(o)
            }

            val assignmentsObj = JSONObject()
            if (!portadaSavedAs.isNullOrBlank()) {
                assignmentsObj.put("container_portadas", JSONObject().put(customName, portadaSavedAs))
            } else {
                assignmentsObj.put("container_portadas", JSONObject())
            }
            assignmentsObj.put("assignments", JSONArray())
            val descs = JSONObject()
            if (description.isNotBlank()) descs.put(customName, description)
            assignmentsObj.put("descriptions", descs)

            val uploadIdForFinalize = "up-${UUID.randomUUID().toString().replace("-", "").take(28)}"
            val form = FormBody.Builder()
                .add("upload_id", uploadIdForFinalize)
                .add("carpeta", customName)
                .add("portada_mode", "container")
                .add("generate_quick", "1")
                .add("manifest", manifestArr.toString())
                .add("parts_kind", "raw")
                .add("assignments", assignmentsObj.toString())
                .build()

            val req = Request.Builder()
                .url("${baseApi()}/upload/finalize")
                .addHeader("x-upload-token", token)
                .post(form)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "finalize failed: ${resp.code} - $body")
                    return Result.retry()
                }
                return Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "doWork failed", e)
            e.printStackTrace()
            return Result.failure()
        }
    }

    private fun collectFilesRec(root: DocumentFile, baseRel: String, acc: MutableList<Pair<DocumentFile, String>>, current: DocumentFile) {
        if (current.isFile) {
            val name = current.name?.trim() ?: return
            val rel = if (baseRel.isBlank()) name else "$baseRel/$name"
            acc.add(Pair(current, rel))
        } else if (current.isDirectory) {
            val currentBase = if (baseRel.isBlank()) (current.name ?: "") else "$baseRel/${current.name ?: ""}"
            for (c in current.listFiles()) {
                collectFilesRec(root, currentBase, acc, c)
            }
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

    private fun uploadChunkBytesWithRecovery(uploadId: String, token: String, filename: String, chunkIndex: Int, totalChunks: Int, bytes: ByteArray): Boolean {
        val ok = uploadChunkBytes(uploadId, token, filename, chunkIndex, totalChunks, bytes)
        if (ok) return true

        // If filename lacks dot/extension, try with .mp4 appended
        if (!filename.contains('.')) {
            val alt = "$filename.mp4"
            Log.i(TAG, "Retrying chunk with alt filename: $alt")
            return uploadChunkBytes(uploadId, token, alt, chunkIndex, totalChunks, bytes)
        }
        return false
    }

    private fun uploadChunkBytes(uploadId: String, token: String, filename: String, chunkIndex: Int, totalChunks: Int, bytes: ByteArray): Boolean {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipart.addFormDataPart("upload_id", uploadId)
        multipart.addFormDataPart("filename", filename)
        multipart.addFormDataPart("chunk_index", chunkIndex.toString())
        multipart.addFormDataPart("total_chunks", totalChunks.toString())
        // field name "chunk", filename "chunk" (server-compatible) and bytes body
        multipart.addFormDataPart("chunk", "chunk", bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))

        val req = Request.Builder()
            .url("${baseApi()}/upload/chunk")
            .addHeader("x-upload-token", token)
            .post(multipart.build())
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                return true
            } else {
                Log.w(TAG, "uploadChunkBytes failed code=${resp.code} filename=$filename chunkIndex=$chunkIndex body=$body")
                return false
            }
        }
    }

    private fun uploadPortadaFile(file: java.io.File, token: String): String? {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipart.addFormDataPart("carpeta", "")
        multipart.addFormDataPart("portada_mode", "container")
        multipart.addFormDataPart("generate_quick", "0")
        val mediaType = UriUtils.guessMimeType(file.name) ?: "application/octet-stream"
        multipart.addFormDataPart("files", file.name, file.asRequestBody(mediaType.toMediaTypeOrNull()))
        val request = Request.Builder().url("${baseApi()}/upload").addHeader("x-upload-token", token).post(multipart.build()).build()
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
}
