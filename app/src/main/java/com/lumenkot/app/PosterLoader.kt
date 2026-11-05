package com.lumenkot.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

object PosterLoader {
    fun load(api: RezkaApi, image: String, page: String): Bitmap? {
        if(image.isBlank()) return null
        val base=api.absolute(page).toHttpUrl()
        val url=when {
            image.startsWith("http://") || image.startsWith("https://") -> image
            image.startsWith("//") -> "https:$image"
            else -> Links.static+"/"+image.trimStart('/')
        }.toHttpUrl()
        val request=Request.Builder().url(url).header("Referer",base.toString())
        api.headers().forEach {(key,value)->request.header(key,value)}
        return api.client.newCall(request.build()).execute().use {response ->
            if(!response.isSuccessful) return@use null
            val bytes=response.body?.bytes() ?: return@use null
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            val options=BitmapFactory.Options().apply {inSampleSize=1;while(bounds.outWidth/inSampleSize>1200 || bounds.outHeight/inSampleSize>1800) inSampleSize*=2}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        }
    }
}
