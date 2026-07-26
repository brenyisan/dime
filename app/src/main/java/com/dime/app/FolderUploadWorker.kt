package com.dime.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

class FolderUploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://inspection-sister-wondering-ask.trycloudflare.com/api"
    private val CHUNK_SIZE = 4 * 1024 * 1024

    override suspend fun doWork(): Result {
        val folderUriStr = inputData.getString("FOLDER_URI") ?: return Result.failure()
        val token = inputData.getString("TOKEN") ?: return Result.failure()
        val customName = inputData.getString("CUSTOM_NAME") ?: "Folder_${System.currentTimeMillis()}"
        val description = inputData.getString("DESCRIPTION") ?: ""
        val portadaUriStr = inputData.getString("PORTADA_URI") ?: ""

        val ctx = applicationContext
        val tree = Uri.parse(folderUriStr)
        val docFile = DocumentFile.fromTreeUri(ctx, tree) ?: return Result.failure()

        // Collect files recursively (only allowed types: video/img/sub)
        val files = mutableListOf<Pair<DocumentFile, String>>() // pair<doc, relPath>
        collectFilesRec(docFile, docFile.name ?: "", files, docFile)

        if (files.isEmpty()) return Result.failure()

        // Upload portada if provided
        var portadaSavedAs: String? = null
        if (portadaUriStr.isNotBlank()) {
            val tmp = UriUtils.uriToTempFile(ctx, portadaUriStr)
            if (tmp != null) {
                portadaSavedAs = uploadPortadaFile(tmp, token)
            }
        }

        // For each file in folder, chunk it and upload parts (resume via chunk/status)
        try {
            for ((doc, rel) in files) {
                val size = doc.length()
                val totalChunks = ceil(size.toDouble() / CHUNK_SIZE.toDouble()).toInt()
                val uploadId = "up-${UUID.randomUUID().toString().replace("-", "").take(28)}"

                // Query server for received chunks
                val received = queryReceivedChunks(uploadId, rel, token)

                // open input stream and send chunks
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
                        val ok = uploadChunkBytes(uploadId, token, rel, idx, totalChunks, bytes)
                        if (!ok) return Result.retry()
                        // progress (we report part progress simple)
                        setProgress(Data.Builder().putInt("part_index", idx).putInt("total_parts_for_file", totalChunks).build())
                        idx++
                    }
                }

                // register manifest entry for finalize
                // Note: we'll accumulate manifest entries and call finalize per-folder at end
            }

            // Build manifest list: all filenames relative (we collected in files list)
            val manifestArr = JSONArray()
            for ((_, rel) in files) {
                val o = JSONObject()
                o.put("filename", rel)
                manifestArr.put(o)
            }

            val assignmentsObj = JSONObject()
            val assignArr = JSONArray()
            if (!portadaSavedAs.isNullOrBlank()) {
                // assign container_portadas at folder name
                assignmentsObj.put("container_portadas", JSONObject().put(customName, portadaSavedAs))
            } else {
                assignmentsObj.put("container_portadas", JSONObject())
            }
            assignmentsObj.put("assignments", JSONArray())
            val descs = JSONObject()
            if (description.isNotBlank()) descs.put(customName, description)
            assignmentsObj.put("descriptions", descs)

            // Finalize with parts_kind raw
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
                .url("$baseUrl/upload/finalize")
                .addHeader("x-upload-token", token)
                .post(form)
                .build()

            client.newCall(req).execute().use { resp ->
                return if (resp.isSuccessful) Result.success() else Result.retry()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure()
        }
    }

    private fun collectFilesRec(root: DocumentFile, baseName: String, acc: MutableList<Pair<DocumentFile, String>>, current: DocumentFile) {
        if (current.isFile) {
            val rel = current.uri.path?.let { current.name } ?: current.name ?: return
            acc.add(Pair(current, rel))
        } else if (current.isDirectory) {
            for (c in current.listFiles()) {
                collectFilesRec(root, baseName, acc, c)
            }
        }
    }

    private fun queryReceivedChunks(uploadId: String, filename: String, token: String): Set<Int> {
        try {
            val url = HttpUrl.parse("$baseUrl/upload/chunk/status")!!.newBuilder()
                .addQueryParameter("upload_id", uploadId)
                .addQueryParameter("filename", filename)
                .addQueryParameter("token", token)
                .build()
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
            return emptySet()
        }
    }

    private fun uploadChunkBytes(uploadId: String, token: String, filename: String, chunkIndex: Int, totalChunks: Int, bytes: ByteArray): Boolean {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipart.addFormDataPart("upload_id", uploadId)
        multipart.addFormDataPart("filename", filename)
        multipart.addFormDataPart("chunk_index", chunkIndex.toString())
        multipart.addFormDataPart("total_chunks", totalChunks.toString())
        multipart.addFormDataPart("chunk", "chunk", bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))

        val req = Request.Builder()
            .url("$baseUrl/upload/chunk")
            .addHeader("x-upload-token", token)
            .post(multipart.build())
            .build()

        client.newCall(req).execute().use { resp ->
            return resp.isSuccessful
        }
    }

    private fun uploadPortadaFile(file: java.io.File, token: String): String? {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipart.addFormDataPart("carpeta", "")
        multipart.addFormDataPart("portada_mode", "container")
        multipart.addFormDataPart("generate_quick", "0")
        val mediaType = UriUtils.guessMimeType(file.name) ?: "application/octet-stream"
        multipart.addFormDataPart("files", file.name, file.asRequestBody(mediaType.toMediaTypeOrNull()))
        val request = Request.Builder().url("$baseUrl/upload").addHeader("x-upload-token", token).post(multipart.build()).build()
        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null
            try {
                val jo = JSONObject(bodyStr)
                val details = jo.optJSONArray("details")
                if (details != null && details.length() > 0) {
                    val d0 = details.getJSONObject(0)
                    return d0.optString("saved_as", null)
                }
            } catch (e: Exception) { e.printStackTrace() }
            return null
        }
    }
}
