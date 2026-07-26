package com.dime.app

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File

class FFmpegWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val fileUriString = inputData.getString("FILE_URI") ?: return Result.failure()
        val token = inputData.getString("TOKEN") ?: ""
        val segmentSeconds = inputData.getInt("SEG_SECONDS", 120)

        val uri = Uri.parse(fileUriString)
        val cacheDir = applicationContext.cacheDir
        val outputDir = File(cacheDir, "dime_segments_${System.currentTimeMillis()}")
        if (!outputDir.exists()) outputDir.mkdirs()

        val inputPath = try {
            FFmpegKitConfig.getSafParameterForRead(applicationContext, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure()
        }

        val outputPattern = File(outputDir, "part_%05d.ts").absolutePath

        val command = "-y -i $inputPath -c copy -f segment -segment_time $segmentSeconds -reset_timestamps 1 $outputPattern"

        return try {
            val session = FFmpegKit.execute(command)
            if (ReturnCode.isSuccess(session.returnCode)) {
                val outputData = Data.Builder()
                    .putString("OUTPUT_DIR", outputDir.absolutePath)
                    .putString("TOKEN", token)
                    .build()
                Result.success(outputData)
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
