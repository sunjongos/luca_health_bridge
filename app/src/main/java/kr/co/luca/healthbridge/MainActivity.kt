package kr.co.luca.healthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import kr.co.luca.healthbridge.databinding.ActivityMainBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.round
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val samsungHealthPackage = "com.sec.android.app.shealth"
    private lateinit var binding: ActivityMainBinding
    private var healthClient: HealthConnectClient? = null
    private var latestJson: String = ""

    private val readPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
    )

    private val backgroundPermission: Set<String>
        get() = if (Build.VERSION.SDK_INT >= 34) setOf("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND") else emptySet()

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        binding.statusText.text = if (granted.containsAll(readPermissions)) {
            if (backgroundPermission.isEmpty() || granted.containsAll(backgroundPermission)) "읽기·백그라운드 권한 연결 완료"
            else "기본 읽기 완료 — 백그라운드 권한은 기기 설정에서 추가 허용해 주세요"
        } else {
            "일부 권한만 허용됨 (${granted.size}/${readPermissions.size})"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val status = HealthConnectClient.getSdkStatus(this)
        if (status == HealthConnectClient.SDK_AVAILABLE) {
            healthClient = HealthConnectClient.getOrCreate(this)
            binding.statusText.text = "Health Connect 사용 가능"
        } else {
            binding.statusText.text = "Health Connect를 설치하거나 업데이트해 주세요"
            binding.exportButton.isEnabled = false
        }

        binding.permissionButton.setOnClickListener { permissionLauncher.launch(readPermissions + backgroundPermission) }
        binding.exportButton.setOnClickListener { exportLatest() }
        binding.shareButton.setOnClickListener { shareLatest() }
        binding.autoSyncButton.setOnClickListener { enableAutoSync() }
        binding.outputText.setOnLongClickListener {
            if (latestJson.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("health.json", latestJson))
                binding.statusText.text = "JSON을 클립보드에 복사했습니다"
            }
            true
        }
    }

    private fun enableAutoSync() {
        val token = binding.tokenInput.text.toString().trim()
        if (token.isBlank()) { binding.statusText.text = "자동연동 코드를 입력해 주세요"; return }
        SyncConfig.saveToken(this, token)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<HealthUploadWorker>(6, TimeUnit.HOURS).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("luca-health-auto-sync", ExistingPeriodicWorkPolicy.UPDATE, periodic)
        val first = OneTimeWorkRequestBuilder<HealthUploadWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueue(first)
        binding.tokenInput.text?.clear()
        binding.statusText.text = "첫 자동 업로드 확인 중…"
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(first.id).observe(this) { info ->
            if (info == null || !info.state.isFinished) return@observe
            binding.statusText.text = if (info.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                "첫 업로드 성공 — 자동연동 활성화 완료"
            } else "첫 업로드 실패: ${info.outputData.getString("error") ?: "권한·코드·네트워크를 확인해 주세요"}"
        }
    }

    private fun exportLatest() = lifecycleScope.launch {
        val client = healthClient ?: return@launch
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(readPermissions)) {
            binding.statusText.text = "먼저 읽기 권한을 허용해 주세요"
            permissionLauncher.launch((readPermissions - granted) + backgroundPermission)
            return@launch
        }

        binding.statusText.text = "최근 7일 데이터를 읽는 중…"
        runCatching {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val start = now.minus(Duration.ofDays(7))
            val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val range7d = TimeRangeFilter.between(start, now)
            val rangeToday = TimeRangeFilter.between(todayStart, now)

            val steps = async { client.readRecords(ReadRecordsRequest(StepsRecord::class, rangeToday)).records }
            val exercise = async { client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range7d)).records }
            val heartRate = async { client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range7d)).records }
            val sleep = async { client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range7d)).records }
            val oxygen = async { client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range7d)).records }
            val weight = async { client.readRecords(ReadRecordsRequest(WeightRecord::class, range7d)).records }
            val bodyFat = async { client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range7d)).records }
            val vo2 = async { client.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, range7d)).records }

            val allStepRecords = steps.await()
            val allExercises = exercise.await()
            val allHeartRecords = heartRate.await()
            val allSleepRecords = sleep.await()
            val allOxygenRecords = oxygen.await()
            val allWeightRecords = weight.await()
            val allBodyFatRecords = bodyFat.await()
            val allVo2Records = vo2.await()

            // Samsung Health is the primary wearable source. Google Fitness and other
            // Health Connect writers are retained only as metric-level fallbacks so a
            // synced copy of the same activity is not counted twice.
            val stepRecords = preferSamsung(allStepRecords) { it.metadata.dataOrigin.packageName }
            val exercises = preferSamsung(allExercises) { it.metadata.dataOrigin.packageName }
            val heartRecords = preferSamsung(allHeartRecords) { it.metadata.dataOrigin.packageName }
            val sleepRecords = preferSamsung(allSleepRecords) { it.metadata.dataOrigin.packageName }
            val oxygenRecords = preferSamsung(allOxygenRecords) { it.metadata.dataOrigin.packageName }
            val weightRecords = preferSamsung(allWeightRecords) { it.metadata.dataOrigin.packageName }
            val bodyFatRecords = preferSamsung(allBodyFatRecords) { it.metadata.dataOrigin.packageName }
            val vo2Records = preferSamsung(allVo2Records) { it.metadata.dataOrigin.packageName }

            val heartSamples = heartRecords.flatMap { it.samples }
            val oxygenValues = oxygenRecords.map { it.percentage.value }
            val latestWeight = weightRecords.maxByOrNull { it.time }
            val latestBodyFat = bodyFatRecords.maxByOrNull { it.time }
            val latestVo2 = vo2Records.maxByOrNull { it.time }
            val latestSleep = sleepRecords.maxByOrNull { it.endTime }

            JSONObject().apply {
                put("schema", "luca.health-connect.v3")
                put("generated_at", DateTimeFormatter.ISO_INSTANT.format(now))
                put("timezone", zone.id)
                put("window_days", 7)
                put("today_steps", stepRecords.sumOf { it.count })
                put("exercise_minutes_7d", mergedDurationMinutes(exercises.map { it.startTime to it.endTime }))
                put("exercise_session_count_7d", exercises.size)
                put("heart_rate_exported_samples_bpm", JSONObject().apply {
                    put("stats", stats(heartSamples.map { it.beatsPerMinute.toDouble() }))
                    put("interpretation", "all_exported_samples_not_resting_heart_rate")
                })
                put("oxygen_saturation_percent", stats(oxygenValues))
                put("latest_sleep", latestSleep?.let {
                    JSONObject().apply {
                        put("start", it.startTime.toString())
                        put("end", it.endTime.toString())
                        put("minutes", Duration.between(it.startTime, it.endTime).toMinutes())
                        put("source", origins(it.metadata.dataOrigin.packageName))
                    }
                } ?: JSONObject.NULL)
                put("latest_weight_kg", latestWeight?.weight?.inKilograms ?: JSONObject.NULL)
                put("latest_body_fat_percent", latestBodyFat?.percentage?.value ?: JSONObject.NULL)
                put("latest_vo2max_ml_kg_min", latestVo2?.vo2MillilitersPerMinuteKilogram ?: JSONObject.NULL)
                put("source_policy", "prefer_samsung_health_then_metric_fallback")
                put("selected_origins", JSONArray((stepRecords.map { it.metadata.dataOrigin.packageName } +
                    exercises.map { it.metadata.dataOrigin.packageName } +
                    heartRecords.map { it.metadata.dataOrigin.packageName } +
                    sleepRecords.map { it.metadata.dataOrigin.packageName } +
                    oxygenRecords.map { it.metadata.dataOrigin.packageName } +
                    weightRecords.map { it.metadata.dataOrigin.packageName } +
                    bodyFatRecords.map { it.metadata.dataOrigin.packageName } +
                    vo2Records.map { it.metadata.dataOrigin.packageName }).distinct()))
                put("available_origins", JSONArray((allStepRecords.map { it.metadata.dataOrigin.packageName } +
                    allExercises.map { it.metadata.dataOrigin.packageName } +
                    allHeartRecords.map { it.metadata.dataOrigin.packageName } +
                    allSleepRecords.map { it.metadata.dataOrigin.packageName } +
                    allOxygenRecords.map { it.metadata.dataOrigin.packageName } +
                    allWeightRecords.map { it.metadata.dataOrigin.packageName } +
                    allBodyFatRecords.map { it.metadata.dataOrigin.packageName } +
                    allVo2Records.map { it.metadata.dataOrigin.packageName }).distinct()))
                put("medical_use", "trend_screening_only_not_diagnosis")
            }.toString(2)
        }.onSuccess { json ->
            latestJson = json
            getSharedPreferences("health", MODE_PRIVATE).edit().putString("latest_json", json).apply()
            binding.outputText.text = json
            binding.statusText.text = "읽기 완료 — 화면 길게 누르면 복사됩니다"
        }.onFailure { error ->
            binding.statusText.text = "읽기 실패: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun shareLatest() {
        if (latestJson.isBlank()) {
            latestJson = getSharedPreferences("health", MODE_PRIVATE).getString("latest_json", "") ?: ""
        }
        if (latestJson.isBlank()) {
            binding.statusText.text = "먼저 최신 건강 JSON을 만들어 주세요"
            return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Luca Health Connect export")
            putExtra(Intent.EXTRA_TEXT, latestJson)
        }, "건강 JSON 공유"))
    }

    private fun stats(values: List<Double>): Any {
        if (values.isEmpty()) return JSONObject.NULL
        return JSONObject().apply {
            put("count", values.size)
            put("min", round2(values.min()))
            put("max", round2(values.max()))
            put("avg", round2(values.average()))
        }
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
    private fun origins(packageName: String): String = packageName.ifBlank { "unknown" }

    private fun <T> preferSamsung(records: List<T>, origin: (T) -> String): List<T> {
        val samsungRecords = records.filter { origin(it) == samsungHealthPackage }
        return samsungRecords.ifEmpty { records }
    }

    private fun mergedDurationMinutes(intervals: List<Pair<Instant, Instant>>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals.sortedBy { it.first }
        var currentStart = sorted.first().first
        var currentEnd = sorted.first().second
        var total = 0L

        for ((start, end) in sorted.drop(1)) {
            if (!start.isAfter(currentEnd)) {
                if (end.isAfter(currentEnd)) currentEnd = end
            } else {
                total += Duration.between(currentStart, currentEnd).toMinutes()
                currentStart = start
                currentEnd = end
            }
        }
        return total + Duration.between(currentStart, currentEnd).toMinutes()
    }
}
