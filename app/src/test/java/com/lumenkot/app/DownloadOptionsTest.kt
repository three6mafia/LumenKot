package com.lumenkot.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadOptionsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun selectsFormatQualitySeveralEpisodesSubtitlesAndRememberSwitch() {
        val episodes=listOf(DownloadEpisode("1","1","Серия 1"),DownloadEpisode("1","2","Серия 2"))
        var selection: DownloadChoice?=null
        compose.setContent {
            DownloadOptions(Video(listOf(Stream("1080p","https://example.com/a.m3u8"),Stream("720p","https://example.com/b.mp4"))),episodes,false,null,false,true,{}, { selection=it })
        }
        compose.onNodeWithText("MP4").performClick()
        compose.onNodeWithText("720p").assertExists()
        compose.onNodeWithText("Выбрать все").performScrollTo().performClick()
        compose.onNodeWithText("С субтитрами").performScrollTo().performClick()
        compose.onNodeWithText("Запомнить место сохранения").performScrollTo().performClick()
        compose.onNodeWithText("Скачать",substring=false).performClick()
        compose.runOnIdle {
            assertEquals(StreamFormat.MP4,selection!!.format)
            assertEquals("720p",selection!!.quality)
            assertEquals(episodes,selection!!.episodes)
            assertFalse(selection!!.subtitles)
            assertTrue(selection!!.rememberFolder)
            assertFalse(selection!!.useSavedFolder)
        }
    }
    @Test fun disablingRememberFolderForcesFolderSelectionAgain() {
        var choice: DownloadChoice?=null
        compose.setContent {
            DownloadOptions(Video(listOf(Stream("720p","https://example.com/a.mp4"))),listOf(DownloadEpisode("","","Фильм")),true,"Movies",false,false,{}, { choice=it })
        }
        compose.onNodeWithText("Запомнить место сохранения").performScrollTo().performClick()
        compose.onNodeWithText("Папка будет выбрана в Android").assertExists()
        compose.onNodeWithText("Скачать",substring=false).performClick()
        compose.runOnIdle {
            assertFalse(choice!!.rememberFolder)
            assertFalse(choice!!.useSavedFolder)
            assertFalse(choice!!.subtitles)
        }
    }

}
