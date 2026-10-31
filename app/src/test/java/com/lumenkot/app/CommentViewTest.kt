package com.lumenkot.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CommentViewTest {
    @Test fun composeSpoilersExpandIndependentlyAndKeepFormatting() {
        val html="""<b>Жирный</b><div class="text_spoiler">Первый секрет</div><div class="text_spoiler">Второй секрет</div>"""
        var clicked=-1
        val collapsed=buildCommentText(html,"","https://example.com",emptySet(),Color.Blue) { clicked=it }
        assertFalse(collapsed.text.contains("Первый секрет"))
        assertEquals(FontWeight.Bold,collapsed.spanStyles.first().item.fontWeight)
        val link=collapsed.getLinkAnnotations(0,collapsed.length).first().item as LinkAnnotation.Clickable
        link.linkInteractionListener!!.onClick(link)
        assertEquals(0,clicked)
        val expanded=buildCommentText(html,"","https://example.com",setOf(clicked),Color.Blue) {}
        assertTrue(expanded.text.contains("Первый секрет"))
        assertFalse(expanded.text.contains("Второй секрет"))
    }
    @Test fun composeListsQuotesAndSafeRelativeLinks() {
        val text=buildCommentText("""<blockquote>Цитата</blockquote><ul><li>Один</li><li>Два</li></ul><a href="/film">Фильм</a><a href="javascript:alert(1)">Опасная ссылка</a><script>script body</script>""","","https://example.com",emptySet(),Color.Blue) {}
        assertTrue(text.text.contains("• Один\n• Два"))
        assertFalse(text.text.contains("script body"))
        assertFalse(text.paragraphStyles.isEmpty())
        val link=text.getLinkAnnotations(0,text.length).single().item as LinkAnnotation.Url
        assertEquals("https://example.com/film",link.url)
    }
}
