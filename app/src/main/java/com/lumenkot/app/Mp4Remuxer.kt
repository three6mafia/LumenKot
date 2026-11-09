package com.lumenkot.app

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Injectable native boundary; queue/storage tests do not pretend to execute Android native code. */
interface Mp4Remuxer {
    fun remux(video: File,audio: File?,durationMs: Long,output: File,canceled: ()->Boolean,progress: (Float)->Unit)
}

object NativeMp4Remuxer: Mp4Remuxer {
    internal fun arguments(video: File,audio: File?,output: File): Array<String> = buildList {
        addAll(listOf("-y","-nostdin","-v","error"))
        fun input(file: File) {addAll(listOf("-protocol_whitelist","file,crypto,data","-allowed_extensions","ALL","-allowed_segment_extensions","ALL","-extension_picky","0","-i",file.absolutePath))}
        input(video);audio?.let(::input)
        addAll(listOf("-map","0:v:0","-map",if(audio==null) "0:a:0?" else "1:a:0","-c","copy","-movflags","+faststart","-f","mp4",output.absolutePath))
    }.toTypedArray()
    override fun remux(video: File,audio: File?,durationMs: Long,output: File,canceled: ()->Boolean,progress: (Float)->Unit) {
        if(canceled() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val done=CountDownLatch(1)
        val session=FFmpegKit.executeWithArgumentsAsync(arguments(video,audio,output),{done.countDown()},{_ ->},{stats ->
            if(durationMs>0) progress((stats.time.toFloat()/durationMs).coerceIn(0f,1f))
        })
        var interrupted=false
        try {
            while(!done.await(100,TimeUnit.MILLISECONDS)) if(canceled()) throw InterruptedException()
            if(canceled()) throw InterruptedException()
            if(!ReturnCode.isSuccess(session.returnCode)) throw IOException("Не удалось собрать MP4. Потоки могут быть повреждены или несовместимы с MP4.")
        } catch(e: InterruptedException) {interrupted=true;throw e} finally {
            if(interrupted) {
                FFmpegKit.cancel(session.sessionId)
                // Wait for the native writer to close before a new attempt can reuse this path.
                var closed=false
                while(!closed) try {done.await();closed=true} catch(_: InterruptedException) {}
                Thread.currentThread().interrupt()
            }
        }
    }
}
