package com.lumenkot.app

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Compatible with Lumen's optional timestamps collection; disabled by default. */
class CloudProgress(context: Context, private val settings: Settings, private val api: RezkaApi) {
    private val deviceId = settings.prefs.getString("deviceId",null) ?: System.currentTimeMillis().toString().also { settings.prefs.edit().putString("deviceId",it).apply() }
    private fun document(data: JSONObject): com.google.firebase.firestore.DocumentReference? {
        if(!settings.prefs.getBoolean("cloudProgress",false) || data.optString("film").isBlank()) return null
        val user=api.cookies.loadForRequest(settings.endpoint.toHttpUrl()).firstOrNull { it.name=="dle_user_id" }?.value ?: return null
        return FirebaseFirestore.getInstance().collection("timestamps").document("playerTime-${data.getString("film")}-$user")
    }
    private fun timestampKey(data: JSONObject) = if(data.optString("season").isBlank() || data.optString("episode").isBlank()) "0" else "${data.getString("season")}-${data.getString("episode")}"
    fun restore(data: JSONObject): Long? {
        val doc=document(data) ?: return null
        val saved=Tasks.await(doc.get(),15,TimeUnit.SECONDS).getString("savedTime") ?: return null
        val time=JSONObject(saved).optJSONObject("voices")?.optJSONObject(data.optString("voice"))?.optJSONObject("timestamps")?.optJSONObject(timestampKey(data)) ?: return null
        return if(time.optString("deviceId")!=deviceId) (time.optDouble("time",0.0)*1000).toLong() else null
    }
    fun save(data: JSONObject, position: Long, duration: Long) {
        val doc=document(data) ?: return
        val existing=Tasks.await(doc.get(),15,TimeUnit.SECONDS).getString("savedTime")
        val saved=runCatching { JSONObject(existing ?: "{}") }.getOrDefault(JSONObject())
        saved.put("filmId",data.optString("film"))
        val voices=saved.optJSONObject("voices") ?: JSONObject()
        val voice=voices.optJSONObject(data.optString("voice")) ?: JSONObject()
        val times=voice.optJSONObject("timestamps") ?: JSONObject()
        times.put(timestampKey(data),JSONObject().put("time",position/1000.0).put("progress",if(duration>0) position.toDouble()/duration*100 else 0.0).put("deviceId",deviceId))
        voice.put("timestamps",times);voices.put(data.optString("voice"),voice);saved.put("voices",voices)
        Tasks.await(doc.set(mapOf("savedTime" to saved.toString(),"updatedAt" to SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.US).format(Date()))),15,TimeUnit.SECONDS)
    }
}
