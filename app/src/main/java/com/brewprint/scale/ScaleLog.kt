package com.brewprint.scale

import android.content.Context
import android.util.Log
import com.brewprint.data.LOG_TAG
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * What happened on the scale's Bluetooth link, kept in the app's own files. Some phones keep only the last seconds of
 * Android's log, so the lines that show where a slow weight comes from are kept here for "Export data". Once [file]
 * passes [limit] bytes it becomes the older half and a new one starts, so at most about twice [limit] is kept.
 */
class ScaleLog(
    private val file: File,
    private val limit: Long = 256 * 1024,
    private val writes: Executor = WRITES,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        @Volatile private var shared: ScaleLog? = null
        private val WRITES: Executor = Executors.newSingleThreadExecutor { Thread(it, "brewprint-scale-log").apply { isDaemon = true } }
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        fun get(context: Context): ScaleLog = shared ?: synchronized(this) {
            shared ?: ScaleLog(File(context.applicationContext.filesDir, "scale-log.txt")).also { shared = it }
        }
    }

    private val older = File(file.path + ".1")

    /** Adds one line with the time it happened. The file is written in the background, so the scale never waits for storage. */
    fun add(line: String) {
        val stamped = STAMP.format(Instant.ofEpochMilli(clock()).atZone(zone)) + " " + line + "\n"
        writes.execute {
            runCatching {
                if (file.length() > limit) { older.delete(); file.renameTo(older) }
                file.appendText(stamped)
            }.onFailure { Log.w(LOG_TAG, "Couldn't write the scale log", it) }
        }
    }

    /** Every line kept, oldest first. */
    fun read(): String = listOf(older, file).joinToString("") { runCatching { it.readText() }.getOrDefault("") }
}

/** The scale log for an export, or a line saying there is none. */
fun readScaleLog(context: Context): String = ScaleLog.get(context).read().ifEmpty { "The scale log is empty.\n" }
