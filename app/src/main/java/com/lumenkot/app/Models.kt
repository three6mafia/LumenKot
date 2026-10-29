package com.lumenkot.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

/** URLs are copied verbatim from Lumen's RezkaApi and SettingsScreen.config. */
object Links {
    val providers = listOf("https://rezka.ag", "https://rezka-ua.pub", "http://hdrezka.tv", "https://hdrezka.ag")
    const val official = "https://hdrzk.org"
    const val static = "https://statichdrezka.ac"
    const val github = "https://github.com/falcofemoralis/lumen"
    const val telegram = "https://t.me/lumen_app"
    const val updates = "https://www.dropbox.com/scl/fi/gz7j11yaje0naix568od9/update.json?rlkey=7tfi07dvys885zvkqwiihpwlf&st=id57d5yf&dl=1"
    const val demo = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    const val support = "mirror@hdrezka.org"
    val cdns = listOf("https://prx-cogent.ukrtelcdn.net", "https://prx2-cogent.ukrtelcdn.net", "https://prx3-cogent.ukrtelcdn.net", "https://prx4-cogent.ukrtelcdn.net", "https://prx5-cogent.ukrtelcdn.net", "https://prx6-cogent.ukrtelcdn.net", "https://prx-ams.ukrtelcdn.net", "https://stream.voidboost.cc", "https://stream.voidboost.top", "https://stream.voidboost.link", "https://stream.voidboost.club")
}
data class Card(val id: String, val title: String, val url: String, val image: String = "", val info: String = "", val type: String = "", val badge: String = "")
data class Catalog(val cards: List<Card>, val pages: Int = 1)
data class Choice(val id: String, val title: String)
data class Category(val title: String,val genres: List<Choice>,val years: List<Choice>)
data class Voice(val id: String, val title: String, val camrip: String = "0", val ads: String = "0", val director: String = "0", val premium: Boolean = false, val active: Boolean = false)
data class Episode(val id: String, val title: String)
data class Season(val id: String, val title: String, val episodes: List<Episode>)
data class Stream(val quality: String, val url: String)
data class Subtitle(val title: String, val url: String, val language: String = "", val default: Boolean = false)
data class Video(val streams: List<Stream>, val subtitles: List<Subtitle> = emptyList())
data class Film(val card: Card, val original: String, val description: String, val info: String, val voices: List<Voice>, val seasons: List<Season>, val direct: Video?, val links: List<Choice>, val related: List<Card>, val bookmarks: List<Choice>, val schedule: String, val restricted: String)
data class Comment(val id: String, val author: String, val date: String, val text: String, val likes: String, val indent: Int,val html: String = "")

