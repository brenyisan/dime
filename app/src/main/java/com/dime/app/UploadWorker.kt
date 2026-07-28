package com.dime.app

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
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

    private fun baseApi(): String {
        val server = SessionManager.getServerUrl(applicationContext).trimEnd('/')
        return "$server/api"
    }

    // Counting wrapper for RequestBody that reports progress via callback
    class CountingRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (bytesWritten: Long, contentLength: Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        @Throws(IOException::class)
        override fun contentLength(): Long = try {
            delegate.contentLength()
        } catch (e: Exception) {
            -1L
        }

        @Throws(IOException::class)
        override fun writeTo(sink: okio.BufferedSink) {
            val countingSink = object : ForwardingSink(sink) {
                var bytesWritten = 0L
                var lastReportedPct = -1
                val total = contentLength()
                @Throws(IOException::class)
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    bytesWritten += byteCount
                    // CORRECCIÓN: Okio llama a write() muchas veces por archivo (no una
                    // vez por chunk completo). Antes, cada una de esas llamadas disparaba
                    // onProgress() -> runBlocking { setProgress(...) }, y setProgress()
                    // persiste el progreso en la base de datos interna de WorkManager, lo
                    // que implica I/O de disco en cada llamada. Para un .ts de varios MB
                    // eso significaba cientos/miles de escrituras a disco por chunk,
                    // serializadas con la propia subida, haciendo todo mucho más lento.
                    // Ahora solo se reporta cuando el porcentaje entero cambia (~100
                    // reportes por chunk como máximo, en vez de miles).
                    val t = if (total >= 0) total else -1L
                    val pct = if (t > 0) ((bytesWritten * 100.0) / t).toInt() else -1
                    if (pct != lastReportedPct) {
                        lastReportedPct = pct
                        onProgress(bytesWritten, t)
                    }
                }
            }
            val buffered = countingSink.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
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

        // 1) upload portada (if any) — igual que el cliente Python: se sube como
        //    un único "chunk" (1 de 1) y luego se finaliza con parts_kind="image".
        //    El nombre se fuerza al nombre del video + ".jpg", sin excepción.
        //    (SIN CAMBIOS respecto al código original)
        var portadaSavedAs: String? = null
        if (portadaUriStr.isNotBlank()) {
            val portadaFile = UriUtils.uriToTempFile(applicationContext, portadaUriStr)
            if (portadaFile != null && portadaFile.exists()) {
                portadaSavedAs = uploadPortadaFile(portadaFile, token, customName)
                Log.i(TAG, "Portada uploaded saved_as=$portadaSavedAs")
            } else {
                Log.w(TAG, "Portada temp file missing or unreadable")
            }
        }

        // 2) upload parts (single upload_id for this video)
        // CORRECCIÓN: se quitó el chequeo previo contra queryReceivedChunks(), que usaba
        // un upload_id recién generado (UUID.randomUUID()) y por lo tanto SIEMPRE debía
        // devolver un set vacío del servidor. Ese chequeo estaba provocando que partes
        // válidas cayeran en la rama "already.contains(index)" y se saltaran con
        // `continue` sin llegar nunca a uploadTsChunkWithProgress(), es decir, sin
        // subirse realmente al servidor. Python nunca hace este chequeo: sencillamente
        // sube cada parte generada por ffmpeg en orden. Ahora el loop hace lo mismo.
        val uploadId = "up-${UUID.randomUUID().toString().replace("-", "").take(28)}"
        val totalChunks = tsFiles.size
        var bytesUploaded = 0L
        val totalBytes = tsFiles.sumOf { it.length() }

        try {
            for ((index, file) in tsFiles.withIndex()) {
                Log.i(TAG, "Uploading chunk -> upload_id=$uploadId filename=$customName chunkIndex=$index partFile=${file.name}")

                // notify start
                runBlocking { setProgress(workDataOf("part_index" to index, "part_progress" to 0, "total_parts" to totalChunks)) }

                val success = uploadTsChunkWithProgress(uploadId, token, index, totalChunks, file, customName)
                if (!success) {
                    // retry with .mp4 appended if missing extension (tolerance)
                    if (!customName.contains('.')) {
                        val alt = "$customName.mp4"
                        Log.i(TAG, "Retry upload with alt filename: $alt")
                        val ok2 = uploadTsChunkWithProgress(uploadId, token, index, totalChunks, file, alt)
                        if (!ok2) {
                            outputDir.deleteRecursively()
                            return Result.retry()
                        } else {
                            customName = alt
                        }
                    } else {
                        outputDir.deleteRecursively()
                        return Result.retry()
                    }
                }

                bytesUploaded += file.length()
                val percent = if (totalBytes > 0) ((bytesUploaded.toDouble() / totalBytes) * 100).roundToInt() else 0
                runBlocking { setProgress(workDataOf(
                    "uploaded_bytes" to bytesUploaded,
                    "total_bytes" to totalBytes,
                    "part_index" to index,
                    "part_progress" to 100,
                    "total_parts" to totalChunks,
                    "overall_percent" to percent
                )) }
            }

            // 3) finalize: include assignments like Python client expects
            val assignmentsObj = JSONObject()
            val assignArr = JSONArray()

            if (!portadaSavedAs.isNullOrBlank()) {
                val a = JSONObject()
                a.put("video", customName)
                // IMPORTANT: pass saved filename exactly as returned by /api/upload/finalize (no "existing:" prefix)
                a.put("image", portadaSavedAs)
                a.put("descripcion", description)
                assignArr.put(a)
            }

            assignmentsObj.put("assignments", assignArr)
            assignmentsObj.put("container_portadas", JSONObject()) // keep empty (video mode uses assignments array)
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

    /**
     * Sube la portada EXACTAMENTE igual que el cliente Python (uploader_ffmpeg_gui.py):
     *  1) Fuerza el nombre a "<stemDelVideo>.jpg" (extensión .jpg sin excepción, sea cual
     *     sea el formato/extensión original de la imagen elegida).
     *     Se usa el "stem" de customName (sin su extensión, si la tuviera) para que el
     *     nombre de la portada coincida EXACTAMENTE con el nombre que el servidor va a
     *     usar para el video final ("<stem>.mp4" en /api/upload/finalize con
     *     parts_kind="ts"). Si customName ya trae extensión (ej: "Mi_Video.mp4"), la
     *     portada se guarda como "Mi_Video.jpg", nunca "Mi_Video.mp4.jpg".
     *  2) La envía como un único chunk (chunk_index=0, total_chunks=1) a /api/upload/chunk.
     *  3) Llama a /api/upload/finalize con parts_kind="image" para que el servidor
     *     la reconstruya/guarde como imagen final.
     *
     * (SIN CAMBIOS respecto al código original)
     *
     * Devuelve el nombre guardado (el mismo "<stemDelVideo>.jpg" que se envió).
     */
    private fun uploadPortadaFile(file: File, token: String, customName: String): String? {
        val baseName = customName.substringBeforeLast(".", customName)
        val nuevoNombre = "$baseName.jpg"
        val coverUploadId = "up-${UUID.randomUUID().toString().replace("-", "").take(28)}"

        // --- Paso 1: subir la imagen completa como chunk único (0 de 1) ---
        val chunkBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val chunkMultipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("upload_id", coverUploadId)
            .addFormDataPart("filename", nuevoNombre)
            .addFormDataPart("chunk_index", "0")
            .addFormDataPart("total_chunks", "1")
            .addFormDataPart("chunk", nuevoNombre, chunkBody)
            .build()

        val chunkRequest = Request.Builder()
            .url("${baseApi()}/upload/chunk")
            .addHeader("x-upload-token", token)
            .post(chunkMultipart)
            .build()

        try {
            client.newCall(chunkRequest).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "uploadPortadaFile chunk failed: ${resp.code} - $bodyStr")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadPortadaFile chunk error", e)
            return null
        }

        // --- Paso 2: finalizar como imagen ---
        val finalizeForm = FormBody.Builder()
            .add("upload_id", coverUploadId)
            .add("carpeta", "")
            .add("manifest", "[{\"filename\":\"$nuevoNombre\"}]")
            .add("parts_kind", "image")
            .build()

        val finalizeRequest = Request.Builder()
            .url("${baseApi()}/upload/finalize")
            .addHeader("x-upload-token", token)
            .post(finalizeForm)
            .build()

        try {
            client.newCall(finalizeRequest).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "uploadPortadaFile finalize failed: ${resp.code} - $bodyStr")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadPortadaFile finalize error", e)
            return null
        }

        return nuevoNombre
    }

    private fun uploadTsChunkWithProgress(uploadId: String, token: String, chunkIndex: Int, totalChunks: Int, file: File, filenameForServer: String): Boolean {
        val fileReq = file.asRequestBody("video/mp2t".toMediaTypeOrNull())
        val counting = CountingRequestBody(fileReq) { written, total ->
            val pct = if (total > 0) ((written * 100.0) / total).toInt() else 0
            runBlocking { setProgress(workDataOf("part_index" to chunkIndex, "part_progress" to pct, "total_parts" to totalChunks)) }
        }

        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipartBuilder.addFormDataPart("upload_id", uploadId)
        multipartBuilder.addFormDataPart("filename", filenameForServer)
        multipartBuilder.addFormDataPart("chunk_index", chunkIndex.toString())
        multipartBuilder.addFormDataPart("total_chunks", totalChunks.toString())
        multipartBuilder.addFormDataPart("chunk", file.name, counting)

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
