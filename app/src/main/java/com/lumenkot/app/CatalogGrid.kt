package com.lumenkot.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CatalogGrid(items: List<Card>,manualColumns: Int?,foreground: Color,secondary: Color,placeholder: Color,load: suspend (Card)->Bitmap?,open: (Card)->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns=manualColumns?.coerceIn(2,8) ?: (maxWidth.value/160f).toInt().coerceIn(2,6)
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    row.forEach { card ->
                        key(card.url) {
                            val bitmap by produceState<Bitmap?>(null,card.image) { if(card.image.isNotBlank()) value=load(card) }
                            Column(Modifier.weight(1f).clickable { open(card) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(166f/250f).clip(RoundedCornerShape(12.dp)).background(placeholder)) {
                                    bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(card.title,color=foreground,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.Bold,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(card.info,color=secondary,fontSize=12.sp,lineHeight=16.sp,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                    repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
