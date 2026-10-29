package com.lumenkot.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicit live-service check: no fixtures, fallback mirrors or swallowed failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveRezkaTest {
    @Test fun rezkaCatalogSearchFilmAndStreams() {
        val settings=Settings(ApplicationProvider.getApplicationContext())
        settings.provider=System.getProperty("lumen.liveProvider","https://rezka.ag");settings.official=false
        System.getProperty("lumen.liveUserAgent")?.let {settings.userAgent=it}
        val api=RezkaApi(settings)
        assertEquals(System.getProperty("lumen.liveProvider","https://rezka.ag"),settings.endpoint)
        val requested=System.getProperty("lumen.liveFilm")
        val film=if(requested!=null) api.film(requested) else {
            val cards=api.catalog().cards
            assertTrue("provider must return real catalog entries",cards.isNotEmpty())
            assertTrue("real search must return entries",api.search(cards.first().title,1).cards.isNotEmpty())
            api.film(cards.first().url)
        }
        assertTrue(film.card.title.isNotBlank())
        val voice=film.voices.firstOrNull { !it.premium && it.active } ?: film.voices.first { !it.premium }
        val seasons=if(film.seasons.isNotEmpty()) api.seasons(film,voice) else emptyList()
        val season=seasons.firstOrNull();val episode=season?.episodes?.firstOrNull()
        println("Parsed film: ${film.card.title}; voices=${film.voices.size}; seasons=${seasons.size}; episodes=${season?.episodes?.size ?: 0}")
        val video=api.video(film,voice,season?.id.orEmpty(),episode?.id.orEmpty())
        assertTrue("real provider must return video URLs",video.streams.isNotEmpty())
        println("Video qualities: "+video.streams.joinToString {it.quality})
    }
}
