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
        settings.provider="https://rezka.ag";settings.official=false
        val api=RezkaApi(settings)
        assertEquals("https://rezka.ag",settings.endpoint)
        val cards=api.catalog().cards
        assertTrue("rezka.ag must return real catalog entries",cards.isNotEmpty())
        val found=api.search(cards.first().title,1).cards
        assertTrue("real search must return entries",found.isNotEmpty())
        val film=api.film(cards.first().url)
        assertTrue(film.card.title.isNotBlank())
        val voice=film.voices.first { !it.premium }
        val seasons=if(film.seasons.isNotEmpty()) api.seasons(film,voice) else emptyList()
        val season=seasons.firstOrNull();val episode=season?.episodes?.firstOrNull()
        val video=api.video(film,voice,season?.id.orEmpty(),episode?.id.orEmpty())
        assertTrue("real provider must return video URLs",video.streams.isNotEmpty())
    }
}
