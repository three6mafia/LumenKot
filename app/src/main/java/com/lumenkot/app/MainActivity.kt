package com.lumenkot.app

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var api: RezkaApi
    private lateinit var body: LinearLayout
    private lateinit var heading: TextView
    private lateinit var sectionTabs: LinearLayout
    private val back = ArrayDeque<() -> Unit>()
    private var current: (() -> Unit)? = null
    private var generation = 0
    private val light: Boolean get() = when(settings.prefs.getString("theme","system")) { "light" -> true; "dark" -> false;else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES }
    private val foreground get() = if(light) Color.parseColor("#1C1C1C") else Color.parseColor("#E3E3E3")
    private val accent get() = if(light) Color.parseColor("#4A9DD9") else Color.parseColor("#004A77")
    private val muted get() = if(light) Color.parseColor("#3B3838") else Color.parseColor("#C4C7C5")
    private val tileBase get() = if(light) Color.parseColor("#C7C7C7") else Color.parseColor("#383838")
    private val tileFocus get() = if(light) Color.parseColor("#1C1C1C") else Color.parseColor("#E3E3E3")
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { search(it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)
        if(light) setTheme(R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        api = RezkaApi(settings)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(back.isEmpty()) finish() else { current=back.removeLast(); current?.invoke() } }
        })
        home()
        intent.data?.let { openLink(it.toString()) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f, color: Int = foreground) = TextView(this).apply {
        text=value; textSize=size; setTextColor(color); setPadding(dp(4),dp(5),dp(4),dp(5))
    }
    private fun panel() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(12)) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text=label; isAllCaps=false; minHeight=dp(48); setTextColor(this@MainActivity.foreground)
        background=GradientDrawable().apply { setColor(tileBase);cornerRadius=dp(8).toFloat() };elevation=0f;stateListAnimator=null
        setOnClickListener { action() }; setOnFocusChangeListener { _, focused ->
            background=GradientDrawable().apply { setColor(if(focused) tileFocus else tileBase);cornerRadius=dp(8).toFloat() }
            setTextColor(if(focused) (if(light) Color.WHITE else Color.BLACK) else this@MainActivity.foreground)
        }
    }
    private fun row(vararg views: View) = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; views.forEach { addView(it) } }
    private fun horizontal(content: LinearLayout): HorizontalScrollView = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(content) }
    private fun page(title: String, action: () -> Unit, remember: Boolean = true) {
        if(remember) current?.let { back.addLast(it) }
        current=action; action()
    }
    private val tv get() = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_TELEVISION
    private fun navIcon(name: String, action: () -> Unit): View = ImageButton(this).apply {
        setImageDrawable(LumenIcon(name,this@MainActivity.foreground));if(name=="Аккаунт") setImageResource(R.drawable.profile_placeholder);contentDescription=name
        setBackgroundColor(Color.TRANSPARENT);setPadding(dp(12),dp(12),dp(12),dp(12))
        isFocusable=true;setOnClickListener { action() }
        setOnFocusChangeListener { _,focused -> background=GradientDrawable().apply {setColor(if(focused) tileBase else Color.TRANSPARENT);cornerRadius=dp(24).toFloat()} }
    }
    private fun shell(title: String) {
        generation++
        val root=LinearLayout(this).apply { orientation=if(tv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;setBackgroundColor(if(light) Color.WHITE else Color.BLACK) }
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        if(tv) root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            listOf("Аккаунт" to {account()},"Уведомления" to {notifications()},"Главная" to {home()},"История" to {history()},"Поиск" to {search()},"Закладки" to {bookmarks()},"Настройки" to {preferences()}).forEach { (name,go) -> addView(navIcon(name,go),LinearLayout.LayoutParams(dp(48),dp(48))) }
        },LinearLayout.LayoutParams(dp(68),-1))
        root.addView(content,if(tv) LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        heading=text(title,20f).apply {setTypeface(null,Typeface.BOLD)}
        if(title!="Смотреть сегодня" && title!="Поиск") {
            val header=row()
            if(title!="Аккаунт" && title!="Закладки" && title!="История просмотра") header.addView(navIcon("Назад") {onBackPressedDispatcher.onBackPressed()},LinearLayout.LayoutParams(dp(48),dp(48)))
            header.addView(heading,LinearLayout.LayoutParams(0,dp(48),1f))
            if(title=="Аккаунт") header.addView(navIcon("Настройки") {preferences()},LinearLayout.LayoutParams(dp(48),dp(48)))
            content.addView(header)
        }
        body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),dp(8))}
        content.addView(ScrollView(this).apply {isFillViewport=true;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        sectionTabs=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        content.addView(sectionTabs)
        if(!tv && title!="Вход") {
            root.addView(View(this).apply {setBackgroundColor(if(light) Color.parseColor("#B1B1B1") else Color.parseColor("#4E4E4E"))},LinearLayout.LayoutParams(-1,dp(1)))
            root.addView(row().apply {
                listOf("Главная" to {home()},"Поиск" to {search()},"Закладки" to {bookmarks()},"История" to {history()},"Аккаунт" to {account()}).forEach { (name,go) -> addView(navIcon(name,go),LinearLayout.LayoutParams(0,dp(50),1f)) }
            })
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) {v,insets ->val i=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom);insets}
    }
    private fun <T> load(work: () -> T, done: (T) -> Unit) {
        val screen=generation
        val progress=ProgressBar(this); body.addView(progress)
        lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.IO) { work() }
                if(screen==generation) { body.removeView(progress); done(result) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(screen==generation) {
                body.removeView(progress); body.addView(text(e.message ?: "Не удалось загрузить данные",16f,Color.rgb(255,167,150)))
                body.addView(button("Повторить") { current?.invoke() })
            } }
        }
    }
    private fun toast(s: String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun note(value: String) { body.addView(text(value,15f,muted)) }
    private fun cards(items: List<Card>, open: (Card) -> Unit = { film(it.url) }) {
        if(items.isEmpty()) { note("Ничего не найдено"); return }
        val columns=settings.prefs.getInt("columns",if(resources.configuration.screenWidthDp >= 960) 6 else if(resources.configuration.screenWidthDp >= 600) 4 else 3).coerceIn(2,8)
        val grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        items.chunked(columns).forEach { chunk ->
            val line=LinearLayout(this)
            chunk.forEach { card ->
                val tile=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    isFocusable=true; isClickable=true; contentDescription=card.title + ". " + card.info
                    background=GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius=dp(12).toFloat() }
                    setOnClickListener { open(card) }
                    setOnFocusChangeListener { _,focus -> background=GradientDrawable().apply { setColor(if(focus) tileFocus else Color.TRANSPARENT); cornerRadius=dp(12).toFloat(); if(focus) setStroke(dp(2),accent) } }
                }
                if(card.image.isNotBlank()) {
                    val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    val poster=FrameLayout(this).apply {background=GradientDrawable().apply {setColor(if(light) Color.LTGRAY else Color.DKGRAY);cornerRadius=dp(12).toFloat()};clipToOutline=true}
                    poster.addView(image,FrameLayout.LayoutParams(-1,-1))
                    if(card.type.isNotBlank()) poster.addView(text(card.type,10f,Color.WHITE).apply {setBackgroundColor(if(card.type=="Сериал") Color.rgb(220,86,93) else Color.rgb(0,155,174));setPadding(dp(4),0,dp(4),0)},FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
                    if(card.badge.isNotBlank()) poster.addView(text(card.badge,10f,Color.WHITE).apply {setBackgroundColor(Color.rgb(220,86,93));setPadding(dp(4),0,dp(4),0)},FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START))
                    tile.addView(poster,LinearLayout.LayoutParams(-1,dp(((resources.configuration.screenWidthDp-(if(tv) 68 else 0)-32-columns*8)/columns*250f/166).toInt())))
                    lifecycleScope.launch {
                        val bitmap=withContext(Dispatchers.IO) { runCatching {
                            val u=if(card.image.startsWith("http")) card.image else if(card.image.startsWith("//")) "https:${card.image}" else Links.static+"/"+card.image.trimStart('/')
                            api.client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r -> if(r.isSuccessful) r.body?.byteStream()?.use { android.graphics.BitmapFactory.decodeStream(it) } else null }
                        }.getOrNull() }
                        if(image.isAttachedToWindow) image.setImageBitmap(bitmap)
                    }
                }
                tile.addView(text(card.title,12f).apply { setTypeface(null,Typeface.BOLD); maxLines=2 })
                tile.addView(text(card.info,10f,muted).apply { maxLines=2 })
                line.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) })
            }
            repeat(columns-chunk.size) { line.addView(View(this),LinearLayout.LayoutParams(0,1,1f)) }
            grid.addView(line)
        }
        body.addView(grid)
    }
    private fun pagination(page: Int, pages: Int, go: (Int) -> Unit) {
        val controls=row(); if(page>1) controls.addView(button("← Назад") { go(page-1) })
        controls.addView(text("$page / $pages",14f,muted)); if(page<pages) controls.addView(button("Дальше →") { go(page+1) }); body.addView(controls)
    }
    private fun home(page: Int = 1, section: Int = 0, genre: String = "", remember: Boolean = true) {
        page("Главная", {
            shell("Смотреть сегодня")
            val names=listOf("Горячие новинки","Новинки","Сейчас смотрят","Популярные","В ожидании")
            sectionTabs.addView(horizontal(row(*names.mapIndexed { i,n ->
                text(if(i==section) "$n\n${mapOf("" to "Все","1" to "Фильмы","2" to "Сериалы","3" to "Мультфильмы","82" to "Аниме")[genre] ?: "Все"}" else n,12f).apply {
                    gravity=Gravity.CENTER;setPadding(dp(8),dp(10),dp(8),dp(8));isFocusable=true;isClickable=true
                    if(i==section) setTypeface(null,Typeface.BOLD)
                    setOnClickListener {
                        if(i==section) select("Тип",listOf("Все","Фильмы","Сериалы","Мультфильмы","Аниме")) { option ->home(1,i,listOf("","1","2","3","82")[option],false) }
                        else home(1,i,genre,false)
                    }
                }
            }.toTypedArray())))
            load({ if(section==0) api.hot(genre.ifBlank { "0" }) else api.catalog(if(section==4) "/announce" else "/new",page,if(section==2) "watching" else if(section==3) "popular" else "",genre) }) {
                cards(it.cards); pagination(page,it.pages) { n -> home(n,section,genre,false) }
            }
        },remember)
    }
    private fun search(query: String = "", n: Int = 1, remember: Boolean = true) {
        page("Поиск", {
            shell("Поиск")
            val input=AutoCompleteTextView(this).apply { hint="Фильм, сериал, актёр";threshold=2; setText(query); isSingleLine=true; imeOptions=EditorInfo.IME_ACTION_SEARCH; setOnEditorActionListener { _,_,_ -> search(text.toString(),1,false);true } }
            var suggestionsJob: Job? = null
            input.addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                    suggestionsJob?.cancel();val term=s.toString()
                    if(term.length>=2) suggestionsJob=lifecycleScope.launch {
                        delay(350)
                        val choices=withContext(Dispatchers.IO) { runCatching { api.suggestions(term) }.getOrDefault(emptyList()) }
                        if(input.isAttachedToWindow && input.text.toString()==term) input.setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,choices))
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            val searchBar=row()
            searchBar.addView(navIcon("Голосом") {
                runCatching {speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))}.onFailure {toast("Распознавание речи недоступно")}
            },LinearLayout.LayoutParams(dp(40),dp(44)))
            input.hint="Поиск";input.textSize=14f
            searchBar.addView(input,LinearLayout.LayoutParams(0,dp(44),1f))
            searchBar.addView(navIcon("Фильтры") {categories()},LinearLayout.LayoutParams(dp(40),dp(44)))
            searchBar.addView(navIcon("Подборки") {collections()},LinearLayout.LayoutParams(dp(40),dp(44)))
            body.addView(searchBar)
            if(query.isNotBlank()) load({ api.search(query,n) }) { result -> cards(result.cards);pagination(n,result.pages) { search(query,it,false) } }
        },remember)
    }
    private fun listing(title: String, path: String, n: Int = 1, remember: Boolean = true) {
        page(title, { shell(title); load({ api.catalog(path,n) }) { cards(it.cards);pagination(n,it.pages) { p -> listing(title,path,p,false) } } },remember)
    }
    private fun categories() {
        page("Каталог", {
            shell("Каталог")
            listOf("Фильмы" to "/films","Сериалы" to "/series","Мультфильмы" to "/cartoons","Аниме" to "/animation","Телепередачи" to "/show").forEach { (n,p) -> body.addView(button(n) { listing(n,p) }) }
            load({ api.categories() }) { groups -> groups.forEach { category ->
                if(category.title.isNotBlank() && category.genres.isNotEmpty()) body.addView(button(category.title + " · жанры и годы") {
                    select("Жанр",category.genres.map { it.title }) { g ->
                        val genre=category.genres[g]
                        select("Год",category.years.map { it.title }) { y ->
                            val year=category.years[y].id
                            val path=when(year) { "-1" -> genre.id.replace("/best", ""); "0" -> genre.id; else -> genre.id+year }
                            listing("${category.title} · ${genre.title}",path)
                        }
                    }
                })
            } }
        })
    }
    private fun collections(n: Int = 1, remember: Boolean = true) {
        page("Подборки", { shell("Подборки");load({ api.collections(n) }) { cards(it.cards) { c -> listing(c.title,c.url) };pagination(n,it.pages) { collections(it,false) } } },remember)
    }
    private fun openLink(url: String) {
        val path=runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(url)
        when {
            path.endsWith(".html") -> film(url)
            path.contains("/person/") -> actor(url)
            url.startsWith("http") && !Links.providers.any { url.startsWith(it+"/") } && !url.startsWith(Links.official+"/") && !url.startsWith("https://hdrezka.tv/") -> external(url)
            else -> listing("Каталог",path.ifBlank { "/new" })
        }
    }
    private fun film(url: String) {
        page("Фильм", {
            shell("Загрузка фильма")
            load({ api.film(url) }) { f ->
                heading.text=f.card.title; note(f.original);note(f.info);body.addView(text(f.description));if(f.restricted.isNotBlank()) note(f.restricted)
                val controls=row(button("▶ Смотреть") { chooseVoice(f,false) },button("↓ Скачать") { chooseVoice(f,true) },button("Закладка") {
                    if(f.bookmarks.isEmpty()) toast("Войдите в аккаунт для закладок") else select("Добавить / убрать закладку",f.bookmarks.map { it.title }) { i -> load({ api.bookmark(f.card.id,f.bookmarks[i].id) }) { toast("Закладка обновлена") } }
                },button("Поделиться") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,(if(settings.official) settings.shareLink else settings.provider)+Uri.parse(api.absolute(f.card.url)).encodedPath),"Поделиться")) })
                body.addView(horizontal(controls))
                body.addView(horizontal(row(button("Комментарии") { comments(f) },button("Трейлер") { load({ api.trailer(f.card.id) }) { if(it.isNotBlank()) external(it) else toast("Трейлер недоступен") } },button("Оценить") { select("Ваша оценка",(1..10).map { "$it" }) { i -> load({ api.rating(f.card.id,i+1) }) { toast("Оценка: $it") } } })))
                if(f.schedule.isNotBlank()) body.addView(button("Расписание серий") { AlertDialog.Builder(this).setTitle("Расписание").setMessage(f.schedule).setPositiveButton("Закрыть",null).show() })
                f.links.forEach { c -> body.addView(button(c.title) { openLink(c.id) }) }
                if(f.related.isNotEmpty()) { body.addView(text("Похожие фильмы",22f,accent)); cards(f.related) }
            }
        })
    }
    private fun chooseVoice(f: Film, download: Boolean) {
        if(f.restricted.isNotBlank()) { toast(f.restricted);return }
        VideoSelectionDialog(this,api,f,lifecycleScope,download) { v,season,episode,seasons,video ->
            deliverVideo(f,v,season,episode,download,seasons,video)
        }.show()
    }
    private fun deliverVideo(f: Film,v: Voice,s: String,e: String,download: Boolean,seasons: List<Season>,video: Video) {
            if(video.streams.isEmpty()) toast("Потоки недоступны") else chooseQuality(video,download) { i ->
                if(download) enqueue(f,video.streams[i],s,e,video.subtitles) else {
                    settings.history(f.card)
                    val payload=JSONObject().put("title",f.card.title).put("subtitle",f.card.info).put("filmUrl",f.card.url).put("film",f.card.id).put("voice",v.id).put("season",s).put("episode",e).put("url",video.streams[i].url).put("quality",video.streams[i].quality)
                    payload.put("seasons",JSONArray(seasons.map { season -> JSONObject().put("id",season.id).put("title",season.title).put("episodes",JSONArray(season.episodes.map { JSONObject().put("id",it.id).put("title",it.title) })) }))
                    payload.put("voices",JSONArray(f.voices.map { JSONObject().put("id",it.id).put("title",it.title) }))
                    payload.put("streams",JSONArray(video.streams.map { JSONObject().put("quality",it.quality).put("url",it.url) }))
                    payload.put("subtitles",JSONArray(video.subtitles.map { JSONObject().put("title",it.title).put("url",it.url).put("language",it.language).put("default",it.default) }))
                    startActivity(Intent(this,PlayerActivity::class.java).putExtra("media",payload.toString()))
                }
            }
    }
    private fun chooseQuality(video: Video,download: Boolean,done: (Int) -> Unit) {
        fun chosen(i: Int) {
            if(settings.prefs.getBoolean("saveQuality",true)) settings.prefs.edit().putString("quality",video.streams[i].quality).apply()
            done(i)
        }
        if(download || settings.prefs.getBoolean("askQuality",false)) select("Качество",video.streams.map { it.quality },::chosen)
        else {
            val index=video.streams.indexOfFirst { it.quality==settings.prefs.getString("quality","") }
            chosen(if(index>=0) index else video.streams.lastIndex)
        }
    }
    private fun select(title: String, options: List<String>, done: (Int) -> Unit) {
        if(options.isEmpty()) { toast("Нет доступных вариантов"); return }
        AlertDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()) { _, i -> done(i) }.setNegativeButton("Отмена",null).show()
    }
    private fun comments(f: Film,n: Int = 1,remember: Boolean = true) {
        page("Комментарии", { shell("Комментарии · ${f.card.title}");load({ api.comments(f.card.id,n) }) { (items,pages) ->
            items.forEach { c ->
                body.addView(text("${c.author} · ${c.date}",16f,accent))
                val comment=text("").apply { movementMethod=android.text.method.LinkMovementMethod.getInstance() }
                fun render(expanded: Boolean) {
                    val fragment=org.jsoup.Jsoup.parseBodyFragment(c.html)
                    fragment.select(".title_spoiler").remove()
                    if(!expanded) fragment.select(".text_spoiler").forEach { it.text("[Спойлер скрыт]") }
                    comment.text=if(c.html.isBlank()) c.text else android.text.Html.fromHtml(fragment.body().html(),android.text.Html.FROM_HTML_MODE_COMPACT)
                }
                render(false);body.addView(comment)
                if(c.html.contains("text_spoiler")) { var expanded=false;body.addView(button("Показать / скрыть спойлер") { expanded=!expanded;render(expanded) }) }
                body.addView(button("♥ ${c.likes}") { load({ api.like(c.id) }) { toast("Отметок: $it") } })
            };pagination(n,pages) { comments(f,it,false) }
        } },remember)
    }
    private fun bookmarks() {
        page("Закладки", { shell("Закладки"); if(!api.cookies.signedIn()) body.addView(button("Войти в аккаунт") { account() }) else load({ api.bookmarks() }) { list -> list.forEach { c -> body.addView(button(c.title) { listing(c.title,"/favorites/${c.id}") }) } } })
    }
    private fun history() {
        page("История", { shell("История просмотра");body.addView(button("Очистить локальную историю") {
            AlertDialog.Builder(this).setMessage("Удалить историю на этом устройстве?").setPositiveButton("Удалить") { _,_ -> settings.clearHistory();current?.invoke() }.setNegativeButton("Отмена",null).show()
        });cards(settings.history());if(api.cookies.signedIn()) { body.addView(text("Продолжить · аккаунт",22f,accent));load({ api.recent() }) { list -> cards(list) { c ->
            select(c.title,listOf("Открыть","Скрыть","Удалить из истории")) { i -> when(i) { 0 -> film(c.url);1 -> load({ api.hideRecent(c.id) }) { current?.invoke() };2 -> load({ api.deleteRecent(c.id) }) { current?.invoke() } } }
        } } } })
    }
    private fun notifications() { page("Уведомления", { shell("Новые серии");if(!api.cookies.signedIn()) note("Войдите в аккаунт, чтобы видеть обновления") else load({ api.notifications() }) { cards(it) } }) }
    private fun actor(url: String) { page("Актёр", { shell("Фильмография");load({ api.actor(url) }) { (info,list) -> note(info);cards(list) } }) }
    private fun account() {
        page("Аккаунт", {
            shell("Аккаунт")
            if(api.cookies.signedIn()) {body.addView(button("Обновления") {notifications()});body.addView(button("Загрузки") {downloads()})}
            if(api.cookies.signedIn()) {
                load({ api.profile() }) { body.addView(text(it,20f)) }
                body.addView(button("Выйти") { api.cookies.clear(); current?.invoke() })
            } else {
                body.addView(text("Вы не авторизованы",20f).apply {gravity=Gravity.CENTER})
                body.addView(text("Войдите, чтобы синхронизировать контент",14f,muted).apply {gravity=Gravity.CENTER})
                body.addView(button("Перейти на страницу входа") {login()})
                body.addView(button("Загрузки") {downloads()})

            }
        })
    }
    private fun login() {
        page("Вход", {
            shell("Вход")
            body.addView(text("Вы не авторизованы",20f))
            body.addView(text("Войдите, чтобы синхронизировать контент",14f,muted))
                val name=EditText(this).apply { hint="Логин или email";isSingleLine=true;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
                val password=EditText(this).apply { hint="Пароль";isSingleLine=true;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                body.addView(name);body.addView(password)
                body.addView(button("Войти") {
                    val login=name.text.toString();val secret=password.text.toString()
                    if(login.isBlank() || secret.isBlank()) toast("Введите логин и пароль") else load({ api.login(login,secret) }) { password.text.clear();account() }
                })
        })
    }
    private fun edit(title: String,value: String,save: (String) -> Unit) {
        val input=EditText(this).apply { setText(value);isSingleLine=true }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Сохранить") { _,_ -> runCatching { save(input.text.toString().trim());current?.invoke() }.onFailure { toast(it.message ?: "Ошибка") } }.setNegativeButton("Отмена",null).show()
    }
    private fun preferences() {
        page("Настройки", {
            shell("Настройки")
            body.addView(button("Тема интерфейса") { select("Тема",listOf("Системная","Тёмная","Светлая")) { i -> settings.prefs.edit().putString("theme",listOf("system","dark","light")[i]).apply();recreate() } })
            body.addView(Switch(this).apply { text="Официальный режим"; isChecked=settings.official;setOnCheckedChangeListener { _,checked -> settings.official=checked } })
            body.addView(button("Зеркало: ${settings.provider}") {
                select("Зеркало",Links.providers+"Другое…") { i -> if(i<Links.providers.size) { settings.provider=Links.providers[i];current?.invoke() } else edit("Адрес зеркала",settings.provider) { settings.provider=it } }
            })
            body.addView(button("Зеркало для ссылки «Поделиться»") { select("Зеркало ссылки",Links.providers) { i -> settings.shareLink=Links.providers[i] } })
            body.addView(Switch(this).apply { text="Спрашивать качество видео";isChecked=settings.prefs.getBoolean("askQuality",false);setOnCheckedChangeListener { _,v -> settings.prefs.edit().putBoolean("askQuality",v).apply() } })
            body.addView(Switch(this).apply { text="Запоминать качество видео";isChecked=settings.prefs.getBoolean("saveQuality",true);setOnCheckedChangeListener { _,v -> settings.prefs.edit().putBoolean("saveQuality",v).apply() } })
            body.addView(button("CDN: ${settings.cdn.ifBlank { "Автоматически" }}") { select("CDN",listOf("Автоматически")+Links.cdns+"Другое…") { i ->
                when { i==0 -> { settings.cdn="";current?.invoke() };i<=Links.cdns.size -> { settings.cdn=Links.cdns[i-1];current?.invoke() };else -> edit("CDN",settings.cdn) { require(it.startsWith("https://"));settings.cdn=it } }
            } })
            body.addView(Switch(this).apply { text="Скачивать субтитры";isChecked=settings.prefs.getBoolean("downloadSubtitles",true);setOnCheckedChangeListener { _,v -> settings.prefs.edit().putBoolean("downloadSubtitles",v).apply() } })
            body.addView(Switch(this).apply { text="Скачивать постер";isChecked=settings.prefs.getBoolean("downloadPoster",true);setOnCheckedChangeListener { _,v -> settings.prefs.edit().putBoolean("downloadPoster",v).apply() } })
            body.addView(button("Хранилище загрузок") { val dirs=getExternalFilesDirs("downloads").filterNotNull();select("Хранилище",dirs.mapIndexed { i,_ -> if(i==0) "Внутренняя память" else "SD-карта $i" }) { i -> settings.prefs.edit().putInt("downloadStorage",i).apply() } })
            body.addView(button("Колонок в каталоге") { select("Колонки",(2..8).map { "$it" }) { i -> settings.prefs.edit().putInt("columns",i+2).apply();current?.invoke() } })
            body.addView(button("Перемотка: ${settings.prefs.getInt("seekSeconds",10)} сек") { select("Шаг перемотки",listOf("5","10","15","30","60")) { i -> settings.prefs.edit().putInt("seekSeconds",listOf(5,10,15,30,60)[i]).apply();current?.invoke() } })
            body.addView(Switch(this).apply { text="Следующая серия автоматически";isChecked=settings.prefs.getBoolean("autoNext",true);setOnCheckedChangeListener { _,checked -> settings.prefs.edit().putBoolean("autoNext",checked).apply() } })
            body.addView(button("Скорость по умолчанию") { val speeds=listOf(.5f,.75f,1f,1.25f,1.5f,2f);select("Скорость",speeds.map { "${it}×" }) { i -> settings.prefs.edit().putFloat("speed",speeds[i]).apply() } })
            body.addView(button("User-Agent") { edit("User-Agent",settings.userAgent) { settings.userAgent=it } })
            body.addView(button("Проверить соединение") { load({ api.request("/");"Соединение установлено" }) { toast(it) } })
            body.addView(Switch(this).apply { text="Синхронизация прогресса (Time share)";isChecked=settings.prefs.getBoolean("cloudProgress",false);setOnCheckedChangeListener { _,checked -> settings.prefs.edit().putBoolean("cloudProgress",checked).apply() } })
            body.addView(button("Обновления оригинального Lumen") { external(Links.updates) })
            body.addView(button("Тест плеера") { startActivity(Intent(this,PlayerActivity::class.java).putExtra("media",JSONObject().put("title","Big Buck Bunny").put("url",Links.demo).toString())) })
            body.addView(button("GitHub") { external(Links.github) });body.addView(button("Telegram") { external(Links.telegram) })
            body.addView(button("Поддержка зеркал") { external("mailto:${Links.support}") })
            note("LumenKot 1.0 · Kotlin / Android\nНативный перенос Lumen. Оригинал: falcofemoralis/lumen. Лицензия MIT.")
        })
    }
    private fun enqueue(f: Film,stream: Stream,s: String,e: String,subtitles: List<Subtitle>) {
        if(stream.url.contains(".m3u8")) { toast("Для загрузки выберите MP4; HLS предназначен для просмотра");return }
        runCatching {
            val filename=(f.card.title+if(e.isNotBlank()) " S${s}E${e}" else "").replace(Regex("[^\\p{L}\\p{N} ._-]"),"_")+".mp4"
            val folders=getExternalFilesDirs("downloads").filterNotNull()
            val folder=folders.getOrElse(settings.prefs.getInt("downloadStorage",0)) { folders.first() }.apply { mkdirs() }
            val request=DownloadManager.Request(Uri.parse(stream.url)).setTitle(f.card.title).setDescription(stream.quality).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationUri(Uri.fromFile(java.io.File(folder,filename)))
            api.headers().forEach { (k,v) -> request.addRequestHeader(k,v) }
            val id=(getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            val manager=getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            fun sidecar(url: String,name: String): Long {
                val r=DownloadManager.Request(Uri.parse(url)).setTitle(name).setDestinationUri(Uri.fromFile(java.io.File(folder,name)))
                api.headers().forEach { (k,v) -> r.addRequestHeader(k,v) }
                return manager.enqueue(r)
            }
            val metadata=JSONObject();val entries=JSONArray()
            if(settings.prefs.getBoolean("downloadSubtitles",true)) subtitles.forEach { sub ->
                val extension=if(sub.url.substringBefore('?').endsWith(".srt")) "srt" else "vtt"
                val name=filename.removeSuffix(".mp4")+"."+sub.title.replace(Regex("[^\\p{L}\\p{N}_-]"),"_")+"."+extension
                val sid=sidecar(sub.url,name)
                entries.put(JSONObject().put("id",sid).put("title",sub.title).put("language",sub.language).put("default",sub.default).put("mime",if(extension=="srt") "application/x-subrip" else "text/vtt"))
            }
            metadata.put("subtitles",entries)
            if(settings.prefs.getBoolean("downloadPoster",true) && f.card.image.isNotBlank()) {
                val image=if(f.card.image.startsWith("http")) f.card.image else Links.static+"/"+f.card.image.trimStart('/')
                metadata.put("poster",sidecar(image,filename.removeSuffix(".mp4")+".jpg"))
            }
            val ids=settings.prefs.getStringSet("downloads",emptySet())!!.toMutableSet();ids.add(id.toString());settings.prefs.edit().putStringSet("downloads",ids).putString("download:$id",metadata.toString()).apply();toast("Загрузка добавлена")
        }.onFailure { toast(it.message ?: "Не удалось начать загрузку") }
    }
    private fun downloads() {
        page("Загрузки", {
            shell("Загрузки");body.addView(button("Обновить") { current?.invoke() })
            val manager=getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val ids=settings.prefs.getStringSet("downloads",emptySet())!!.mapNotNull { it.toLongOrNull() }
            if(ids.isEmpty()) note("Загрузок пока нет. Откройте фильм и выберите «Скачать».")
            ids.forEach { id -> manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if(c.moveToFirst()) {
                    val title=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));val status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));val downloaded=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    body.addView(text(title,19f));note(when(status) { DownloadManager.STATUS_SUCCESSFUL -> "Готово";DownloadManager.STATUS_FAILED -> "Ошибка: ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}";DownloadManager.STATUS_PAUSED -> "Приостановлена системой";else -> if(total>0) "${downloaded*100/total}%" else "Загрузка…" })
                    val metadata=runCatching { JSONObject(settings.prefs.getString("download:$id","{}")!!) }.getOrDefault(JSONObject())
                    val subs=metadata.optJSONArray("subtitles") ?: JSONArray()
                    if(status==DownloadManager.STATUS_SUCCESSFUL) body.addView(button("▶ Открыть") { manager.getUriForDownloadedFile(id)?.let { uri ->
                        val offline=JSONArray()
                        for(i in 0 until subs.length()) { val sub=subs.getJSONObject(i);val subUri=manager.getUriForDownloadedFile(sub.getLong("id"));if(subUri!=null) offline.put(JSONObject(sub.toString()).put("url",subUri.toString())) }
                        startActivity(Intent(this,PlayerActivity::class.java).putExtra("media",JSONObject().put("title",title).put("url",uri.toString()).put("subtitles",offline).toString()))
                    } })
                    body.addView(button("Удалить") { AlertDialog.Builder(this).setMessage("Удалить загрузку и файл?").setPositiveButton("Удалить") { _,_ -> val group=mutableListOf(id);for(i in 0 until subs.length()) group.add(subs.getJSONObject(i).getLong("id"));if(metadata.has("poster")) group.add(metadata.getLong("poster"));manager.remove(*group.toLongArray());settings.prefs.edit().remove("download:$id").putStringSet("downloads",ids.filter { it!=id }.map { it.toString() }.toSet()).apply();current?.invoke() }.setNegativeButton("Отмена",null).show() })
                }
            } }
        })
    }
    private fun external(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }.onFailure { toast("Нет приложения для открытия ссылки") } }
}
