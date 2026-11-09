package com.lumenkot.app

import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ParserTest {
    @Test fun browserChallengeIsNotMistakenForCatalogOrWrongPassword() {
        assertTrue(Parser.servicePageError("<!doctype html><html><title>Проверяем, что вы не бот!</title></html>")!!.contains("проверки браузера"))
        assertNull(Parser.servicePageError("<html><title>Теория большого взрыва</title></html>"))
    }
    @Test fun parsesCatalogAndPagination() {
        val html="""<div class="b-content__inline_item" data-id="42"><div class="b-content__inline_item-cover"><img src="https://example.com/poster.jpg"><span class="info">HD</span></div><div class="b-content__inline_item-link"><a href="/films/42-film.html">Фильм</a><div>2026, драма</div></div></div><div class="b-navigation"><a>1</a><a>12</a><a>→</a></div>"""
        val result=Parser.catalog(html)
        assertEquals(12,result.pages);assertEquals("42",result.cards.single().id);assertEquals("Фильм",result.cards.single().title);assertEquals("/films/42-film.html",result.cards.single().url)
    }
    @Test fun parsesEpisodesWithAndWithoutSeasonTabs() {
        val root=Jsoup.parse("""<li class="b-simple_season__item" data-tab_id="2">Сезон 2</li><div id="simple-episodes-list-2"><li class="b-simple_episode__item" data-episode_id="3">Серия 3</li></div>""")
        assertEquals("3",Parser.seasons(root).single().episodes.single().id)
        val standalone=Parser.seasons(Jsoup.parse("""<li class="b-simple_episode__item" data-season_id="5" data-episode_id="6">Серия 6</li>"""))
        assertEquals("5",standalone.single().id)
    }
    @Test fun decodesObfuscatedStreamsAndKeepsOriginalLinks() {
        val plain="[720p]https://stream.voidboost.cc/video.mp4 or https://stream.voidboost.top/fallback.mp4,[1080p]https://stream.voidboost.cc/full.m3u8"
        val b64=Base64.getEncoder().encodeToString(plain.toByteArray())
        val trash=Base64.getEncoder().encodeToString("@#!".toByteArray())
        val encrypted="#h"+b64.take(16)+"//_//"+trash+b64.drop(16)
        assertEquals(plain,StreamDecoder.decrypt(encrypted))
        val streams=Parser.streams(encrypted)
        assertEquals(2,streams.size);assertEquals("https://stream.voidboost.cc/video.mp4",streams[0].url)
        assertEquals("1080p",streams[1].quality)
    }
    @Test fun parsesSubtitlesAndDefaultLanguage() {
        val video=Parser.video(JSONObject("""{"url":"[720p]https://example.com/video.mp4","subtitle":"[Русский]https://example.com/ru.vtt","subtitle_lns":{"Русский":"ru"},"subtitle_def":"ru"}"""))
        assertTrue(video.subtitles.single().default);assertEquals("ru",video.subtitles.single().language)
    }
    @Test fun parsesFilmAndInlineMovieVideo() {
        val film=Parser.film("""<meta property="og:type" content="video.movie"><div class="b-post__title"><h1>Тест</h1></div><div id="user-favorites-holder" data-post_id="42"></div><script>initCDNMoviesEvents(42,0, {"id":42,"streams":"[720p]https://example.com/movie.mp4"});</script>""","/films/42-test.html")
        assertEquals("42",film.card.id);assertEquals("https://example.com/movie.mp4",film.direct!!.streams.single().url)
    }
    @Test fun parsesActiveWebsiteEpisodeAndLazyPoster() {
        val html="""<div class="b-post__title"><h1>Сериал</h1></div><div class="b-sidecover"><img src="placeholder.gif" data-src="//cdn.example/poster.jpg"></div><li class="b-simple_season__item" data-tab_id="3">Сезон 3</li><div id="simple-episodes-list-3"><li class="b-simple_episode__item active" data-episode_id="5">Серия 5</li></div>"""
        val film=Parser.film(html,"/series/42-film.html")
        assertEquals("//cdn.example/poster.jpg",film.card.image)
        assertEquals("3",film.seasons.single().id);assertEquals("5",film.seasons.single().episodes.single().id)
        assertTrue(film.seasons.single().episodes.single().active)
    }
    @Test fun preservesOriginalServiceDefaults() {
        assertEquals("https://hdrzk.org",Links.official)
        assertEquals("https://rezka.ag",Links.providers.first())
        assertEquals(11,Links.cdns.size)
        assertEquals("https://github.com/falcofemoralis/lumen",Links.github)
    }
}
