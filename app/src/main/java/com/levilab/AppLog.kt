package com.levilab

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private var file: File? = null
    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "levilab.log")
        lines.addAll(read().lines().filter { it.isNotEmpty() }.takeLast(500))
    }

    // Each line is appended and fsynced immediately so it survives a native crash.
    @Synchronized
    fun w(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + msg
        android.util.Log.i("LeviLab", msg)
        val f = file
        if (f != null) {
            try {
                FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()); it.fd.sync() }
            } catch (_: Throwable) {
            }
        }
        main.post { lines.add(line) }
    }

    fun read(): String = try { file?.readText()?.takeLast(200_000) ?: "" } catch (_: Throwable) { "" }

    fun clear() {
        try { file?.writeText("") } catch (_: Throwable) {}
        main.post { lines.clear() }
    }
}
