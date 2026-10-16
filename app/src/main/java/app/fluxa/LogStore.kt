package app.fluxa

import eu.kanade.tachiyomi.R
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log helpers for the in-app log viewer: reads this app's own logcat (Java + native "Fluxa" tags)
 * and keeps the last uncaught Java crash in a file so it survives the process dying.
 */
object LogStore {
    private const val TAG = "FluxaLog"
    private const val CRASH_FILE = "last_crash.txt"
    private const val MAX_LINES = 4000

    @Volatile private var installed = false

    /** Call early (Activity/Service onCreate). Writes uncaught exceptions to [CRASH_FILE]. */
    fun installCrashHandler(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(app.filesDir, CRASH_FILE).writeText(
                    "[$stamp] uncaught exception in thread \"${thread.name}\"\n${Log.getStackTraceString(error)}",
                )
            }
            runCatching { DisplayTuner.restoreIfApplied(app) } // never leave the screen at a reduced size
            Log.e(TAG, "uncaught exception in ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun header(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return "${context.getString(R.string.fluxa_app_name)} $version · ${Build.MANUFACTURER} ${Build.MODEL} · " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}"
    }

    /** Device info + last Java crash (if any) + recent logcat of this app. Blocking: call off the main thread. */
    fun read(context: Context, warnAndAbove: Boolean): String {
        val out = StringBuilder()
        out.append(header(context)).append("\n\n")

        val crash = File(context.filesDir, CRASH_FILE)
        if (crash.exists()) {
            out.append("===== CRASH ครั้งล่าสุด =====\n")
            out.append(runCatching { crash.readText() }.getOrDefault("(อ่านไม่ได้)")).append("\n")
        }

        out.append("===== logcat =====\n")
        val cmd = mutableListOf("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "crash", "-t", MAX_LINES.toString())
        if (warnAndAbove) cmd.add("*:W")
        try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.append(it).append('\n') } }
            process.waitFor()
        } catch (t: Throwable) {
            out.append("อ่าน logcat ไม่ได้: ${t.message}\n")
        }
        return out.toString()
    }

    /** Clears logcat and the saved crash. */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, CRASH_FILE).delete() }
        runCatching { ProcessBuilder("logcat", "-c").start().waitFor() }
    }
}