object Parser {
    fun servicePageError(html: String): String? {
        val r=Jsoup.parse(html)
        val title=r.title().lowercase()
        return if(title.contains("проверяем, что вы не бот") || title.contains("just a moment") || r.selectFirst("#challenge-form, #cf-challenge-running")!=null)
            "Сайт требует проверки браузера. Получена страница проверки вместо данных фильма или аккаунта. Это не ошибка пароля."
        else null
    }
    fun cards(root: Element): List<Card> = root.select(".b-content__inline_item").mapNotNull { el ->
        val a = el.selectFirst(".b-content__inline_item-link a") ?: return@mapNotNull null
        Card(el.attr("data-id"), a.text(), a.attr("href"), el.selectFirst(".b-content__inline_item-cover img")?.attr("src").orEmpty(), el.selectFirst(".b-content__inline_item-link div")?.text().orEmpty(), when {a.attr("href").contains("/series/")->"Сериал";a.attr("href").contains("/cartoons/")->"Мультфильм";a.attr("href").contains("/animation/")->"Аниме";else->"Фильм"},el.selectFirst(".b-content__inline_item-cover .info")?.text().orEmpty())
    }
    fun pages(root: Element): Int = root.select(".b-navigation a").mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: 1
    fun catalog(html: String): Catalog = Jsoup.parse(html).let { Catalog(cards(it), pages(it)) }
    fun seasons(root: Element): List<Season> {
        val seasons = root.select(".b-simple_season__item").map { el ->
            val id = el.attr("data-tab_id")
            Season(id, el.text(), root.select("#simple-episodes-list-$id .b-simple_episode__item").map { Episode(it.attr("data-episode_id"), it.text()) })
        }
        if (seasons.isNotEmpty()) return seasons
        val eps = root.select(".b-simple_episode__item")
        return if (eps.isEmpty()) emptyList() else listOf(Season(eps.first()!!.attr("data-season_id").ifBlank { "1" }, "Серии", eps.map { Episode(it.attr("data-episode_id"), it.text()) }))
    }
    fun film(html: String, url: String): Film {
        val r = Jsoup.parse(html)
        val title = r.selectFirst(".b-post__title h1")?.text().orEmpty()
        require(title.isNotBlank()) { r.selectFirst(".error-title")?.text() ?: "Карточка фильма не найдена" }
        val voices = r.select(".b-translator__item").map { Voice(it.attr("data-translator_id"), it.attr("title").ifBlank { it.text() }, it.attr("data-camrip").ifBlank { "0" }, it.attr("data-ad").ifBlank { "0" }, it.attr("data-director").ifBlank { "0" }, it.hasClass("b-prem_translator"),it.hasClass("active")) }.toMutableList()
        var direct: Video? = null
        if (voices.isEmpty()) {
            val marker = if (r.selectFirst("meta[property=og:type]")?.attr("content")?.contains("video.movie") == true) "initCDNMoviesEvents" else "initCDNSeriesEvents"
            val script = html.substringAfter(marker, "")
            val start = script.indexOf("{\"id\"").let { if (it < 0) script.indexOf("{\"url\"") else it }
            if (marker == "initCDNMoviesEvents" && start >= 0) {
                val obj = jsonObjectAt(script, start)
                if (obj != null) direct = video(obj, "streams")
            }
            val id = script.substringBefore('{').split(',').getOrNull(1)?.trim().orEmpty()
            voices.add(Voice(if (marker.contains("Series")) id else "", "Оригинал"))
        }
        return Film(
            Card(r.selectFirst("#user-favorites-holder")?.attr("data-post_id").orEmpty().ifBlank { Regex("/(\\d+)-").find(url)?.groupValues?.get(1).orEmpty() }, title, url, r.selectFirst(".b-sidecover img")?.attr("src").orEmpty()),
            r.selectFirst(".b-post__origtitle")?.text().orEmpty(), r.selectFirst(".b-post__description_text")?.text().orEmpty(), r.select(".b-post__info tr").joinToString("\n") { it.text() }, voices, seasons(r), direct,
            r.select(".b-post__info a[href], .b-post__partcontent_item[data-url]").map { Choice(it.attr("href").ifBlank { it.attr("data-url") }, it.text()) }.filter { it.id.isNotBlank() },
            cards(r.selectFirst(".b-sidelist") ?: Jsoup.parse("")),
            r.select(".hd-label-row").map { Choice(it.selectFirst("input")?.attr("value").orEmpty(), it.selectFirst("label")?.text().orEmpty()) },
            r.select(".b-post__schedule_block").joinToString("\n\n") { block -> block.select("tr").joinToString("\n") { it.text() } },
            r.selectFirst(".b-player__restricted__block_message, .b-post__go_status")?.text().orEmpty()
        )
    }
    private fun jsonObjectAt(text: String, start: Int): JSONObject? {
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (quoted && c == '\\') { escaped = true; continue }
            if (c == '"') quoted = !quoted
            if (!quoted) { if (c == '{') depth++; if (c == '}' && --depth == 0) return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull() }
        }
        return null
    }
    fun video(json: JSONObject, key: String = "url"): Video {
        val streams = streams(json.optString(key))
        val languages = json.optJSONObject("subtitle_lns") ?: JSONObject()
        val subtitles = Regex("\\[([^]]+)]([^,]+)").findAll(json.optString("subtitle")).map { m ->
            val lang = languages.optString(m.groupValues[1]); Subtitle(m.groupValues[1], m.groupValues[2].trim(), lang, lang == json.optString("subtitle_def"))
        }.toList()
        return Video(streams, subtitles)
    }
    fun streams(raw: String): List<Stream> = Regex("\\[([^]]+)]([^,]+)").findAll(StreamDecoder.decrypt(raw)).map { Stream(Jsoup.parse(it.groupValues[1]).text(), it.groupValues[2].substringBefore(" or ").trim()) }.filter { it.url.startsWith("http") }.distinctBy { it.url }.toList()
}

/** Port of RezkaApi/decode.ts; no WebView or JavaScript engine. */
object StreamDecoder {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private fun encode(text: String): String {
        val b = text.toByteArray(); val out = StringBuilder()
        for (i in b.indices step 3) {
            val a = b[i].toInt() and 255; val c = if (i+1<b.size) b[i+1].toInt() and 255 else 0; val d = if(i+2<b.size) b[i+2].toInt() and 255 else 0
            out.append(alphabet[a shr 2]).append(alphabet[((a and 3) shl 4) or (c shr 4)]).append(if(i+1<b.size) alphabet[((c and 15) shl 2) or (d shr 6)] else '=').append(if(i+2<b.size) alphabet[d and 63] else '=')
        }
        return out.toString()
    }
    private val trash by lazy {
        val s = "@#!^$".map { it.toString() }
        val pairs = s.flatMap { a -> s.map { a+it } }
        Regex((pairs + pairs.flatMap { a -> s.map { a+it } }).joinToString("|") { Regex.escape(encode(it)) })
    }
    fun decrypt(raw: String): String {
        if (!raw.startsWith('#')) return raw
        val cache = HashMap<String, String>()
        fun clean(input: String): String {
            cache[input]?.let { return it }
            var shortest = input; var from = 0
            while (true) {
                val at = input.indexOf("//_//", from); if (at < 0) break
                val suffix = input.substring(at + 5)
                val end = suffix.indexOfFirst { it == '/' || it == '=' }.let { if (it < 0) suffix.length else it + 1 }
                val candidate = clean(input.substring(0, at) + suffix.substring(0, end).replace(trash, "") + suffix.substring(end))
                if (candidate.length < shortest.length) shortest = candidate
                from = at + 1
            }
            cache[input] = shortest; return shortest
        }
        val input = clean(raw.drop(2)); val output = java.io.ByteArrayOutputStream(); var bits = 0; var value = 0
        for (ch in input) {
            if(ch == '=') break
            if(ch.isWhitespace()) continue
            val n = alphabet.indexOf(ch); require(n >= 0) { "Некорректный поток видео" }
            value = (value shl 6) or n; bits += 6
            if(bits >= 8) { bits -= 8; output.write((value shr bits) and 255) }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }
}
