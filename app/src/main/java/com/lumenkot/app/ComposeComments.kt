package com.lumenkot.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** All comment text, including independent accessible spoiler links, is drawn by Compose. */
@Composable
fun CommentText(comment: Comment,baseUrl: String,color: Color,linkColor: Color) {
    var expanded by rememberSaveable(comment.id,comment.html) { mutableStateOf(listOf<Int>()) }
    val content=remember(comment.html,comment.text,baseUrl,expanded,linkColor) {
        buildCommentText(comment.html,comment.text,baseUrl,expanded.toSet(),linkColor) { id ->
            expanded=if(id in expanded) expanded-id else expanded+id
        }
    }
    Text(content,color=color,fontSize=16.sp,lineHeight=23.sp,
        modifier=Modifier.fillMaxWidth().padding(start=(comment.indent.coerceIn(0,4)*12).dp,top=4.dp,bottom=8.dp))
}

fun buildCommentText(html: String,fallback: String,baseUrl: String,expanded: Set<Int>,linkColor: Color,onSpoiler: (Int)->Unit): AnnotatedString {
    if(html.isBlank()) return AnnotatedString(fallback)
    val root=Jsoup.parseBodyFragment(html,baseUrl).body()
    val spoilerIds=root.select(".text_spoiler").withIndex().associate { it.value to it.index }
    return buildAnnotatedString {
        fun newline() { if(length>0 && toAnnotatedString().text.last()!='\n') append('\n') }
        fun node(n: Node) {
            if(n is TextNode) { append(n.text());return }
            if(n !is Element) return
            val tag=n.normalName()
            if(tag in setOf("script","style","iframe","object") || n.hasClass("title_spoiler")) return
            if(n.hasClass("text_spoiler")) {
                val id=spoilerIds.getValue(n)
                withLink(LinkAnnotation.Clickable("spoiler:$id",TextLinkStyles(SpanStyle(color=linkColor,textDecoration=TextDecoration.Underline))) { onSpoiler(id) }) {
                    append(if(id in expanded) "[Скрыть спойлер]" else "[Показать спойлер]")
                }
                if(id in expanded) { append(' ');n.childNodes().forEach { node(it) } }
                return
            }
            if(tag=="br") { append('\n');return }
            val block=tag in setOf("p","div","blockquote","ul","ol","li","pre")
            if(block) newline()
            val style=when(tag) {
                "b","strong" -> SpanStyle(fontWeight=FontWeight.Bold)
                "i","em" -> SpanStyle(fontStyle=FontStyle.Italic)
                "u" -> SpanStyle(textDecoration=TextDecoration.Underline)
                "s","strike","del" -> SpanStyle(textDecoration=TextDecoration.LineThrough)
                "code","pre" -> SpanStyle(fontFamily=FontFamily.Monospace)
                else -> SpanStyle()
            }
            val token=pushStyle(style)
            if(tag=="li") append(if(n.parent()?.normalName()=="ol") "${(n.parent()?.children()?.indexOf(n) ?: 0)+1}. " else "• ")
            val quote=if(tag=="blockquote") pushStyle(ParagraphStyle(textIndent=TextIndent(16.sp,16.sp))) else null
            val url=n.absUrl("href")
            val link=tag=="a" && android.net.Uri.parse(url).scheme in setOf("http","https","mailto")
            fun children() {
                if(tag=="img") append(n.attr("alt").ifBlank { "[Изображение]" })
                else n.childNodes().forEach { node(it) }
            }
            if(link) withLink(LinkAnnotation.Url(url,TextLinkStyles(SpanStyle(color=linkColor,textDecoration=TextDecoration.Underline)))) { children() }
            else children()
            if(block) newline()
            if(quote!=null) pop(quote)
            pop(token)
        }
        root.childNodes().forEach { node(it) }
    }
}
