package com.lumenkot.app

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*

/** Native counterpart of PlayerVideoSelector: voice list, season/episode chips, loading overlay. */
class VideoSelectionDialog(
    private val activity: Activity, private val api: RezkaApi, private val film: Film,
    private val scope: CoroutineScope, private val download: Boolean,
    private val ready: (Voice,String,String,List<Season>,Video)->Unit
): Dialog(activity) {
    private lateinit var voices: Spinner
    private lateinit var seasonsView: LinearLayout
    private lateinit var episodesView: LinearLayout
    private lateinit var choices: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private var voice=film.voices.firstOrNull { it.active } ?: film.voices.first()
    private var seasons=emptyList<Season>()
    private var season: Season?=null
    private var episode: Episode?=null
    private var job: Job?=null
    private var loading=false
    private var seasonFailure=false
    private var initialFetch=false
    private val light get()=when(api.settings.prefs.getString("theme","system")) {"light"->true;"dark"->false;else->(activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES}
    private val foreground get()=Color.parseColor(if(light) "#1C1C1C" else "#E3E3E3")
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun rounded(color: String,radius: Int=16)=GradientDrawable().apply {setColor(Color.parseColor(color));cornerRadius=dp(radius).toFloat()}
    private fun chip(label: String,selected: Boolean,action: ()->Unit)=Button(activity).apply {
        text=label;isAllCaps=false;textSize=14f;minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0
        setPadding(dp(8),dp(8),dp(8),dp(8));elevation=0f;stateListAnimator=null
        setTextColor(if(selected) Color.parseColor(if(light) "#3D1800" else "#C2E7FF") else this@VideoSelectionDialog.foreground)
        background=rounded(if(selected) (if(light) "#4A9DD9" else "#004A77") else (if(light) "#BABABA" else "#5B5B5B"))
        setOnClickListener {if(!loading) action()}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))
            background=rounded(if(light) "#DADADA" else "#252525").apply {setStroke(dp(1),Color.parseColor(if(light) "#C7C7C7" else "#383838"))}
        }
        voices=Spinner(activity)
        voices.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,film.voices.map {it.title.ifBlank {"Оригинал"}})
        voices.setSelection(film.voices.indexOf(voice));voices.visibility=if(serial && film.voices.size>1) View.VISIBLE else View.GONE
        content.addView(voices)
        val scroll=ScrollView(activity)
        choices=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        seasonsView=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};choices.addView(seasonsView)
        episodesView=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};choices.addView(episodesView)
        scroll.addView(choices);content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        progress=ProgressBar(activity);content.addView(progress)
        status=TextView(activity).apply {id=R.id.video_selection_status;setTextColor(this@VideoSelectionDialog.foreground);textSize=14f;setPadding(dp(8),dp(4),dp(8),dp(4))};content.addView(status)
        retry=chip("Повторить",false) {if(seasonFailure) loadSeasons() else fetchVideo()}.apply {id=R.id.video_selection_play;visibility=View.GONE};content.addView(retry)
        setContentView(content)
        setOnDismissListener {job?.cancel()}
        if(!serial) {
            if(film.voices.size>1) {
                choices.addView(TextView(activity).apply {text="Перевод";textSize=14f;setTextColor(this@VideoSelectionDialog.foreground);gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(8))},0)
                film.voices.forEach {v -> choices.addView(chip(v.title.ifBlank {"Оригинал"},v==voice) {voice=v;fetchVideo()},LinearLayout.LayoutParams(-1,dp(48)).apply {bottomMargin=dp(4)})}
            }
            busy(false)
        } else voices.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {val next=film.voices[position];if(next!=voice || !initialFetch) {voice=next;initialFetch=true;loadSeasons()}}
        }
    }
    override fun onStart() {
        super.onStart()
        val landscape=activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        window?.setLayout(if(landscape) dp(300) else activity.resources.displayMetrics.widthPixels-dp(16),if(landscape) minOf(dp(300),activity.resources.displayMetrics.heightPixels-dp(32)) else activity.resources.displayMetrics.heightPixels/2)
        window?.setGravity(if(landscape) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER)
        if(serial && !initialFetch) {initialFetch=true;loadSeasons()}
        if(!serial && film.voices.size==1 && !initialFetch) {initialFetch=true;fetchVideo()}
    }
    private val serial get()=film.seasons.isNotEmpty() || film.card.url.contains("/series/") || film.card.url.contains("/show/")
    private fun busy(value: Boolean) {loading=value;progress.visibility=if(value) View.VISIBLE else View.GONE;retry.isEnabled=!value;voices.isEnabled=!value}
    private fun loadSeasons() {
        job?.cancel();seasonsView.removeAllViews();episodesView.removeAllViews();season=null;episode=null;status.text="";retry.visibility=View.GONE;seasonFailure=false
        busy(true)
        job=scope.launch {
            try {
                seasons=withContext(Dispatchers.IO) {api.seasons(film,voice)}
                check(seasons.isNotEmpty() && seasons.any {it.episodes.isNotEmpty()}) {"Сервис не вернул серии для выбранного перевода"}
                selectSeason(seasons.first {it.episodes.isNotEmpty()})
            } catch(e: CancellationException) {throw e} catch(e: Exception) {seasonFailure=true;status.text=e.message?.takeIf {it.isNotBlank()} ?: "Не удалось получить серии";retry.visibility=View.VISIBLE} finally {if(isShowing) busy(false)}
        }
    }
    private fun renderChips(parent: LinearLayout,items: List<Pair<String,Boolean>>,onSelect: (Int)->Unit) {
        parent.removeAllViews()
        val columns=if(activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE) 3 else 4
        items.withIndex().chunked(columns).forEach {chunk ->
            val row=LinearLayout(activity)
            chunk.forEach {indexed -> row.addView(chip(indexed.value.first,indexed.value.second) {onSelect(indexed.index)},LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(2),dp(2),dp(2),dp(2))})}
            repeat(columns-chunk.size) {row.addView(View(activity),LinearLayout.LayoutParams(0,1,1f))}
            parent.addView(row)
        }
    }
    private fun selectSeason(s: Season) {
        season=s;episode=s.episodes.firstOrNull()
        renderChips(seasonsView,seasons.map {it.title to (it.id==s.id)}) {selectSeason(seasons[it])}
        renderEpisodes();status.text=""
    }
    private fun renderEpisodes() {
        val episodes=season?.episodes.orEmpty()
        renderChips(episodesView,episodes.map {it.title to (it.id==episode?.id)}) {index ->episode=episodes[index];renderEpisodes();fetchVideo()}
    }
    private fun fetchVideo() {
        if(loading) return
        if(serial && (season==null || episode==null)) {status.text="Выберите серию";return}
        busy(true);status.text="Получение видео…";retry.visibility=View.GONE
        job=scope.launch {
            try {
                val v=voice;val s=season?.id.orEmpty();val ep=episode?.id.orEmpty()
                val video=withContext(Dispatchers.IO) {api.video(film,v,s,ep)}
                check(video.streams.isNotEmpty()) {"Сервис не вернул видеопотоки. Попробуйте другой перевод или проверьте доступ к видео на сайте."}
                ready(v,s,ep,seasons,video);dismiss()
            } catch(e: CancellationException) {throw e} catch(e: Exception) {status.text=e.message?.takeIf {it.isNotBlank()} ?: "Не удалось получить видео";retry.visibility=View.VISIBLE} finally {if(isShowing) busy(false)}
        }
    }
}
