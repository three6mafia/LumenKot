package com.lumenkot.app

import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** Website stores the last episode; exact timestamps use the same per-film/voice key as before. */
data class WatchSelection(val voice: String, val season: String, val episode: String)
class WatchProgress(private val prefs: SharedPreferences) {
    fun last(film: String): WatchSelection? = runCatching {
        val data=JSONObject(prefs.getString("watchLast:$film",null) ?: return@runCatching null)
        WatchSelection(data.getString("voice"),data.optString("season"),data.optString("episode"))
    }.getOrNull()
    fun position(film: String, voice: String, season: String, episode: String) = prefs.getLong(key(film,voice,season,episode),0)
    fun hasProgress(film: String) = prefs.all.any {(key,value)->key.startsWith("position:$film:") && value is Long && value>5000}
    fun record(film: String, voice: String, season: String, episode: String, position: Long) {
        if(film.isBlank()) return
        prefs.edit().putLong(key(film,voice,season,episode),position.coerceAtLeast(0))
            .putString("watchLast:$film",JSONObject().put("voice",voice).put("season",season).put("episode",episode).toString()).apply()
    }
    companion object {
        fun key(film: String, voice: String, season: String, episode: String)="position:$film:$voice:$season:$episode"
        private data class Save(val api: RezkaApi,val film: String,val voice: String,val season: String,val episode: String,val position: Long)
        // Activity destruction must not cancel the last website save. Collapse queued updates per film.
        private val queue=Channel<Save>(Channel.UNLIMITED)
        private val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            for(first in queue) {
                val latest=linkedMapOf(first.film to first)
                while(true) {val next=queue.tryReceive().getOrNull() ?: break;latest[next.film]=next}
                for(save in latest.values) runCatching {
                    save.api.json("/ajax/send_save",mapOf("post_id" to save.film,"translator_id" to save.voice,
                        "season" to save.season.ifBlank {"0"},"episode" to save.episode.ifBlank {"0"},
                        "current_time" to (save.position/1000).coerceAtLeast(1).toString()))
                }
            }
        }
        fun saveToWebsite(api: RezkaApi, film: String, voice: String, season: String, episode: String, position: Long) {
            if(film.isNotBlank() && api.cookies.signedIn()) queue.trySend(Save(api,film,voice,season,episode,position))
        }
    }
}
