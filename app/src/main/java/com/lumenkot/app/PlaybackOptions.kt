package com.lumenkot.app

import kotlin.math.roundToInt

/** Shared by the pre-play selector, in-player selector and episode changes. */
object PlaybackQuality {
    val values=listOf("max","4K","2K","1080p Ultra","1080p","720p","480p","360p")
    fun preferred(video: Video, preference: String): Stream? {
        val available=video.streams.filter {it.available}
        return available.firstOrNull {it.quality==(video.selectedQuality ?: preference)} ?: available.maxByOrNull {rank(it.quality)}
    }
    private fun rank(quality: String): Int = when(quality.lowercase()) {
        "4k" -> 2160
        "2k" -> 1440
        "1080p ultra" -> 1081
        else -> Regex("\\d+").find(quality)?.value?.toIntOrNull() ?: 0
    }
    fun remember(settings: Settings, stream: Stream) {
        if(stream.available && settings.prefs.getBoolean("saveQuality",true)) settings.prefs.edit().putString("quality",stream.quality).apply()
    }
    fun premiumMessage(quality: String="1080p Ultra") = "Для $quality требуется доступ HDrezka Premium. Проверьте подписку и вход в аккаунт на выбранном зеркале или выберите другое качество."
}

/** Steps use the device's real music-volume levels, including hardware keys. */
object VolumeSteps {
    val values=listOf(1,2,3,5)
    fun target(start: Int, maximum: Int, delta: Float, step: Int): Int {
        if(maximum<=0) return 0
        val amount=step.coerceAtLeast(1)
        val change=(delta*maximum/amount).roundToInt()*amount
        return (start+change).coerceIn(0,maximum)
    }
}
