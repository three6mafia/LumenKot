package com.lumenkot.app

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class LumenTab(val label: String,val icon: String,val open: ()->Unit)

@Composable
fun LumenScaffold(title: String,home: Boolean,dark: Boolean,television: Boolean,selected: Int,tabs: List<LumenTab>,content: View,back: ()->Unit) {
    val bg=if(dark) Color.Black else Color.White
    val fg=if(dark) Color(0xffe3e3e3) else Color(0xff1c1c1c)
    val border=if(dark) Color(0xff4e4e4e) else Color(0xffb1b1b1)
    Box(Modifier.fillMaxSize().testTag("app-shell").background(bg).safeDrawingPadding().imePadding()) {
        val side=television
        @Composable fun tab(index: Int,modifier: Modifier) {
            val item=tabs[index]
            Box(modifier.selectable(selected==index,role=Role.Tab,onClick=item.open).semantics { contentDescription=item.label },contentAlignment=Alignment.Center) {
                LumenIcon(item.icon,if(index==selected) fg else fg.copy(alpha=.65f))
            }
        }
        Row(Modifier.fillMaxSize()) {
            if(side) Column(Modifier.width(64.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { tabs.indices.forEach { tab(it,Modifier.fillMaxWidth().height(56.dp)) } }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if(!home) Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clickable(onClick=back).semantics { contentDescription="Назад" },contentAlignment=Alignment.Center) { LumenIcon("back",fg) }
                    Text(title,color=fg,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                }
                androidx.compose.runtime.key(content) { AndroidView(factory={content},modifier=Modifier.weight(1f).fillMaxWidth().clipToBounds()) }
                if(!side) {
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(border))
                    Row(Modifier.fillMaxWidth().height(50.dp).background(bg)) { tabs.indices.forEach { tab(it,Modifier.weight(1f).fillMaxHeight()) } }
                }
            }
        }
    }
}

@Composable
fun LumenFilters(names: List<String>,selected: Int,dark: Boolean,choose: (Int)->Unit) {
    val fg=if(dark) Color(0xffe3e3e3) else Color(0xff1c1c1c)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=6.dp)) {
        names.forEachIndexed { index,name ->
            Column(Modifier.width(IntrinsicSize.Max).selectable(selected==index,role=Role.Tab,onClick={choose(index)}).padding(horizontal=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min=36.dp),contentAlignment=Alignment.Center) {
                    Text(name,maxLines=1,softWrap=false,color=if(index==selected) fg else fg.copy(alpha=.7f),fontSize=14.sp,fontWeight=if(index==selected) FontWeight.Bold else FontWeight.Normal)
                }
                Spacer(Modifier.fillMaxWidth().height(2.dp).background(if(index==selected) fg else Color.Transparent))
            }
        }
    }
}

@Composable
private fun LumenIcon(name: String,color: Color) {
    val svg=when(name) {
        "home" -> "M3 10 L12 3 L21 10 L21 21 L15 21 L15 14 L9 14 L9 21 L3 21 Z"
        "search" -> "M21 21 L16 16 M18 10 A8 8 0 1 1 2 10 A8 8 0 1 1 18 10"
        "bookmarks" -> "M3 5 L10 5 L12 7 L21 7 L21 20 L3 20 Z M8 12 C8 9 12 10 12 12 C12 10 16 9 16 12 C16 14 12 17 12 17 C12 17 8 14 8 12"
        "history" -> "M3 11 A9 9 0 1 1 5 18 M3 4 L3 11 L10 11 M12 7 L12 12 L16 14"
        "back" -> "M20 12 L4 12 M11 5 L4 12 L11 19"
        else -> "M16 7 A4 4 0 1 1 8 7 A4 4 0 1 1 16 7 M4 22 L4 19 C4 12 20 12 20 19 L20 22"
    }
    Canvas(Modifier.size(24.dp)) {
        val path=PathParser().parsePathString(svg).toPath()
        scale(size.width/24f,size.height/24f,pivot=androidx.compose.ui.geometry.Offset.Zero) { drawPath(path,color,style=Stroke(width=1.8f)) }
    }
}
