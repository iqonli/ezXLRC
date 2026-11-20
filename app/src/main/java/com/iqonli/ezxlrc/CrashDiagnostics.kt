package com.iqonli.ezxlrc

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object CrashDiagnostics {
    private const val REPORT_FILE = "ezxlrc-last-crash.txt"
    private const val PREFS = "ezxlrc_crash_diagnostics"
    private const val KEY_STAGE = "last_stage"
    private const val KEY_EXIT_TIMESTAMP = "last_exit_timestamp"

    fun install(application: Application) {
        capturePreviousSystemExit(application)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeUncaughtException(application, thread, error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun markStage(context: Context, stage: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STAGE, stage)
                .commit()
        }
    }

    fun readReport(context: Context): String? =
        runCatching {
            File(context.filesDir, REPORT_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()

    fun clearReport(context: Context) {
        runCatching { File(context.filesDir, REPORT_FILE).delete() }
    }

    private fun writeUncaughtException(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val stage = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STAGE, "unknown") ?: "unknown"
            val report = buildString {
                appendLine("ezXLRC uncaught exception")
                appendLine(deviceInfo(context))
                appendLine("Time: ${timestamp(System.currentTimeMillis())}")
                appendLine("Thread: ${thread.name} (id=${thread.id})")
                appendLine("Last startup stage: $stage")
                appendLine()
                appendLine(android.util.Log.getStackTraceString(error))
            }
            FileOutputStream(File(context.filesDir, REPORT_FILE), false).use { stream ->
                stream.write(report.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
        }
    }

    private fun capturePreviousSystemExit(application: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val latest = manager.getHistoricalProcessExitReasons(application.packageName, 0, 8)
                .filter { isCrashReason(it.reason) }
                .maxByOrNull { it.timestamp }
                ?: return
            if (latest.timestamp <= prefs.getLong(KEY_EXIT_TIMESTAMP, 0L)) return
            val trace = runCatching {
                latest.traceInputStream?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0 || total >= 256 * 1024) break
                        val accepted = minOf(count, 256 * 1024 - total)
                        output.write(buffer, 0, accepted)
                        total += accepted
                    }
                    output.toString("UTF-8")
                }.orEmpty()
            }.getOrDefault("")
            val report = buildString {
                appendLine("Android recorded a previous abnormal process exit")
                appendLine(deviceInfo(application))
                appendLine("Time: ${timestamp(latest.timestamp)}")
                appendLine("Reason: ${reasonName(latest.reason)}")
                appendLine("Status: ${latest.status}")
                appendLine("Process: ${latest.processName}")
                appendLine("Description: ${latest.description ?: "(none)"}")
                appendLine()
                appendLine("System trace:")
                appendLine(trace.ifBlank { "(Android did not provide a trace.)" })
            }
            val file = File(application.filesDir, REPORT_FILE)
            if (file.isFile) {
                file.appendText("\n\n--- Android process-exit record ---\n\n$report", Charsets.UTF_8)
            } else {
                file.writeText(report, Charsets.UTF_8)
            }
            prefs.edit().putLong(KEY_EXIT_TIMESTAMP, latest.timestamp).commit()
        }
    }

    private fun isCrashReason(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> "OTHER_$reason"
    }

    private fun deviceInfo(context: Context): String {
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return buildString {
            appendLine("App: $version (${context.packageName})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    private fun timestamp(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(value))
}
