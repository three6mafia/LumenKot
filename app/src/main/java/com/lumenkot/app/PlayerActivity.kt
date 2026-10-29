package com.lumenkot.app

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.graphics.Color
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
    private var controlsLocked=false
    private lateinit var titleView: TextView
    private var progressJob: Job? = null
    private var infoJob: Job? = null
    private lateinit var cloud: CloudProgress
    private lateinit var surface: PlayerView
    private lateinit var toolbar: LinearLayout
    private lateinit var errorView: TextView
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
        val attributes=resources.getLayout(R.layout.lumen_player_surface)
        while(attributes.next()!=org.xmlpull.v1.XmlPullParser.START_TAG) { }
        surface=PlayerView(this,android.util.Xml.asAttributeSet(attributes)).apply { id=R.id.player_surface; setShowSubtitleButton(true);setShowNextButton(false);setShowPreviousButton(false);controllerShowTimeoutMs=5000;keepScreenOn=true;setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);setControllerAutoShow(true) }
        attributes.close()
        root.addView(surface,FrameLayout.LayoutParams(-1,-1))
        var heldSpeed: Float?=null
        val gestures=GestureDetector(this,object: GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if(::player.isInitialized && !released && !controlsLocked) {if(e.x<surface.width/2) player.seekBack() else player.seekForward()}
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if(::player.isInitialized && !released && !controlsLocked && player.isPlaying) {heldSpeed=player.playbackParameters.speed;player.setPlaybackSpeed(1.5f)}
            }
        })
        surface.setOnTouchListener { _,event ->
            val handled=gestures.onTouchEvent(event)
            if(event.action==MotionEvent.ACTION_UP || event.action==MotionEvent.ACTION_CANCEL) heldSpeed?.let {if(::player.isInitialized && !released) player.setPlaybackSpeed(it);heldSpeed=null}
            handled
        }
        toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;setBackgroundColor(0x99000000.toInt());gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(16),dp(16),0) }
        fun control(label: String, container: LinearLayout=toolbar, action: () -> Unit): ImageButton {
            return ImageButton(this).apply {
                contentDescription=label;setImageDrawable(LumenIcon(label,Color.WHITE));setBackgroundColor(Color.TRANSPARENT);setPadding(dp(9),dp(9),dp(9),dp(9));setOnClickListener {if(!released) action()}
                container.addView(this,LinearLayout.LayoutParams(dp(42),dp(42)).apply {leftMargin=dp(12)})
            }
        }
        control("‹") { finish() }
        val titleColumn=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        titleView=TextView(this).apply {setTextColor(Color.WHITE);textSize=18f;setTypeface(null,android.graphics.Typeface.BOLD);maxLines=2}
        titleColumn.addView(titleView)
        titleColumn.addView(TextView(this).apply {text=data.optString("subtitle");setTextColor(Color.WHITE);textSize=14f})
        toolbar.addView(titleColumn,LinearLayout.LayoutParams(0,-2,1f))
        updateTitle()
        control("PiP") { if(Build.VERSION.SDK_INT>=26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
        control("Скорость") {
            val speeds=listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f)
            AlertDialog.Builder(this).setTitle("Скорость").setItems(speeds.map { "${it}×" }.toTypedArray()) { _,i -> player.setPlaybackSpeed(speeds[i]) }.show()
        }

        control("Качество") {
            val streams=data.optJSONArray("streams") ?: JSONArray()
            val labels=(0 until streams.length()).map { streams.getJSONObject(it).optString("quality") }
            AlertDialog.Builder(this).setTitle("Качество").setItems(labels.toTypedArray()) { _,i ->
                val at=player.currentPosition; val play=player.playWhenReady
                data.put("url",streams.getJSONObject(i).getString("url")).put("quality",streams.getJSONObject(i).optString("quality"));if(settings.prefs.getBoolean("saveQuality",true)) settings.prefs.edit().putString("quality",data.optString("quality")).apply();player.setMediaItem(media(),at);player.prepare();player.playWhenReady=play
            }.show()
        }
        if((data.optJSONArray("subtitles")?.length() ?: 0)>0) control("Субтитры") {
            val subtitles=data.optJSONArray("subtitles") ?: JSONArray()
            AlertDialog.Builder(this).setTitle("Субтитры").setItems((listOf("Выключены")+(0 until subtitles.length()).map {subtitles.getJSONObject(it).optString("title")}).toTypedArray()) { _,i ->
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,i==0).apply {if(i>0) setPreferredTextLanguage(subtitles.getJSONObject(i-1).optString("language"))}.build()
            }.show()
        }
        control("Блокировка") {
            controlsLocked=!controlsLocked
            for(i in 0 until toolbar.childCount) {val child=toolbar.getChildAt(i);if(child is ImageButton && child.contentDescription!="Блокировка") child.visibility=if(controlsLocked) View.GONE else View.VISIBLE}
            (toolbar.getChildAt(toolbar.childCount-1) as ImageButton).setImageDrawable(LumenIcon(if(controlsLocked) "Заблокировано" else "Блокировка",Color.WHITE))
            surface.findViewById<View>(R.id.lumen_middle).visibility=if(controlsLocked) View.INVISIBLE else View.VISIBLE
            surface.findViewById<View>(R.id.lumen_bottom_actions).visibility=if(controlsLocked) View.INVISIBLE else View.VISIBLE
            surface.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isEnabled=!controlsLocked
        }
        val bottom=surface.findViewById<LinearLayout>(R.id.lumen_bottom_actions)
        if(data.optString("filmUrl").isNotBlank()) {
            if((data.optJSONArray("seasons")?.length() ?: 0)>0 || (data.optJSONArray("voices")?.length() ?: 0)>1) control("Серия",bottom) {chooseEpisode()}
            control("Комментарии",bottom) {showComments()}
            control("В закладки",bottom) {showBookmarks()}
            control("Поделиться",bottom) {
                val path=Uri.parse(data.optString("filmUrl")).encodedPath.orEmpty()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,settings.shareLink.trimEnd('/')+path),"Поделиться"))
            }
        }
        control("Экран",bottom) { surface.resizeMode=if(surface.resizeMode==androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
        root.addView(toolbar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        surface.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { toolbar.visibility=it })
        errorView=TextView(this).apply {
            visibility=View.GONE;setTextColor(Color.WHITE);textSize=16f;gravity=Gravity.CENTER;setBackgroundColor(0xDD000000.toInt());setPadding(24,24,24,24)
            setOnClickListener {if(::player.isInitialized && !released) {visibility=View.GONE;player.prepare();player.play()}}
        }
        root.addView(errorView,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
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
            val http=OkHttpDataSource.Factory(api.client.newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.MILLISECONDS).build()).setDefaultRequestProperties(api.headers())
            val sources=DefaultDataSource.Factory(this,http)
            player=ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).setSeekBackIncrementMs(settings.prefs.getInt("seekSeconds",10)*1000L).setSeekForwardIncrementMs(settings.prefs.getInt("seekSeconds",10)*1000L).build()
            released=false;surface.player=player
            val serial=data.optJSONArray("seasons")?.length()?.let {it>0} ?: false
            surface.findViewById<ImageButton>(R.id.lumen_next).apply {visibility=if(serial) View.VISIBLE else View.GONE;setImageDrawable(LumenIcon("Следующая",Color.WHITE));setOnClickListener {nextEpisode()} }
            surface.findViewById<ImageButton>(R.id.lumen_previous).apply {visibility=if(serial) View.VISIBLE else View.GONE;setImageDrawable(LumenIcon("Предыдущая",Color.WHITE));setOnClickListener {nextEpisode(-1)} }
            player.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if(!released && state==Player.STATE_ENDED && settings.prefs.getBoolean("autoNext",true)) nextEpisode()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if(released || isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
                    errorView.text="${error.errorCodeName}\n${error.localizedMessage.orEmpty()}\nПроверьте перевод, зеркало и доступ к видео на сайте.\nНажмите, чтобы повторить."
                    errorView.visibility=View.VISIBLE

                }
            })
            player.addListener(object: Player.Listener {
                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                    surface.findViewById<TextView>(R.id.lumen_track_info).text="${size.width}×${size.height} · ${data.optString("quality")}"
                }
            })
            infoJob=lifecycleScope.launch {
                while(!released && isActive) {
                    val remaining=(player.duration-player.currentPosition).coerceAtLeast(0)
                    surface.findViewById<TextView>(R.id.lumen_end_time).text=if(player.duration>0) "Окончание в "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(System.currentTimeMillis()+(remaining/player.playbackParameters.speed.coerceAtLeast(.1f)).toLong())) else ""
                    delay(1000)
                }
            }
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
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun updateTitle() {
        titleView.text=data.optString("title")+if(data.optString("season").isNotBlank()) " · Сезон ${data.optString("season")} — Серия ${data.optString("episode")}" else ""
    }
    private fun showComments() {
        lifecycleScope.launch {
            try {
                val comments=withContext(Dispatchers.IO) {api.comments(data.optString("film"),1).first}
                if(!isFinishing && !released) AlertDialog.Builder(this@PlayerActivity).setTitle("Комментарии").setItems(comments.map {"${it.author} · ${it.date}\n${it.text}"}.ifEmpty {listOf("Нет комментариев")}.toTypedArray(),null).setNegativeButton("Закрыть",null).show()
            } catch(e: Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;Toast.makeText(this@PlayerActivity,e.message,Toast.LENGTH_LONG).show()}
        }
    }
    private fun showBookmarks() {
        if(!api.cookies.signedIn()) {Toast.makeText(this,"Войдите в аккаунт в разделе «Аккаунт»",Toast.LENGTH_LONG).show();return}
        lifecycleScope.launch {
            try {
                val categories=withContext(Dispatchers.IO) {api.bookmarks()}
                if(!isFinishing && !released) AlertDialog.Builder(this@PlayerActivity).setTitle("Закладки").setItems(categories.map {it.title}.toTypedArray()) {_,index ->
                    lifecycleScope.launch {try {withContext(Dispatchers.IO) {api.bookmark(data.optString("film"),categories[index].id)};Toast.makeText(this@PlayerActivity,"Добавлено",Toast.LENGTH_SHORT).show()} catch(e: Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;Toast.makeText(this@PlayerActivity,e.message,Toast.LENGTH_LONG).show()}}
                }.show()
            } catch(e: Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;Toast.makeText(this@PlayerActivity,e.message,Toast.LENGTH_LONG).show()}
        }
    }
    private fun chooseEpisode() {
        lifecycleScope.launch {
            try {
                val film=withContext(Dispatchers.IO) {api.film(data.getString("filmUrl"))}
                if(!isFinishing && !released) VideoSelectionDialog(this@PlayerActivity,api,film,lifecycleScope,false) {voice,season,episode,seasons,video ->
                    settings.prefs.edit().putLong(key,player.currentPosition).apply()
                    data.put("voice",voice.id).put("season",season).put("episode",episode)
                    data.put("seasons",JSONArray(seasons.map {s->JSONObject().put("id",s.id).put("title",s.title).put("episodes",JSONArray(s.episodes.map {JSONObject().put("id",it.id).put("title",it.title)}))}))
                    applyVideo(video)
                }.show()
            } catch(e: Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;Toast.makeText(this@PlayerActivity,e.message,Toast.LENGTH_LONG).show()}
        }
    }
    private fun applyVideo(video: Video) {
        val stream=video.streams.firstOrNull {it.quality==data.optString("quality")} ?: video.streams.last()
        data.put("url",stream.url).put("quality",stream.quality)
        data.put("streams",JSONArray(video.streams.map {JSONObject().put("quality",it.quality).put("url",it.url)}))
        data.put("subtitles",JSONArray(video.subtitles.map {JSONObject().put("title",it.title).put("url",it.url).put("language",it.language).put("default",it.default)}))
        errorView.visibility=View.GONE;updateTitle();player.setMediaItem(media());player.prepare();player.play()
    }
    private fun nextEpisode(direction: Int = 1) {
        val seasons=data.optJSONArray("seasons") ?: return
        val all=mutableListOf<Pair<String,String>>()
        for(s in 0 until seasons.length()) { val season=seasons.getJSONObject(s);val eps=season.getJSONArray("episodes");for(e in 0 until eps.length()) all.add(season.getString("id") to eps.getJSONObject(e).getString("id")) }
        val index=all.indexOf(data.optString("season") to data.optString("episode"))
        if(index>=0 && index+direction in all.indices) { val next=all[index+direction];changeEpisode(next.first,next.second) }
    }
    private fun changeEpisode(season: String,episode: String) {
        if(data.optString("filmUrl").isBlank()) return
        val wasPlaying=player.playWhenReady;player.pause()
        lifecycleScope.launch {
            try {
                val video=withContext(Dispatchers.IO) { val f=api.film(data.getString("filmUrl"));val voice=f.voices.firstOrNull { it.id==data.optString("voice") } ?: f.voices.first();api.video(f,voice,season,episode) }
                if(released) return@launch
                require(video.streams.isNotEmpty()) { "Видео недоступно" }
                settings.prefs.edit().putLong(key,player.currentPosition).apply()
                data.put("season",season).put("episode",episode)
                if(!released) applyVideo(video)
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
        progressJob?.cancel();infoJob?.cancel()
        if(::player.isInitialized && !released) {
            position=player.currentPosition;playing=player.playWhenReady;settings.prefs.edit().putLong(key,position).apply()
            if(data.optString("film").isNotBlank() && api.cookies.signedIn()) {
                val fields=mapOf("post_id" to data.optString("film"),"translator_id" to data.optString("voice"),"season" to data.optString("season").ifBlank { "0" },"episode" to data.optString("episode").ifBlank { "0" },"current_time" to "${(position/1000).coerceAtLeast(1)}")
                lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { api.json("/ajax/send_save",fields) } } }
            }
            released=true;surface.player=null;player.release()
        }
        super.onStop()
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(::player.isInitialized && !released && !controlsLocked && event.action==KeyEvent.ACTION_DOWN) {
            when(event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if(player.isPlaying) player.pause() else player.play();return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward();return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack();return true }
            }
        }
        return super.onKeyDown(keyCode,event)
    }
}
