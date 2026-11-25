package com.brewprint.data

import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The tag on the app's own lines in Android's log. */
const val LOG_TAG = "Brewprint"

/** The name offered when saving an export made on [day]. */
fun exportFileName(day: LocalDate): String = "brewprint-export-$day.zip"

/**
 * Settings → "Export data": one zip with what the app knows about itself, everything it keeps on this phone, its
 * recent log, and its own log of the scale's link, for the user to keep or attach to a problem report.
 */
fun writeExport(output: OutputStream, about: String, data: ByteArray, log: String, scaleLog: String) {
    ZipOutputStream(output).use { zip ->
        listOf("about.txt" to about.toByteArray(), "data.json" to data, "log.txt" to redactSecrets(log).toByteArray(), "scale-log.txt" to scaleLog.toByteArray()).forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

/** The app's recent lines from Android's log. Android shows an app only its own lines, and keeps them until it needs the room. */
fun readAppLog(): String = runCatching {
    val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
    try { process.inputStream.bufferedReader().use { it.readText() }.ifEmpty { "The log is empty." } } finally { process.destroy() }
}.getOrElse { "Couldn't read the app's log: $it" }

// Named tokens first, so a JWT among them is redacted once, whole. A JSON key may come escaped, as \"refresh_token\".
private val tokens = listOf(
    Regex("""(?i)((?:access|refresh|provider|id)[_-]?token\\?["']?\s*[:=]\s*\\?["']?)[^\s,&"'}\\\]]+"""),
    Regex("""(?i)(bearer\s+)[^\s,\[\]"]+"""),
    // Sign-in tokens are JWTs, which a failed request's headers may carry into the log.
    Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]*"""),
)

/** Removes sign-in tokens, so an export can be shared without giving access to the account. */
fun redactSecrets(text: String): String = tokens.fold(text) { result, token ->
    token.replace(result) { match -> match.groupValues.getOrElse(1) { "" } + "[redacted]" }
}
