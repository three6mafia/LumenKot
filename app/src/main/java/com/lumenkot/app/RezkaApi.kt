package com.lumenkot.app

import android.content.Context
import android.os.Build
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class Settings(context: Context) {
    val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    init {
        // Correct the old default on upgrade without replacing custom mirrors or cookies.
        if (prefs.getInt("networkDefaultsVersion",0)<2) {
            if (prefs.getString("provider",Links.providers.first())==Links.providers.first()) prefs.edit().putBoolean("official",false).apply()
            prefs.edit().putInt("networkDefaultsVersion",2).apply()
        }
    }
    var provider: String
        get() = prefs.getString("provider", Links.providers.first())!!
        set(value) { require(value.startsWith("https://") || value.startsWith("http://")); prefs.edit().putString("provider", value.trimEnd('/')).apply() }
    var official: Boolean
        get() = prefs.getBoolean("official", false)
        set(value) { prefs.edit().putBoolean("official", value).apply() }
    var shareLink: String
        get() = prefs.getString("shareLink",Links.providers.first())!!
        set(value) { prefs.edit().putString("shareLink",value).apply() }
    var cdn: String
        get() = prefs.getString("cdn", "")!!
        set(value) { prefs.edit().putString("cdn", value.trimEnd('/')).apply() }
    var userAgent: String
        get() = prefs.getString("userAgent", "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")!!
        set(value) { prefs.edit().putString("userAgent", value).apply() }
    val endpoint get() = if (official) Links.official else provider
    fun history(card: Card) {
        val list = history().filter { it.url != card.url }.toMutableList(); list.add(0, card)
        prefs.edit().putString("history", JSONArray(list.take(100).map { JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("image", it.image) }).toString()).apply()
    }
    fun history(): List<Card> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]")); (0 until a.length()).map { val j = a.getJSONObject(it); Card(j.optString("id"), j.optString("title"), j.optString("url"), j.optString("image")) }
    }.getOrDefault(emptyList())
    fun clearHistory() { prefs.edit().remove("history").apply() }
}

class PersistentCookies(private val settings: Settings): CookieJar {
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val retained = stored().filter { (_, old) -> cookies.none { it.name == old.name && it.domain == old.domain && it.path == old.path } }
        val combined = retained + cookies.map { url.toString() to it }
        settings.prefs.edit().putString("cookies", JSONArray(combined.filter { it.second.expiresAt > System.currentTimeMillis() }.map { (origin,cookie) -> JSONObject().put("origin",origin).put("cookie",cookie.toString()) }).toString()).apply()
    }
    private fun stored(): List<Pair<String,Cookie>> = runCatching {
        val a = JSONArray(settings.prefs.getString("cookies", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val item = a.getJSONObject(i); val origin = item.getString("origin")
            Cookie.parse(origin.toHttpUrl(), item.getString("cookie"))?.let { origin to it }
        }
    }.getOrDefault(emptyList())
    @Synchronized override fun loadForRequest(url: HttpUrl) = stored().map { it.second }.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    fun clear() { settings.prefs.edit().remove("cookies").apply() }
    fun signedIn() = loadForRequest(settings.endpoint.toHttpUrl()).any { it.name == "dle_password" && it.value.isNotEmpty() }
}

