package com.iqonli.ezxlrc.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.iqonli.ezxlrc.CrashDiagnostics
import com.iqonli.ezxlrc.MainActivity

class CrashRecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashDiagnostics.readReport(this)
        if (report.isNullOrBlank()) {
            launchMain()
        } else {
            showReport(report)
        }
    }

    private fun showReport(report: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(248, 249, 251))
        }
        val title = TextView(this).apply {
            text = "ezXLRC 上次启动发生异常"
            textSize = 21f
            setTextColor(Color.rgb(25, 35, 48))
        }
        root.addView(title)
        val subtitle = TextView(this).apply {
            text = "已保存系统崩溃记录。复制或分享下面的完整日志发给开发者。"
            textSize = 14f
            setTextColor(Color.rgb(85, 96, 110))
            setPadding(0, 10, 0, 14)
        }
        root.addView(subtitle)
        val scroll = ScrollView(this)
        val log = TextView(this).apply {
            text = report
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(35, 43, 54))
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(log, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val copy = Button(this).apply {
            text = "复制崩溃日志"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ezXLRC crash report", report))
                Toast.makeText(this@CrashRecoveryActivity, "已复制完整日志", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copy, buttonParams())

        val share = Button(this).apply {
            text = "分享崩溃日志"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ezXLRC Android crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(intent, "分享崩溃日志"))
            }
        }
        root.addView(share, buttonParams())

        val retry = Button(this).apply {
            text = "清除日志并重新启动 ezXLRC"
            setOnClickListener {
                CrashDiagnostics.clearReport(this@CrashRecoveryActivity)
                launchMain()
            }
        }
        root.addView(retry, buttonParams())
        setContentView(root)
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 6 }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
