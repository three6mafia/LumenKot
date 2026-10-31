package com.lumenkot.app

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity: ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var data: JSONObject
    private lateinit var settings: Settings
    private lateinit var api: RezkaApi
    private var position=0L
    private var playing=true
    private var released=false
    private var progressJob: Job? = null
    private lateinit var cloud: CloudProgress
    private lateinit var surface: PlayerView
    private lateinit var toolbar: LinearLayout
    private val key get() = "position:${data.optString("film",data.optString("url"))}:${data.optString("voice")}:${data.optString("season")}:${data.optString("episode")}"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data=runCatching { JSONObject(intent.getStringExtra("media") ?: "{}") }.getOrElse { finish();return }
        if(data.optString("url").isBlank()) { finish();return }
        settings=Settings(this);api=RezkaApi(settings);cloud=CloudProgress(this,settings,api)
        position=savedInstanceState?.getLong("position") ?: settings.prefs.getLong(key,0)
        playing=savedInstanceState?.getBoolean("playing") ?: true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK)!=android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val root=FrameLayout(this)
        surface=PlayerView(this).apply { id=R.id.player_surface; setShowSubtitleButton(true);setShowNextButton(false);setShowPreviousButton(false);controllerShowTimeoutMs=5000;keepScreenOn=true }
        root.addView(surface,FrameLayout.LayoutParams(-1,-1))
        toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;setBackgroundColor(0x99000000.toInt());gravity=Gravity.END }
        fun control(label: String, action: () -> Unit) { toolbar.addView(Button(this).apply { text=label;isAllCaps=false;setOnClickListener { action() } }) }
        control("‹") { finish() }
        control("Формат / качество") {
            val streams=data.optJSONArray("streams") ?: JSONArray()
            val labels=(0 until streams.length()).map { streams.getJSONObject(it).let { Stream(it.optString("quality"),it.optString("url")).label } }
            AlertDialog.Builder(this).setTitle("Формат и качество").setItems(labels.toTypedArray()) { _,i ->
                val at=player.currentPosition; val play=player.playWhenReady
                data.put("url",streams.getJSONObject(i).getString("url")).put("quality",streams.getJSONObject(i).optString("quality"));if(settings.prefs.getBoolean("saveQuality",true)) settings.prefs.edit().putString("quality",data.optString("quality")).apply();player.setMediaItem(media(),at);player.prepare();player.playWhenReady=play
            }.show()
        }
        control("Серия") { chooseEpisode() }
        control("Следующая") { nextEpisode() }
        control("Скорость") {
            val speeds=listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f)
            AlertDialog.Builder(this).setTitle("Скорость").setItems(speeds.map { "${it}×" }.toTypedArray()) { _,i -> player.setPlaybackSpeed(speeds[i]) }.show()
        }
        control("Экран") { surface.resizeMode=if(surface.resizeMode==androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
        control("PiP") { if(Build.VERSION.SDK_INT>=26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
        val toolbarScroll=android.widget.HorizontalScrollView(this).apply { addView(toolbar);isFillViewport=true }
        root.addView(toolbarScroll,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        surface.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { toolbarScroll.visibility=it })
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply { hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
    }
    private fun media(): MediaItem {
        val b=MediaItem.Builder().setUri(data.getString("url")).setMediaId(key)
        val subtitles=data.optJSONArray("subtitles") ?: JSONArray()
        b.setSubtitleConfigurations((0 until subtitles.length()).map { i ->
            val s=subtitles.getJSONObject(i); val url=s.getString("url")
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setLabel(s.optString("title")).setLanguage(s.optString("language")).setMimeType(s.optString("mime").ifBlank { if(url.substringBefore('?').endsWith(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT }).setSelectionFlags(if(s.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0).build()
        })
        return b.build()
    }
    override fun onStart() {
        super.onStart()
        if(::data.isInitialized && ::surface.isInitialized) {
            val http=OkHttpDataSource.Factory(api.client).setDefaultRequestProperties(api.headers())
            val sources=DefaultDataSource.Factory(this,http)
            player=ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).setSeekBackIncrementMs(settings.prefs.getInt("seekSeconds",10)*1000L).setSeekForwardIncrementMs(settings.prefs.getInt("seekSeconds",10)*1000L).build()
            released=false;surface.player=player
            player.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if(state==Player.STATE_ENDED && settings.prefs.getBoolean("autoNext",true)) nextEpisode()
                }
                override fun onPlayerError(error: PlaybackException) {
                    AlertDialog.Builder(this@PlayerActivity).setTitle("Ошибка воспроизведения").setMessage(error.localizedMessage).setPositiveButton("Повторить") { _,_ -> player.prepare();player.play() }.setNegativeButton("Закрыть") { _,_ -> finish() }.show()
                }
            })
            player.setMediaItem(media(),position);player.setPlaybackSpeed(settings.prefs.getFloat("speed",1f));player.prepare();player.playWhenReady=playing
            progressJob=lifecycleScope.launch {
                if(settings.prefs.getBoolean("cloudProgress",false)) {
                    val remote=withContext(Dispatchers.IO) { runCatching { cloud.restore(data) }.getOrNull() }
                    if(remote!=null && !released) player.seekTo(remote)
                }
                while(isActive) {
                    delay(15000)
                    if(!released) {
                        val at=player.currentPosition;val duration=player.duration
                        settings.prefs.edit().putLong(key,at).apply()
                        val snapshot=JSONObject(data.toString())
                        withContext(Dispatchers.IO) { runCatching { cloud.save(snapshot,at,duration) } }
                    }
                }
            }
        }
    }
    private fun chooseEpisode() {
        val seasons=data.optJSONArray("seasons") ?: JSONArray()
        if(seasons.length()==0) return
        AlertDialog.Builder(this).setTitle("Сезон").setItems((0 until seasons.length()).map { seasons.getJSONObject(it).optString("title") }.toTypedArray()) { _,i ->
            val season=seasons.getJSONObject(i);val episodes=season.getJSONArray("episodes")
            AlertDialog.Builder(this).setTitle("Серия").setItems((0 until episodes.length()).map { episodes.getJSONObject(it).optString("title") }.toTypedArray()) { _,e -> changeEpisode(season.getString("id"),episodes.getJSONObject(e).getString("id")) }.show()
        }.show()
    }
    private fun nextEpisode() {
        val seasons=data.optJSONArray("seasons") ?: return
        val all=mutableListOf<Pair<String,String>>()
        for(s in 0 until seasons.length()) { val season=seasons.getJSONObject(s);val eps=season.getJSONArray("episodes");for(e in 0 until eps.length()) all.add(season.getString("id") to eps.getJSONObject(e).getString("id")) }
        val index=all.indexOf(data.optString("season") to data.optString("episode"))
        if(index>=0 && index+1<all.size) { val next=all[index+1];changeEpisode(next.first,next.second) }
    }
    private fun changeEpisode(season: String,episode: String) {
        if(data.optString("filmUrl").isBlank()) return
        val wasPlaying=player.playWhenReady;player.pause()
        lifecycleScope.launch {
            try {
                val video=withContext(Dispatchers.IO) { val f=api.film(data.getString("filmUrl"));val voice=f.voices.firstOrNull { it.id==data.optString("voice") } ?: f.voices.first();api.video(f,voice,season,episode) }
                require(video.streams.isNotEmpty()) { "Видео недоступно" }
                settings.prefs.edit().putLong(key,player.currentPosition).apply()
                val format=Stream(data.optString("quality"),data.optString("url")).format
                val candidates=video.streams.filter { it.format==format }.ifEmpty { video.streams }
                val stream=candidates.firstOrNull { it.quality==data.optString("quality") } ?: candidates.last()
                data.put("season",season).put("episode",episode).put("url",stream.url).put("quality",stream.quality)
                data.put("streams",JSONArray(video.streams.map { JSONObject().put("quality",it.quality).put("url",it.url) }))
                data.put("subtitles",JSONArray(video.subtitles.map { JSONObject().put("title",it.title).put("url",it.url).put("language",it.language).put("default",it.default) }))
                player.setMediaItem(media());player.prepare();player.play()
            } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;Toast.makeText(this@PlayerActivity,e.message ?: "Не удалось открыть серию",Toast.LENGTH_LONG).show();player.playWhenReady=wasPlaying }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if(::player.isInitialized && !released) { outState.putLong("position",player.currentPosition);outState.putBoolean("playing",player.playWhenReady) }
        super.onSaveInstanceState(outState)
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean,newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig)
        if(::surface.isInitialized) { surface.useController=!isInPictureInPictureMode;toolbar.visibility=if(isInPictureInPictureMode) View.GONE else View.VISIBLE }
    }
    override fun onStop() {
        progressJob?.cancel()
        if(::player.isInitialized && !released) {
            position=player.currentPosition;playing=player.playWhenReady;settings.prefs.edit().putLong(key,position).apply()
            if(data.optString("film").isNotBlank() && api.cookies.signedIn()) {
                val fields=mapOf("post_id" to data.optString("film"),"translator_id" to data.optString("voice"),"season" to data.optString("season").ifBlank { "0" },"episode" to data.optString("episode").ifBlank { "0" },"current_time" to "${(position/1000).coerceAtLeast(1)}")
                lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { api.json("/ajax/send_save",fields) } } }
            }
            surface.player=null;player.release();released=true
        }
        super.onStop()
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(::player.isInitialized && !released && event.action==KeyEvent.ACTION_DOWN) {
            when(event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if(player.isPlaying) player.pause() else player.play();return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward();return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack();return true }
            }
        }
        return super.onKeyDown(keyCode,event)
    }
}
