package com.lumenkot.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Exact Lucide 0.563.0 path data from the original app dependency (ISC license). */
class LumenIcon(name: String,color: Int): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=2f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val data=when(name) {
        "Главная" -> "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8 M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
        "Поиск" -> "m21 21-4.34-4.34 M3.0 11.0 a8.0 8.0 0 1 0 16.0 0 a8.0 8.0 0 1 0 -16.0 0"
        "Закладки" -> "M10.638 20H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v3.417 M14.62 18.8A2.25 2.25 0 1 1 18 15.836a2.25 2.25 0 1 1 3.38 2.966l-2.626 2.856a.998.998 0 0 1-1.507 0z"
        "История" -> "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8 M3 3v5h5 M12 7v5l4 2"
        "Аккаунт" -> "M7.0 8.0 a5.0 5.0 0 1 0 10.0 0 a5.0 5.0 0 1 0 -10.0 0 M20 21a8 8 0 0 0-16 0"
        "Назад" -> "m12 19-7-7 7-7 M19 12H5"
        "Уведомления" -> "M10.268 21a2 2 0 0 0 3.464 0 M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"
        "Настройки" -> "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915 M9.0 12.0 a3.0 3.0 0 1 0 6.0 0 a3.0 3.0 0 1 0 -6.0 0"
        "Голосом" -> "M12 19v3 M19 10v2a7 7 0 0 1-14 0v-2 M9 2 h6 v13 h-6 Z"
        "Фильтры" -> "M14 17H5 M19 7h-9 M14.0 17.0 a3.0 3.0 0 1 0 6.0 0 a3.0 3.0 0 1 0 -6.0 0 M4.0 7.0 a3.0 3.0 0 1 0 6.0 0 a3.0 3.0 0 1 0 -6.0 0"
        "Подборки" -> "M3 3 h7 v7 h-7 Z M14 3 h7 v7 h-7 Z M14 14 h7 v7 h-7 Z M3 14 h7 v7 h-7 Z"
        else -> ""
    }
    override fun draw(canvas: Canvas) {canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f);PathParser.createPathFromPathData(data)?.let {canvas.drawPath(it,paint)};canvas.restore()}
    override fun setAlpha(alpha: Int) {paint.alpha=alpha}
    override fun setColorFilter(colorFilter: ColorFilter?) {paint.colorFilter=colorFilter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth()=24
    override fun getIntrinsicHeight()=24
}
