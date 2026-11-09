package com.lumenkot.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** The same five groups and mobile modal / TV two-column structure as SettingsStructure. */
class SettingsScreen(
    private val activity: Activity, private val settings: Settings,
    private val external: (String)->Unit, private val themeChanged: ()->Unit,
    private val checkConnection: ()->Unit,private val chooseDownloadFolder: ()->Unit={}
) {
    companion object {val groups=listOf("Интерфейс","Сетевые настройки","Загрузки","Плеер","О приложении")}
    private val prefs get()=settings.prefs
    private val tv get()=(activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_TELEVISION
    private val light get()=when(prefs.getString("theme","system")) {"light"->true;"dark"->false;else->(activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES}
    private val foreground get()=Color.parseColor(if(light) "#1C1C1C" else "#E3E3E3")
    private val secondary get()=Color.parseColor(if(light) "#3B3838" else "#C4C7C5")
    private lateinit var content: LinearLayout
    private var group=groups.first()
    private var dialog: Dialog?=null
    private var editor: Dialog?=null
    private var versionTaps=0
    private var lastVersionTap=0L
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    private fun row(title: String,subtitle: String="",icon: String="Настройки",action: (() -> Unit)?=null): LinearLayout {
        val row=LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(56);setPadding(dp(12),dp(12),dp(12),dp(12))
            if(action!=null) {isFocusable=true;isClickable=true;setOnClickListener {action()}}
            background=android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),ColorDrawable(Color.parseColor(if(light) "#DADADA" else "#252525")))
                addState(intArrayOf(android.R.attr.state_pressed),ColorDrawable(Color.parseColor(if(light) "#DADADA" else "#252525")))
                addState(intArrayOf(),ColorDrawable(Color.TRANSPARENT))
            }
        }
        row.addView(ImageView(activity).apply {setImageDrawable(LumenIcon(icon,this@SettingsScreen.foreground));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(20),dp(20)).apply {marginEnd=dp(12)})
        val labels=column()
        labels.addView(TextView(activity).apply {text=title;textSize=14f;setTextColor(this@SettingsScreen.foreground)})
        if(subtitle.isNotBlank()) labels.addView(TextView(activity).apply {text=subtitle;textSize=14f;setTextColor(secondary);alpha=.8f})
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        return row
    }
    fun render(parent: LinearLayout) {
        parent.removeAllViews()
        val navigation=column().apply {id=R.id.settings_groups}
        if(tv) {
            val split=LinearLayout(activity)
            content=column().apply {id=R.id.settings_content}
            groups.forEach {name ->navigation.addView(row(name,icon=name) {group=name;renderGroup()})}
            split.addView(navigation,LinearLayout.LayoutParams(0,-2,1f));split.addView(content,LinearLayout.LayoutParams(0,-2,2f))
            parent.addView(split);renderGroup()
        } else {
            groups.forEach {name ->navigation.addView(row(name,icon=name) {openGroup(name)}.apply {
                addView(TextView(activity).apply {text="›";textSize=22f;setTextColor(secondary)})
            })}
            parent.addView(navigation)
        }
    }
    private fun openGroup(name: String) {
        if(dialog?.isShowing==true) return
        group=name
        val pane=column().apply {setPadding(dp(8),dp(8),dp(8),dp(8));background=GradientDrawable().apply {setColor(Color.parseColor(if(light) "#DADADA" else "#252525"));cornerRadius=dp(16).toFloat()}}
        val window=Dialog(activity)
        pane.addView(row(name,icon="Назад") {window.dismiss()})
        content=column().apply {id=R.id.settings_content}
        pane.addView(ScrollView(activity).apply {addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        window.setContentView(pane);window.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog=window;renderGroup();window.show()
        window.window?.setLayout(minOf(dp(560),activity.resources.displayMetrics.widthPixels-dp(24)),(activity.resources.displayMetrics.heightPixels*.8f).toInt())
    }
    private fun toggle(key: String,title: String,default: Boolean,subtitle: String="") {
        val switch=Switch(activity).apply {isChecked=prefs.getBoolean(key,default);contentDescription=title;setOnCheckedChangeListener {_,value ->prefs.edit().putBoolean(key,value).apply();if(key=="tvPlayButton") renderGroup()}}
        content.addView(row(title,subtitle,action={switch.isChecked=!switch.isChecked}).apply {addView(switch)})
    }
    private fun choose(title: String,options: List<String>,selected: Int=-1,save: (Int)->Unit) {
        if(editor?.isShowing==true || options.isEmpty()) return
        editor=AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(options.toTypedArray(),selected) {d,i ->d.dismiss();save(i);renderGroup()}.setNegativeButton("Отмена",null).show()
    }
    private fun textChoice(title: String,value: String,options: List<Pair<String,String>>,save: (String)->Unit) {
        content.addView(row(title,options.firstOrNull {it.first==value}?.second ?: value) {choose(title,options.map {it.second},options.indexOfFirst {it.first==value}) {save(options[it].first)}})
    }
    private fun integers(key: String,title: String,default: Int,values: List<Int>,label: (Int)->String={it.toString()}) {
        val value=prefs.getInt(key,default)
        content.addView(row(title,label(value)) {choose(title,values.map(label),values.indexOf(value)) {prefs.edit().putInt(key,values[it]).apply()}})
    }
    private fun speeds(key: String,title: String,default: Float,values: List<Float>) {
        val value=prefs.getFloat(key,default)
        content.addView(row(title,"${value}×",icon="Скорость") {choose(title,values.map {"${it}×"},values.indexOf(value)) {prefs.edit().putFloat(key,values[it]).apply()}})
    }
    private fun input(title: String,value: String,save: (String)->Unit) {
        if(editor?.isShowing==true) return
        val field=EditText(activity).apply {setText(value);isSingleLine=true}
        val d=AlertDialog.Builder(activity).setTitle(title).setView(field).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create()
        editor=d;d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {save(field.text.toString().trim())}.onSuccess {d.dismiss();renderGroup()}.onFailure {field.error=it.message ?: "Проверьте значение"}
        }
    }
    private fun address(title: String,value: String,options: List<String>,enabled: Boolean=true,save: (String)->Unit) {
        content.addView(row(title,value) {choose(title,options+"Другое…",options.indexOf(value)) {i ->if(i<options.size) save(options[i]) else input(title,value) {require(it.startsWith("https://") || it.startsWith("http://")) {"Введите HTTP(S)-адрес"};save(it)}}}.apply {isEnabled=enabled;alpha=if(enabled) 1f else .5f})
    }
    private fun renderGroup() {
        if(!::content.isInitialized) return
        content.removeAllViews()
        when(group) {
            "Интерфейс" -> {
                textChoice("Тема приложения",prefs.getString("theme","system")!!,listOf("system" to "Как в системе","dark" to "Тёмная","light" to "Светлая")) {prefs.edit().putString("theme",it).apply();dismiss();themeChanged()}
                val routes=listOf("home" to "Главная","search" to "Поиск","bookmarks" to "Закладки","history" to "История","account" to "Аккаунт")
                textChoice("Стартовый экран",prefs.getString("initialRoute","home")!!,routes) {prefs.edit().putString("initialRoute",it).apply()}
                integers(if(tv) "columnsTV" else "columns","Фильмов в ряду",if(tv) 6 else 3,(2..if(tv) 12 else 10).toList())
                toggle("continueButton","Кнопка продолжить просмотр",true)
            }
            "Сетевые настройки" -> {
                val official=Switch(activity).apply {contentDescription="Официальный режим";isChecked=settings.official;setOnCheckedChangeListener {_,v ->settings.official=v;renderGroup()}}
                content.addView(row("Официальный режим","Ссылки как в официальном приложении",action={official.isChecked=!official.isChecked}).apply {addView(official)})
                address("Зеркало",settings.provider,Links.providers) {settings.provider=it}
                address("Зеркало официального режима (Поделиться)",settings.shareLink,Links.providers,settings.official) {settings.shareLink=it}
                val automatic=Switch(activity).apply {contentDescription="Автоматический CDN";isChecked=settings.automaticCDN;setOnCheckedChangeListener {_,v ->settings.automaticCDN=v;renderGroup()}}
                content.addView(row("Автоматический CDN",action={automatic.isChecked=!automatic.isChecked}).apply {addView(automatic)})
                address("CDN",settings.cdn.ifBlank {Links.cdns.first()},Links.cdns,!settings.automaticCDN) {settings.cdn=it}
                content.addView(row("User-Agent",settings.userAgent) {input("User-Agent",settings.userAgent) {require(it.isNotBlank());settings.userAgent=it}})
                content.addView(row("Проверить соединение",action=checkConnection))
            }
            "Загрузки" -> {
                val locations=DownloadLocations(activity)
                content.addView(row("Папка загрузки",locations.label(),icon="Скачать",action=chooseDownloadFolder))
                toggle("rememberDownloadPath","Всегда сохранять в эту папку",false,"Если выключено, место выбирается перед скачиванием")
                if(locations.tree!=null) content.addView(row("Использовать папку приложения",action={locations.appFolder();renderGroup()}))
                val dirs=activity.getExternalFilesDirs("downloads").filterNotNull()
                val names=dirs.mapIndexed {i,_ ->if(i==0) "Внутренняя память" else "SD-карта $i"}
                if(locations.tree==null) content.addView(row("Хранилище приложения",names.getOrNull(prefs.getInt("downloadStorage",0)).orEmpty(),icon="Скачать") {choose("Хранилище приложения",names,prefs.getInt("downloadStorage",0)) {prefs.edit().putInt("downloadStorage",it).apply()}})
                toggle("downloadSubtitles","Скачивать субтитры",true)
                toggle("downloadPoster","Скачивать постер",true)
            }
            "Плеер" -> {
                textChoice("Качество видео в плеере",settings.quality,PlaybackQuality.values.map {it to if(it=="max") "Максимальное" else it}) {prefs.edit().putString("quality",it).apply()}
                toggle("saveQuality","Запоминать качество видео в плеере",true,"Сохранять выбор при изменении качества")
                toggle("askQuality","Всегда спрашивать качество",true,"Выбор качества перед запуском фильма или серии")
                integers("seekSeconds","Секунды перемотки",10,(5..60 step 5).toList()) {"$it сек."}
                integers("volumeStep","Шаг громкости",1,VolumeSteps.values) {if(it==1) "1 уровень" else if(it<5) "$it уровня" else "$it уровней"}
                textChoice("Соотношение сторон по умолчанию",prefs.getString("aspectRatio","contain")!!,listOf("contain" to "Вписать","cover" to "Обрезать","fill" to "Заполнить")) {prefs.edit().putString("aspectRatio",it).apply()}
                toggle("autoNext","Автоматически включать следующую серию",true)
                if(!tv) speeds("holdSpeed","Скорость при долгом нажатии",2f,(1..12).map {it*.25f})
                speeds("speed","Скорость воспроизведения",1f,listOf(.25f,.5f,.75f,1f,1.1f,1.25f,1.5f,1.75f,2f,2.25f,2.5f,2.75f,3f))
                toggle("showBuffer","Показывать загруженное время",false)
                toggle("showEndTime","Показывать время когда видео закончится",false)
                if(tv) {toggle("tvPlayButton","Воспроизведение по нажатию",false);if(prefs.getBoolean("tvPlayButton",false)) toggle("tvShowInterface","Показывать интерфейс при остановке видео на ТВ",true)}
                integers("bufferSeconds","Настройки буфера плеера",0,listOf(0)+(15..180 step 15).toList()) {if(it==0) "Автоматически" else "$it сек."}
            }
            "О приложении" -> {
                content.addView(row("Telegram","Перейти в Telegram") {external(Links.telegram)})
                content.addView(row("GitHub","Перейти на GitHub") {external(Links.github)})
                val version=activity.packageManager.getPackageInfo(activity.packageName,0).versionName.orEmpty()
                content.addView(row("Версия приложения",version) {
                    val now=android.os.SystemClock.elapsedRealtime();versionTaps=if(now-lastVersionTap<600) versionTaps+1 else 1;lastVersionTap=now
                    if(versionTaps>=3) {prefs.edit().putBoolean("securedSettings",true).apply();renderGroup()}
                })
                content.addView(row("Обновления оригинального Lumen",action={external(Links.updates)}))
                if(prefs.getBoolean("securedSettings",false) || prefs.getBoolean("cloudProgress",false)) toggle("cloudProgress","Time share",false,"Синхронизация прогресса через исходный Firebase")
            }
        }
    }
    fun dismiss() {editor?.dismiss();dialog?.dismiss()}
    fun refresh() {renderGroup()}
}
