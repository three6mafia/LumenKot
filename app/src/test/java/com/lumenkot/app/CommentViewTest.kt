package com.lumenkot.app

import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommentViewTest {
    @Test fun spoilersExpandIndependentlyAndKeepFormatting() {
        val view=CommentView(ApplicationProvider.getApplicationContext())
        view.setComment("""<b>Жирный</b><div class="text_spoiler">Первый секрет</div><div class="text_spoiler">Второй секрет</div>""","","https://example.com")
        fun content()=view.text as Spanned
        assertFalse(content().contains("Первый секрет"))
        assertFalse(content().contains("Второй секрет"))
        assertEquals(1,content().getSpans(0,content().length,StyleSpan::class.java).size)
        content().getSpans(0,content().length,ClickableSpan::class.java)[0].onClick(view)
        assertTrue(content().contains("Первый секрет"))
        assertFalse(content().contains("Второй секрет"))
        content().getSpans(0,content().length,ClickableSpan::class.java)[0].onClick(view)
        assertFalse(content().contains("Первый секрет"))
    }
    @Test fun listsQuotesAndSafeRelativeLinks() {
        val view=CommentView(ApplicationProvider.getApplicationContext())
        view.setComment("""<blockquote>Цитата</blockquote><ul><li>Один</li><li>Два</li></ul><a href="/film">Фильм</a><a href="javascript:alert(1)">Опасная ссылка</a><script>script body</script>""","","https://example.com")
        val text=view.text as Spanned
        assertTrue(text.contains("• Один\n• Два"))
        assertFalse(text.contains("script body"))
        assertEquals("https://example.com/film",text.getSpans(0,text.length,URLSpan::class.java).single().url)
    }
}
