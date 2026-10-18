package kr.co.luca.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import kotlin.math.round
import kotlin.reflect.KClass

class HealthDataCollector(context: Context) {
    private val client = HealthConnectClient.getOrCreate(context)
    private val samsung = "com.sec.android.app.shealth"

    suspend fun collect(): String = coroutineScope {
        val zone = ZoneId.systemDefault(); val now = Instant.now(); val start = now.minus(Duration.ofDays(7))
        val range7d = TimeRangeFilter.between(start, now)
        val rangeToday = TimeRangeFilter.between(LocalDate.now(zone).atStartOfDay(zone).toInstant(), now)
        val s = async { readAll(StepsRecord::class, rangeToday) }
        val e = async { readAll(ExerciseSessionRecord::class, range7d) }
        val h = async { readAll(HeartRateRecord::class, range7d) }
        val sl = async { readAll(SleepSessionRecord::class, range7d) }
        val o = async { readAll(OxygenSaturationRecord::class, range7d) }
        val w = async { readAll(WeightRecord::class, range7d) }
        val b = async { readAll(BodyFatRecord::class, range7d) }
        val v = async { readAll(Vo2MaxRecord::class, range7d) }
        val allS=s.await(); val allE=e.await(); val allH=h.await(); val allSl=sl.await(); val allO=o.await(); val allW=w.await(); val allB=b.await(); val allV=v.await()
        fun <T> preferred(records: List<T>, origin: (T)->String): List<T> = records.filter { origin(it)==samsung }.ifEmpty { records }
        val steps=preferred(allS){it.metadata.dataOrigin.packageName}; val exercises=preferred(allE){it.metadata.dataOrigin.packageName}
        val hearts=preferred(allH){it.metadata.dataOrigin.packageName}; val sleeps=preferred(allSl){it.metadata.dataOrigin.packageName}
        val oxy=preferred(allO){it.metadata.dataOrigin.packageName}; val weights=preferred(allW){it.metadata.dataOrigin.packageName}
        val fats=preferred(allB){it.metadata.dataOrigin.packageName}; val vo2=preferred(allV){it.metadata.dataOrigin.packageName}
        val selected=(steps.map{it.metadata.dataOrigin.packageName}+exercises.map{it.metadata.dataOrigin.packageName}+hearts.map{it.metadata.dataOrigin.packageName}+sleeps.map{it.metadata.dataOrigin.packageName}+oxy.map{it.metadata.dataOrigin.packageName}+weights.map{it.metadata.dataOrigin.packageName}+fats.map{it.metadata.dataOrigin.packageName}+vo2.map{it.metadata.dataOrigin.packageName}).distinct()
        val available=(allS.map{it.metadata.dataOrigin.packageName}+allE.map{it.metadata.dataOrigin.packageName}+allH.map{it.metadata.dataOrigin.packageName}+allSl.map{it.metadata.dataOrigin.packageName}+allO.map{it.metadata.dataOrigin.packageName}+allW.map{it.metadata.dataOrigin.packageName}+allB.map{it.metadata.dataOrigin.packageName}+allV.map{it.metadata.dataOrigin.packageName}).distinct()
        val latestSleep=sleeps.maxByOrNull{it.endTime}
        JSONObject().apply {
            put("schema","luca.health-connect.v5"); put("generated_at",now.toString()); put("timezone",zone.id); put("window_days",7)
            put("today_steps",steps.sumOf{it.count}); put("exercise_minutes_7d",merged(exercises.map{it.startTime to it.endTime})); put("exercise_session_count_7d",exercises.size)
            put("heart_rate_exported_samples_bpm",JSONObject().put("stats",stats(hearts.flatMap{it.samples}.map{it.beatsPerMinute.toDouble()})).put("interpretation","all_exported_samples_not_resting_heart_rate"))
            put("oxygen_saturation_percent",stats(oxy.map{it.percentage.value}))
            put("latest_sleep",latestSleep?.let{JSONObject().put("start",it.startTime.toString()).put("end",it.endTime.toString()).put("minutes",Duration.between(it.startTime,it.endTime).toMinutes()).put("source",it.metadata.dataOrigin.packageName)}?:JSONObject.NULL)
            put("latest_weight_kg",weights.maxByOrNull{it.time}?.weight?.inKilograms?:JSONObject.NULL)
            put("latest_body_fat_percent",fats.maxByOrNull{it.time}?.percentage?.value?:JSONObject.NULL)
            put("latest_vo2max_ml_kg_min",vo2.maxByOrNull{it.time}?.vo2MillilitersPerMinuteKilogram?:JSONObject.NULL)
            put("source_policy","prefer_samsung_health_then_metric_fallback"); put("selected_origins",JSONArray(selected)); put("available_origins",JSONArray(available)); put("medical_use","trend_screening_only_not_diagnosis")
        }.toString(2)
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageSize = 1000, pageToken = token))
            records.addAll(response.records)
            token = response.pageToken
        } while (token != null)
        return records
    }

    private fun stats(values: List<Double>): Any = if(values.isEmpty()) JSONObject.NULL else JSONObject().put("count",values.size).put("min",r(values.min())).put("max",r(values.max())).put("avg",r(values.average()))
    private fun r(v:Double)=round(v*100)/100
    private fun merged(xs:List<Pair<Instant,Instant>>):Long { if(xs.isEmpty()) return 0; val a=xs.sortedBy{it.first}; var s=a[0].first; var e=a[0].second; var total=0L; for((ns,ne) in a.drop(1)){if(!ns.isAfter(e)){if(ne.isAfter(e))e=ne}else{total+=Duration.between(s,e).toMinutes();s=ns;e=ne}}; return total+Duration.between(s,e).toMinutes() }
}
