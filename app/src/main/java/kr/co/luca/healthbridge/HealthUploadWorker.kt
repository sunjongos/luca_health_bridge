package kr.co.luca.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.net.SocketTimeoutException

class HealthUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val token = SyncConfig.token(applicationContext)
        if (token.isBlank()) return Result.failure(workDataOf("error" to "연동코드 없음"))
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE)
            return Result.failure(workDataOf("error" to "Health Connect unavailable"))
        return runCatching {
            val json = HealthDataCollector(applicationContext).collect()
            val connection = (URL(SyncConfig.ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                applicationContext.getSharedPreferences("health_status", Context.MODE_PRIVATE).edit()
                    .putLong("last_attempt", System.currentTimeMillis()).putInt("last_http", status).apply()
                if (status in 200..299) {
                    applicationContext.getSharedPreferences("health_status", Context.MODE_PRIVATE).edit()
                        .putLong("last_success", System.currentTimeMillis()).remove("last_error").apply()
                    Result.success(workDataOf("http" to status, "uploaded" to true))
                } else if (status == 408 || status == 429 || status >= 500) Result.retry()
                else Result.failure(workDataOf("http" to status, "error" to "영구 HTTP 오류"))
            } finally { connection.disconnect() }
        }.getOrElse { error ->
            applicationContext.getSharedPreferences("health_status", Context.MODE_PRIVATE).edit()
                .putLong("last_attempt", System.currentTimeMillis()).putString("last_error", error.javaClass.simpleName).apply()
            when (error) {
                is SecurityException -> Result.failure(workDataOf("error" to "건강데이터 권한 필요"))
                is SocketTimeoutException, is IOException -> Result.retry()
                else -> Result.failure(workDataOf("error" to (error.javaClass.simpleName ?: "영구 오류")))
            }
        }
    }
}
