package com.lumenkot.app

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.view.View
import android.widget.TextView
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Native structured comment text. No WebView, scripts, or HTML.fromHtml. */
class CommentView(context: Context) : TextView(context) {
    private var root: Element = Jsoup.parseBodyFragment("").body()
    private val expanded = mutableSetOf<Element>()

    init {
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        setLineSpacing(0f,1.15f)
    }

    fun setComment(html: String, fallback: String, baseUrl: String) {
        root = Jsoup.parseBodyFragment(html,baseUrl).body()
        expanded.clear()
        if(html.isBlank()) text = fallback else render()
    }

    private fun render() {
        val out = SpannableStringBuilder()
        fun span(style: Any, start: Int) {
            if(out.length > start) out.setSpan(style,start,out.length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun newline() { if(out.isNotEmpty() && out.last()!='\n') out.append('\n') }
        fun append(node: Node) {
            if(node is TextNode) { out.append(node.text()); return }
            if(node !is Element) return
            val tag = node.normalName()
            if(tag in setOf("script","style","iframe","object","title") || node.hasClass("title_spoiler")) return
            if(node.hasClass("text_spoiler")) {
                val start = out.length
                val open = node in expanded
                out.append(if(open) "[Скрыть спойлер]" else "[Показать спойлер]")
                out.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        if(!expanded.add(node)) expanded.remove(node)
                        render()
                    }
                },start,out.length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(open) { out.append(' '); node.childNodes().forEach { append(it) } }
                return
            }
            if(tag=="br") { out.append('\n'); return }
            val block = tag in setOf("p","div","blockquote","ul","ol","li","pre")
            if(block) newline()
            val start = out.length
            if(tag=="li") {
                val ordered = node.parent()?.normalName()=="ol"
                val index = node.parent()?.children()?.indexOf(node) ?: 0
                out.append(if(ordered) "${index+1}. " else "• ")
            }
            if(tag=="img") out.append(node.attr("alt").ifBlank { "[Изображение]" })
            else node.childNodes().forEach { append(it) }
            when(tag) {
                "b","strong" -> span(StyleSpan(Typeface.BOLD),start)
                "i","em" -> span(StyleSpan(Typeface.ITALIC),start)
                "u" -> span(UnderlineSpan(),start)
                "s","strike","del" -> span(StrikethroughSpan(),start)
                "code","pre" -> span(TypefaceSpan("monospace"),start)
                "blockquote" -> span(QuoteSpan(currentTextColor),start)
                "a" -> {
                    val url = node.absUrl("href")
                    if(android.net.Uri.parse(url).scheme in setOf("http","https","mailto")) span(URLSpan(url),start)
                }
            }
            if(block) newline()
        }
        root.childNodes().forEach { append(it) }
        text = out
    }
}
