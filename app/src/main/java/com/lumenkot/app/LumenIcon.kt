package com.lumenkot.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Lucide 0.563.0 paths and shapes from the original app dependency (ISC license). */
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
        "‹" -> "m12 19-7-7 7-7 M19 12H5"
        "Качество" -> "M14 17H5 M19 7h-9 M14.0 17.0 a3.0 3.0 0 1 0 6.0 0 a3.0 3.0 0 1 0 -6.0 0 M4.0 7.0 a3.0 3.0 0 1 0 6.0 0 a3.0 3.0 0 1 0 -6.0 0"
        "Серия" -> "M21 5H3 M10 12H3 M10 19H3 M15 12.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997a1 1 0 0 1-1.517-.86z"
        "Скорость" -> "m12 14 4-4 M3.34 19a10 10 0 1 1 17.32 0"
        "Экран" -> "m15 15 6 6 m15 9 6-6 M21 16v5h-5 M21 8V3h-5 M3 16v5h5 m3 21 6-6 M3 8V3h5 M9 9 3 3"
        "PiP" -> "M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4 M12 13 h10 v7 h-10 Z"
        "Следующая" -> "M21 4v16 M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z"
        "Предыдущая" -> "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z M3 20V4"
        "Блокировка" -> "M11.0 16.0 a1.0 1.0 0 1 0 2.0 0 a1.0 1.0 0 1 0 -2.0 0 M5.0 10.0 H19.0 Q21.0 10.0 21.0 12.0 V20.0 Q21.0 22.0 19.0 22.0 H5.0 Q3.0 22.0 3.0 20.0 V12.0 Q3.0 10.0 5.0 10.0 Z M7 10V7a5 5 0 0 1 9.33-2.5"
        "Заблокировано" -> "M11.0 16.0 a1.0 1.0 0 1 0 2.0 0 a1.0 1.0 0 1 0 -2.0 0 M5.0 10.0 H19.0 Q21.0 10.0 21.0 12.0 V20.0 Q21.0 22.0 19.0 22.0 H5.0 Q3.0 22.0 3.0 20.0 V12.0 Q3.0 10.0 5.0 10.0 Z M7 10V7a5 5 0 0 1 10 0v3"
        "Поделиться" -> "m15 17 5-5-5-5 M4 18v-2a4 4 0 0 1 4-4h12"
        "Комментарии" -> "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z M7 11h10 M7 15h6 M7 7h8"
        "В закладки" -> "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z"
        "Субтитры" -> "M10 9.17a3 3 0 1 0 0 5.66 M17 9.17a3 3 0 1 0 0 5.66 M4.0 5.0 H20.0 Q22.0 5.0 22.0 7.0 V17.0 Q22.0 19.0 20.0 19.0 H4.0 Q2.0 19.0 2.0 17.0 V7.0 Q2.0 5.0 4.0 5.0 Z"
        else -> ""
    }
    override fun draw(canvas: Canvas) {canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f);PathParser.createPathFromPathData(data)?.let {canvas.drawPath(it,paint)};canvas.restore()}
    override fun setAlpha(alpha: Int) {paint.alpha=alpha}
    override fun setColorFilter(colorFilter: ColorFilter?) {paint.colorFilter=colorFilter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth()=24
    override fun getIntrinsicHeight()=24
}
