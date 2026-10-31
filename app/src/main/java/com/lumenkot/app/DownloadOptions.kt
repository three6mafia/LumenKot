package com.lumenkot.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class DownloadEpisode(val season: String,val episode: String,val title: String)
data class DownloadChoice(val format: StreamFormat,val quality: String,val episodes: List<DownloadEpisode>,val subtitles: Boolean,val rememberFolder: Boolean,val useSavedFolder: Boolean)

@Composable
fun DownloadOptions(video: Video,episodes: List<DownloadEpisode>,rememberFolder: Boolean,savedFolder: String?,dark: Boolean,initialSubtitles: Boolean=true,dismiss: ()->Unit,confirm: (DownloadChoice)->Unit) {
    val formats=video.streams.map { it.format }.filter { it!=StreamFormat.OTHER }.distinct()
    var format by remember { mutableStateOf(formats.first()) }
    val qualities=video.streams.filter { it.format==format }.map { it.quality }.distinct()
    var quality by remember(format) { mutableStateOf(qualities.last()) }
    var selected by remember { mutableStateOf(episodes.take(1).toSet()) }
    var subtitles by remember { mutableStateOf(initialSubtitles) }
    var remember by remember { mutableStateOf(rememberFolder) }
    var saved by remember { mutableStateOf(rememberFolder && savedFolder!=null) }
    MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xffb5f36b),surface=Color(0xff1d251d)) else lightColorScheme(primary=Color(0xff326914),surface=Color(0xfffafcf7))) {
        AlertDialog(onDismissRequest=dismiss,title={ Text("Скачать видео") },text={
            Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
                Text("Формат")
                Row { formats.forEach { f -> Row { RadioButton(selected=format==f,onClick={format=f});TextButton(onClick={format=f}) { Text(f.name) } } } }
                Text("Качество")
                qualities.forEach { q -> Row { RadioButton(selected=quality==q,onClick={quality=q});TextButton(onClick={quality=q}) { Text(q) } } }
                if(episodes.size>1) {
                    Text("Серии · выбрано ${selected.size}")
                    TextButton(onClick={ selected=if(selected.size==episodes.size) emptySet() else episodes.toSet() }) { Text(if(selected.size==episodes.size) "Снять выбор" else "Выбрать все") }
                    episodes.forEach { episode -> Row {
                        Checkbox(checked=episode in selected,onCheckedChange={ checked -> selected=if(checked) selected+episode else selected-episode })
                        TextButton(onClick={ selected=if(episode in selected) selected-episode else selected+episode }) { Text(episode.title) }
                    } }
                }
                Row { Switch(checked=subtitles,onCheckedChange={subtitles=it});TextButton(onClick={subtitles=!subtitles}) { Text("С субтитрами") } }
                Text(if(saved && savedFolder!=null) "Папка: $savedFolder" else "Папка будет выбрана в Android")
                if(remember && savedFolder!=null) TextButton(onClick={saved=!saved}) { Text(if(saved) "Выбрать другую папку" else "Использовать сохранённую папку") }
                Row { Switch(checked=remember,onCheckedChange={remember=it;if(!it) saved=false});TextButton(onClick={remember=!remember;if(!remember) saved=false}) { Text("Запомнить место сохранения") } }
            }
        },confirmButton={ TextButton(enabled=selected.isNotEmpty(),onClick={ confirm(DownloadChoice(format,quality,episodes.filter { it in selected },subtitles,remember,remember && saved)) }) { Text("Скачать") } },dismissButton={TextButton(onClick=dismiss) {Text("Отмена")}})
    }
}