class RezkaApi(val settings: Settings) {
    val cookies = PersistentCookies(settings)
    val client = OkHttpClient.Builder().cookieJar(cookies).connectTimeout(20, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build()
    fun headers(): Map<String, String> = buildMap {
        put("User-Agent", settings.userAgent)
        if (settings.official) { put("X-Hdrezka-Android-App", "1"); put("X-Hdrezka-Android-App-Version", "2.2.1") }
    }
    fun absolute(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        else -> settings.endpoint + "/" + path.trimStart('/')
    }
    fun request(path: String, fields: Map<String, String> = emptyMap(), post: Boolean = false): String {
        val u = absolute(if(post) path.trimEnd('/')+"/" else path).toHttpUrl().newBuilder()
        if (post) u.addQueryParameter("t", System.currentTimeMillis().toString()) else fields.forEach { (k,v) -> u.addQueryParameter(k,v) }
        val req = Request.Builder().url(u.build())
        headers().forEach { (k,v) -> req.header(k,v) }
        if (post) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            fields.forEach { (k,v) -> body.addFormDataPart(k,v) }
            req.post(body.build())
        }
        return client.newCall(req.build()).execute().use { res ->
            check(!post || res.request.method=="POST") { "Сервис перенаправил запрос входа или видео на обычную страницу. Проверьте выбранное зеркало." }
            check(res.isSuccessful) { "Сервис ответил HTTP ${res.code}" }
            val body = res.body?.string().orEmpty()
            if(!body.trimStart().startsWith('{')) Parser.servicePageError(body)?.let {error(it)}
            val error = if (!body.trimStart().startsWith('{')) Jsoup.parse(body).selectFirst(".error-code") else null
            check(error == null) { Jsoup.parse(body).selectFirst(".error-title")?.text() ?: "Сервис временно недоступен" }
            body
        }
    }
    fun json(path: String, fields: Map<String, String>): JSONObject {
        val response=request(path,fields,true)
        check(response.trimStart().startsWith("{")) { "Сервис вернул страницу вместо видеоданных. Проверьте зеркало и вход в аккаунт." }
        return JSONObject(response)
    }
    fun checked(path: String, fields: Map<String, String>): JSONObject = json(path, fields).also { check(it.optBoolean("success")) { Jsoup.parse(it.optString("message", "Не удалось выполнить запрос")).text().ifBlank { "Сервис отклонил запрос. Проверьте доступ к выбранному видео или аккаунту на сайте." } } }
    fun catalog(path: String = "/new", page: Int = 1, filter: String = "", genre: String = ""): Catalog = Parser.catalog(request(path.trimEnd('/') + "/page/$page/", buildMap { if(filter.isNotEmpty()) put("filter",filter); if(genre.isNotEmpty()) put("genre",genre) }))
    fun search(query: String, page: Int): Catalog = Parser.catalog(request("/search/", mapOf("do" to "search", "subaction" to "search", "q" to query, "page" to "$page")))
    fun hot(genre: String = "0") = Parser.catalog(request("/engine/ajax/get_newest_slider_content.php", mapOf("id" to genre), true))
    fun film(url: String) = Parser.film(request(url), url)
    fun seasons(film: Film, voice: Voice): List<Season> {
        val j = json("/ajax/get_cdn_series", mapOf("id" to film.card.id,"translator_id" to voice.id,"action" to "get_episodes"))
        check(!j.has("success") || j.optBoolean("success")) { Jsoup.parse(j.optString("message","Не удалось получить серии")).text() }
        return Parser.seasons(Jsoup.parse(j.optString("seasons") + j.optString("episodes")))
    }
    fun video(film: Film, voice: Voice, season: String = "", episode: String = ""): Video {
        if (voice.id.isBlank() && film.direct != null) return modify(film.direct)
        val data = mutableMapOf("id" to film.card.id, "translator_id" to voice.id, "action" to if(episode.isNotEmpty()) "get_stream" else "get_movie")
        if (episode.isNotEmpty()) { data["season"] = season; data["episode"] = episode }
        else { data["is_camrip"] = voice.camrip; data["is_ads"] = voice.ads; data["is_director"] = voice.director }
        return modify(Parser.video(checked("/ajax/get_cdn_series", data)))
    }
    private fun modify(v: Video): Video = if(settings.cdn.isEmpty()) v else v.copy(streams=v.streams.map { s -> val host=settings.cdn.toHttpUrl(); s.copy(url=s.url.toHttpUrl().newBuilder().scheme(host.scheme).host(host.host).port(host.port).build().toString()) })
    fun saveWatch(film: Film, voice: Voice, season: String, episode: String, seconds: Long) {
        json("/ajax/send_save", mapOf("post_id" to film.card.id, "translator_id" to voice.id,"season" to season.ifBlank { "0" }, "episode" to episode.ifBlank { "0" }, "current_time" to seconds.coerceAtLeast(1).toString()))
    }
    fun login(name: String, password: String) { checked("/ajax/login/",mapOf("login_name" to name,"login_password" to password,"login_not_save" to "0")) }
    fun profile(): String {
        val id = cookies.loadForRequest(settings.endpoint.toHttpUrl()).firstOrNull { it.name == "dle_user_id" }?.value ?: error("Войдите в аккаунт")
        val r = Jsoup.parse(request("/user/$id"))
        return listOf(r.title(), r.selectFirst("#email")?.attr("value").orEmpty(), r.selectFirst(".b-tophead-premuser")?.text().orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    }
    fun bookmarks(): List<Choice> = Jsoup.parse(request("/favorites")).select(".b-favorites_content__cats_list_item").map { Choice(it.attr("data-cat_id"), it.selectFirst(".name")?.text().orEmpty()) }
    fun bookmark(film: String, category: String) { checked("/ajax/favorites",mapOf("post_id" to film,"cat_id" to category,"action" to "add_post")) }
    fun recent(): List<Card> = Jsoup.parse(request("/continue")).select(".b-videosaves__list_item").mapNotNull { e ->
        val a = e.selectFirst(".title a") ?: return@mapNotNull null
        Card(e.selectFirst(".delete")?.attr("data-id").orEmpty(), a.text(), a.attr("href"), a.attr("data-cover_url"), e.selectFirst(".info")?.text().orEmpty())
    }
    fun deleteRecent(id: String) { checked("/engine/ajax/cdn_saves_remove.php", mapOf("id" to id)) }
    fun hideRecent(id: String) { checked("/engine/ajax/cdn_saves_view.php", mapOf("id" to id)) }
    fun collections(page: Int): Catalog {
        val r = Jsoup.parse(request("/collections/page/$page/"))
        return Catalog(r.select(".b-content__collections_item").map { Card(it.attr("data-url"), it.selectFirst(".title")?.text().orEmpty(), it.attr("data-url"), it.selectFirst(".cover")?.attr("src").orEmpty(), it.selectFirst(".num")?.text().orEmpty()) }, Parser.pages(r))
    }
    fun categories(): List<Category> = Jsoup.parse(request("/collections")).select("li.b-topnav__item:not(.single)").map { el ->
        Category(el.selectFirst(".b-topnav__item-link")?.text().orEmpty(),
            el.select("select.select-category option").map { Choice(it.attr("value"),it.text()) }.filter { it.id.isNotBlank() }.distinctBy { it.id },
            listOf(Choice("-1","За последнее время")) + el.select("select.select-year option").map { Choice(it.attr("value"),it.text()) }.filter { it.id.isNotBlank() }.distinctBy { it.id })
    }
    fun comments(id: String, page: Int): Pair<List<Comment>, Int> {
        val j = JSONObject(request("/ajax/get_comments",mapOf("t" to "${System.currentTimeMillis()}","news_id" to id,"cstart" to "$page","type" to "0","comment_id" to "0","skin" to "hdrezka")))
        val r = Jsoup.parse(j.optString("comments"))
        return r.select(".comments-tree-item").map { Comment(it.attr("data-id"),it.selectFirst(".name")?.text().orEmpty(),it.selectFirst(".date")?.text().orEmpty(),it.selectFirst(".text div")?.text().orEmpty(),it.selectFirst(".b-comment__like_it")?.attr("data-likes_num").orEmpty(),it.attr("data-indent").toIntOrNull() ?: 0,it.selectFirst(".text div")?.html().orEmpty()) } to Parser.pages(Jsoup.parse(j.optString("navigation")))
    }
    fun saveScheduleWatch(id: String) { checked("/engine/ajax/schedule_watched.php",mapOf("id" to id)) }
    fun suggestions(query: String): List<String> = Jsoup.parse(request("/engine/ajax/search.php",mapOf("q" to query),true)).select("li .enty").map { it.text() }.distinct()
    fun like(id: String) = checked("/engine/ajax/comments_like.php",mapOf("id" to id)).optString("count")
    fun rating(id: String, value: Int) = checked("/engine/ajax/rating.php",mapOf("news_id" to id,"go_rate" to "$value","skin" to "hdrezka")).optString("num")
    fun trailer(id: String): String {
        val code = checked("/engine/ajax/gettrailervideo.php",mapOf("id" to id)).optString("code")
        return Jsoup.parse(code).selectFirst("iframe")?.attr("src")?.let { if(it.startsWith("//")) "https:$it" else it }.orEmpty()
    }
    fun notifications(): List<Card> = Jsoup.parse(request("/")).select(".b-seriesupdate__block .tracked").mapNotNull { e ->
        val a = e.selectFirst(".b-seriesupdate__block_list_link") ?: return@mapNotNull null
        Card(a.attr("href"), a.text(), a.attr("href"), info=listOf(e.selectFirst(".season")?.text(), e.selectFirst(".cell-2")?.text()).filterNotNull().joinToString(" · "))
    }
    fun actor(url: String): Pair<String,List<Card>> {
        val r = Jsoup.parse(request(url))
        return listOf(r.selectFirst(".b-post__title")?.text(),r.selectFirst(".b-post__info")?.text()).filterNotNull().joinToString("\n") to Parser.cards(r)
    }
}
