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
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var api: RezkaApi
    private lateinit var body: LinearLayout
    private lateinit var heading: TextView
    private val back = ArrayDeque<() -> Unit>()
    private var current: (() -> Unit)? = null
    private var generation = 0
    private val light: Boolean get() = when(settings.prefs.getString("theme","system")) { "light" -> true; "dark" -> false;else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES }
    private val foreground get() = if(light) Color.rgb(23,33,23) else Color.WHITE
    private val accent get() = if(light) Color.rgb(50,105,20) else Color.rgb(181,243,107)
    private val muted get() = if(light) Color.rgb(75,91,75) else Color.rgb(163,174,161)
    private val tileBase get() = if(light) Color.rgb(236,242,232) else Color.rgb(29,37,29)
    private val tileFocus get() = if(light) Color.rgb(214,236,193) else Color.rgb(54,73,42)
    private val downloadNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
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
        setOnClickListener { action() }; setOnFocusChangeListener { _, focused -> setTextColor(if(focused) accent else this@MainActivity.foreground) }
    }
    private fun row(vararg views: View) = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; views.forEach { addView(it) } }
    private fun horizontal(content: LinearLayout): HorizontalScrollView = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(content) }
    private fun page(title: String, action: () -> Unit, remember: Boolean = true) {
        if(remember) current?.let { back.addLast(it) }
        current=action; action()
    }
    private fun shell(title: String) {
        generation++
        val root=panel().apply { setPadding(dp(12),0,dp(12),0); setBackgroundColor(if(light) Color.rgb(250,252,247) else Color.rgb(16,21,16)) }
        val brand = text("LUMEN",22f,accent).apply { setTypeface(null,Typeface.BOLD); letterSpacing=.14f }
        val nav=row(button("‹") { onBackPressedDispatcher.onBackPressed() },brand)
        root.addView(nav)
        val menu=row(button("Главная") { home() },button("Поиск") { search() },button("Каталог") { categories() },button("Подборки") { collections() },button("Закладки") { bookmarks() },button("История") { history() },button("Загрузки") { downloads() },button("Уведомления") { notifications() },button("Аккаунт") { account() },button("Настройки") { preferences() })
        root.addView(horizontal(menu))
        heading=text(title,26f).apply { setTypeface(null,Typeface.BOLD) }; root.addView(heading)
        body=panel()
        root.addView(ScrollView(this).apply { isFillViewport=true; addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                androidx.compose.runtime.key(root) { AndroidView(factory={ root },modifier=Modifier.fillMaxSize()) }
            }
        }
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
        body.addView(androidx.compose.ui.platform.ComposeView(this).apply {
            id=View.generateViewId()
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns=if(settings.prefs.contains("columns")) settings.prefs.getInt("columns",2).coerceIn(2,8)
                        else if(maxWidth>=androidx.compose.ui.unit.Dp(960f)) 6 else if(maxWidth>=androidx.compose.ui.unit.Dp(600f)) 4 else 2
                    Column(Modifier.fillMaxWidth()) {
                        items.chunked(columns).forEach { chunk ->
                            Row(Modifier.fillMaxWidth()) {
                                chunk.forEach { card ->
                                    androidx.compose.runtime.key(card.url,columns) {
                                        AndroidView(factory={ cardView(card,columns,open) },modifier=Modifier.weight(1f).padding(androidx.compose.ui.unit.Dp(4f)))
                                    }
                                }
                                repeat(columns-chunk.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        })
    }
    private fun cardView(card: Card,columns: Int,open: (Card)->Unit): View {
        val tile=panel().apply {
            isFocusable=true; isClickable=true; contentDescription=card.title + ". " + card.info
            background=GradientDrawable().apply { setColor(tileBase); cornerRadius=dp(12).toFloat() }
            setOnClickListener { open(card) }
            setOnFocusChangeListener { _,focus -> background=GradientDrawable().apply { setColor(if(focus) tileFocus else tileBase); cornerRadius=dp(12).toFloat(); if(focus) setStroke(dp(2),accent) } }
        }
        if(card.image.isNotBlank()) {
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            tile.addView(image,LinearLayout.LayoutParams(-1,dp(if(columns>=4) 180 else 210)))
            lifecycleScope.launch {
                val bitmap=withContext(Dispatchers.IO) { runCatching {
                    val u=if(card.image.startsWith("http")) card.image else if(card.image.startsWith("//")) "https:${card.image}" else Links.static+"/"+card.image.trimStart('/')
                    api.client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r -> if(r.isSuccessful) r.body?.byteStream()?.use { android.graphics.BitmapFactory.decodeStream(it) } else null }
                }.getOrNull() }
                if(image.isAttachedToWindow) image.setImageBitmap(bitmap)
            }
        }
        tile.addView(text(card.title,17f).apply { setTypeface(null,Typeface.BOLD); maxLines=3 })
        tile.addView(text(card.info,13f,muted).apply { maxLines=3 })
        return tile
    }
    private fun pagination(page: Int, pages: Int, go: (Int) -> Unit) {
        val controls=row(); if(page>1) controls.addView(button("← Назад") { go(page-1) })
        controls.addView(text("$page / $pages",14f,muted)); if(page<pages) controls.addView(button("Дальше →") { go(page+1) }); body.addView(controls)
    }
    private fun home(page: Int = 1, section: Int = 1, genre: String = "", remember: Boolean = true) {
        page("Главная", {
            shell("Смотреть сегодня")
            val names=listOf("Горячие новинки","Новинки","Сейчас смотрят","Популярные","В ожидании")
            body.addView(horizontal(row(*names.mapIndexed { i,n -> button(if(i==section) "• $n" else n) { home(1,i,genre,false) } }.toTypedArray())))
            body.addView(horizontal(row(*listOf("Все" to "","Фильмы" to "1","Сериалы" to "2","Мультфильмы" to "3","Аниме" to "82").map { (name,id) -> button(name) { home(1,section,id,false) } }.toTypedArray())))
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
            body.addView(input)
            body.addView(row(button("Найти") { search(input.text.toString(),1,false) },button("Голосом") {
                runCatching { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }.onFailure { toast("Распознавание речи недоступно") }
            }))
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
        select("Перевод",f.voices.map { it.title + if(it.premium) " · Premium" else "" }) { i ->
            val voice=f.voices[i]
            if(f.seasons.isNotEmpty() || f.card.url.contains("/series/")) load({ api.seasons(f,voice) }) { seasons ->
                if(seasons.isEmpty()) chooseVideo(f,voice,"","",download) else select("Сезон",seasons.map { it.title }) { s ->
                    val season=seasons[s];select("Серия",season.episodes.map { it.title }) { e -> chooseVideo(f,voice,season.id,season.episodes[e].id,download,seasons) }
                }
            } else chooseVideo(f,voice,"","",download)
        }
    }
    private fun chooseVideo(f: Film,v: Voice,s: String,e: String,download: Boolean,seasons: List<Season> = emptyList()) {
        load({ api.video(f,v,s,e) }) { video ->
            if(video.streams.isEmpty()) toast("Потоки недоступны") else chooseQuality(video,download) { i ->
                if(download) enqueue(f,video.streams[i],s,e,video.subtitles) else {
                    settings.history(f.card)
                    val payload=JSONObject().put("title",f.card.title).put("filmUrl",f.card.url).put("film",f.card.id).put("voice",v.id).put("season",s).put("episode",e).put("url",video.streams[i].url).put("quality",video.streams[i].quality)
                    payload.put("seasons",JSONArray(seasons.map { season -> JSONObject().put("id",season.id).put("title",season.title).put("episodes",JSONArray(season.episodes.map { JSONObject().put("id",it.id).put("title",it.title) })) }))
                    payload.put("voices",JSONArray(f.voices.map { JSONObject().put("id",it.id).put("title",it.title) }))
                    payload.put("streams",JSONArray(video.streams.map { JSONObject().put("quality",it.quality).put("url",it.url) }))
                    payload.put("subtitles",JSONArray(video.subtitles.map { JSONObject().put("title",it.title).put("url",it.url).put("language",it.language).put("default",it.default) }))
                    startActivity(Intent(this,PlayerActivity::class.java).putExtra("media",payload.toString()))
                }
            }
        }
    }
    private fun chooseQuality(video: Video,download: Boolean,done: (Int) -> Unit) {
        fun chosen(i: Int) {
            if(settings.prefs.getBoolean("saveQuality",true)) settings.prefs.edit().putString("quality",video.streams[i].quality).apply()
            done(i)
        }
        val available=video.streams.indices.filter { !download || video.streams[it].format in listOf(StreamFormat.MP4,StreamFormat.HLS) }
        if(available.isEmpty()) { toast("Видео для скачивания недоступно у этого источника");return }
        if(download || settings.prefs.getBoolean("askQuality",false))
            select("Качество",available.map { video.streams[it].label }) { chosen(available[it]) }
        else chosen(available.firstOrNull { video.streams[it].quality==settings.prefs.getString("quality","") } ?: available.last())
    }
    private fun select(title: String, options: List<String>, done: (Int) -> Unit) {
        if(options.isEmpty()) { toast("Нет доступных вариантов"); return }
        AlertDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()) { _, i -> done(i) }.setNegativeButton("Отмена",null).show()
    }
    private fun comments(f: Film,n: Int = 1,remember: Boolean = true) {
        page("Комментарии", { shell("Комментарии · ${f.card.title}");load({ api.comments(f.card.id,n) }) { (items,pages) ->
            items.forEach { c ->
                body.addView(text("${c.author} · ${c.date}",16f,accent))
                body.addView(androidx.compose.ui.platform.ComposeView(this).apply {
                    id=View.generateViewId()
                    setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                    setContent { CommentText(c,settings.endpoint,androidx.compose.ui.graphics.Color(this@MainActivity.foreground),androidx.compose.ui.graphics.Color(accent)) }
                })
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
            if(api.cookies.signedIn()) {
                load({ api.profile() }) { body.addView(text(it,20f)) }
                body.addView(button("Выйти") { api.cookies.clear(); current?.invoke() })
            } else {
                val name=EditText(this).apply { hint="Логин или email";isSingleLine=true;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
                val password=EditText(this).apply { hint="Пароль";isSingleLine=true;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                body.addView(name);body.addView(password)
                body.addView(button("Войти") {
                    val login=name.text.toString();val secret=password.text.toString()
                    if(login.isBlank() || secret.isBlank()) toast("Введите логин и пароль") else load({ api.login(login,secret) }) { password.text.clear(); current?.invoke() }
                })
            }
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
        if(stream.format==StreamFormat.HLS) {
            if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                downloadNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            runCatching { OfflineDownloads.add(this,f.card.title+if(e.isNotBlank()) " S${s}E${e}" else "",stream) }
                .onSuccess { toast("Загрузка HLS добавлена") }.onFailure { toast(it.message ?: "Ошибка загрузки") }
            return
        }
        if(stream.format!=StreamFormat.MP4) { toast("Для загрузки выберите MP4; HLS предназначен для просмотра");return }
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
            val hlsDownloads=OfflineDownloads.all(this)
            hlsDownloads.forEach { download ->
                val metadata=OfflineDownloads.media(download)
                body.addView(text(metadata.optString("title"),19f))
                note(when(download.state) {
                    androidx.media3.exoplayer.offline.Download.STATE_COMPLETED -> "Готово · HLS"
                    androidx.media3.exoplayer.offline.Download.STATE_FAILED -> "Ошибка загрузки HLS"
                    androidx.media3.exoplayer.offline.Download.STATE_STOPPED -> "Приостановлена"
                    androidx.media3.exoplayer.offline.Download.STATE_REMOVING -> "Удаление…"
                    else -> if(download.percentDownloaded>=0) "${download.percentDownloaded.toInt()}% · HLS" else "Загрузка HLS…"
                })
                if(download.state==androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)
                    body.addView(button("▶ Открыть") { startActivity(Intent(this,PlayerActivity::class.java).putExtra("media",metadata.toString())) })
                if(download.state==androidx.media3.exoplayer.offline.Download.STATE_FAILED)
                    body.addView(button("Повторить") { OfflineDownloads.retry(this,download);current?.invoke() })
                body.addView(button("Удалить") {
                    AlertDialog.Builder(this).setMessage("Удалить загрузку и файл?").setPositiveButton("Удалить") { _,_ -> OfflineDownloads.remove(this,download.request.id);current?.invoke() }.setNegativeButton("Отмена",null).show()
                })
            }
            val manager=getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val ids=settings.prefs.getStringSet("downloads",emptySet())!!.mapNotNull { it.toLongOrNull() }
            if(ids.isEmpty() && hlsDownloads.isEmpty()) note("Загрузок пока нет. Откройте фильм и выберите «Скачать».")
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
