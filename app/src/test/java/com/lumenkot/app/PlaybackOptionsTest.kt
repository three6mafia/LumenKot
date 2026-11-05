package com.lumenkot.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackOptionsTest {
    @Test fun premiumPlaceholdersSurviveParsingButAreNeverChosenForPlayback() {
        val streams=Parser.streams("[720p]https://example.com/720.mp4,[1080p Ultra]<span class='premium'>Требуется Premium</span>")
        assertEquals(2,streams.size)
        assertTrue(streams.last().premium);assertFalse(streams.last().available)
        assertEquals("720p",PlaybackQuality.preferred(Video(streams),"1080p Ultra")!!.quality)
        assertNull(PlaybackQuality.preferred(Video(listOf(streams.last())),"max"))
    }
    @Test fun suppliedPremiumStreamRemainsPlayableWithoutGuessingAccountEntitlement() {
        val stream=Parser.streams("[<span class='premium'>1080p Ultra</span>]https://example.com/ultra.mp4").single()
        assertTrue(stream.premium);assertTrue(stream.available)
        assertEquals(stream,PlaybackQuality.preferred(Video(listOf(stream)),"1080p Ultra"))
    }
    @Test fun maxUsesResolutionAndExplicitSelectionOverridesSavedPreference() {
        val video=Video(listOf(Stream("4K","https://example.com/4k"),Stream("720p","https://example.com/720"),Stream("1080p","https://example.com/1080")))
        assertEquals("4K",PlaybackQuality.preferred(video,"max")!!.quality)
        assertEquals("720p",PlaybackQuality.preferred(video.copy(selectedQuality="720p"),"4K")!!.quality)
    }
    @Test fun volumeStepQuantizesMovementAndClampsBothEnds() {
        assertEquals(10,VolumeSteps.target(7,15,.2f,3))
        assertEquals(7,VolumeSteps.target(7,15,.08f,3))
        assertEquals(15,VolumeSteps.target(14,15,1f,3))
        assertEquals(0,VolumeSteps.target(1,15,-1f,3))
        assertEquals(0,VolumeSteps.target(0,0,1f,3))
    }
}
