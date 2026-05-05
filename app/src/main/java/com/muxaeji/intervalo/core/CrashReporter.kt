package com.muxaeji.intervalo.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import timber.log.Timber

class CrashReporter(
    private val context: Context
) {
    private val crashDir: File = CrashReportStore.crashDir(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                persistCrash(thread, throwable)
            }.onFailure {
                Timber.e(it, "Failed to persist crash report")
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        runCatching {
            importNativeExitsIfAvailable()
        }.onFailure {
            Timber.e(it, "Failed to import ApplicationExitInfo")
        }
        logPendingCrashes()
    }

    private fun persistCrash(thread: Thread, throwable: Throwable) {
        if (!crashDir.exists()) crashDir.mkdirs()
        val now = Instant.now().toString().replace(":", "-")
        val file = File(crashDir, "crash_$now.txt")
        file.writeText(
            buildString {
                appendLine("timestamp=$now")
                appendLine("thread=${thread.name}")
                appendLine("exception=${throwable::class.java.name}")
                appendLine("message=${throwable.message.orEmpty()}")
                appendLine("--- stacktrace ---")
                appendLine(stacktrace(throwable))
            }
        )
        cleanupOldReports()
    }

    /**
     * On Android 11+ (API 30), pull historical exit reasons. JVM uncaught-exception handlers do not
     * fire for native crashes (SIGSEGV from FluidSynth / Oboe / AudioTrack), ANRs, or low-memory
     * kills, so we mirror those into our crash-reports dir as well — otherwise the user's "send
     * crash report" button never appears after a native crash.
     */
    private fun importNativeExitsIfAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        val infos = am.getHistoricalProcessExitReasons(null, 0, MAX_EXIT_QUERY)
        var newest = lastSeen
        for (info in infos) {
            val ts = info.timestamp
            if (ts <= lastSeen) continue
            if (ts > newest) newest = ts
            if (!shouldReport(info.reason)) continue
            persistExitInfo(info)
        }
        if (newest != lastSeen) {
            prefs.edit().putLong(KEY_LAST_EXIT_TS, newest).apply()
        }
        cleanupOldReports()
    }

    private fun shouldReport(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY -> true
        else -> false
    }

    private fun persistExitInfo(info: ApplicationExitInfo) {
        if (!crashDir.exists()) crashDir.mkdirs()
        val ts = Instant.ofEpochMilli(info.timestamp).toString().replace(":", "-")
        val file = File(crashDir, "exit_$ts.txt")
        file.writeText(
            buildString {
                appendLine("timestamp=$ts")
                appendLine("reason=${reasonLabel(info.reason)}")
                appendLine("description=${info.description.orEmpty()}")
                appendLine("processName=${info.processName}")
                appendLine("status=${info.status}")
                appendLine("importance=${info.importance}")
                appendLine("pss=${info.pss}")
                appendLine("rss=${info.rss}")
                appendLine("--- traces ---")
                runCatching {
                    info.traceInputStream?.bufferedReader()?.use { append(it.readText()) }
                }.onFailure {
                    appendLine("(failed to read traces: ${it.message})")
                }
            }
        )
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (JVM)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    private fun logPendingCrashes() {
        if (!crashDir.exists()) return
        crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?.forEach { report ->
                Timber.w("Pending crash report detected: %s", report.absolutePath)
            }
    }

    private fun cleanupOldReports() {
        crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)
            ?.forEach { it.delete() }
    }

    private fun stacktrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    private companion object {
        const val MAX_REPORTS = 10
        const val MAX_EXIT_QUERY = 16
        const val PREFS_NAME = "crash_reporter_prefs"
        const val KEY_LAST_EXIT_TS = "last_seen_exit_ts"
    }
}
