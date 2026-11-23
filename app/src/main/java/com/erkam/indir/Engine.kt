package com.erkam.indir

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // İlk açılışta python + yt-dlp + ffmpeg hazırlanır (birkaç saniye sürebilir)
        Thread { Engine.ensure(this); Engine.autoUpdate(this) }.start()
    }
}

/** yt-dlp motorunu hazırlar ve günceller. */
object Engine {
    @Volatile var ready = false
        private set
    @Volatile var initError: String? = null
        private set
    @Volatile var updating = false
        private set

    private val main = Handler(Looper.getMainLooper())
    val listeners = mutableSetOf<() -> Unit>()
    fun changed() = main.post { listeners.toList().forEach { it() } }

    @Synchronized
    fun ensure(ctx: Context): Boolean {
        if (ready) return true
        return try {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            ready = true
            initError = null
            changed()
            true
        } catch (e: Throwable) {
            initError = e.message ?: e.toString()
            changed()
            false
        }
    }

    fun version(ctx: Context): String =
        try { YoutubeDL.getInstance().versionName(ctx.applicationContext) ?: "" } catch (_: Throwable) { "" }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("motor", Context.MODE_PRIVATE)

    /** YouTube sık değiştiği için 2 günde bir sessizce günceller. */
    fun autoUpdate(ctx: Context) {
        val last = prefs(ctx).getLong("son", 0)
        if (System.currentTimeMillis() - last < 2L * 24 * 3600 * 1000) return
        update(ctx)
    }

    /** true: güncellendi, false: zaten güncel, null: hata */
    fun update(ctx: Context): Boolean? {
        if (!ensure(ctx)) return null
        updating = true; changed()
        return try {
            val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE)
            prefs(ctx).edit().putLong("son", System.currentTimeMillis()).apply()
            st == YoutubeDL.UpdateStatus.DONE
        } catch (_: Throwable) {
            null
        } finally {
            updating = false; changed()
        }
    }
}
