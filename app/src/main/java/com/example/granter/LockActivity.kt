package com.example.granter

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Màn hình chờ cấp quyền: hiện mã máy, tự hỏi server mỗi 15 giây, được duyệt thì tự mở app. */
class LockActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tv: TextView
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            thread {
                val s = License.refresh(applicationContext)
                runOnUiThread {
                    if (!running) return@runOnUiThread
                    if (s == License.State.ACTIVE) {
                        startActivity(Intent(this@LockActivity, MainActivity::class.java))
                        finish()
                    } else {
                        tv.text = when (s) {
                            License.State.PENDING -> "Đang chờ cấp quyền... Khi được duyệt, app sẽ tự mở."
                            License.State.REVOKED -> "Quyền của thiết bị này đã bị thu hồi. Liên hệ TikTok @hh_1059 để được hỗ trợ."
                            License.State.EXPIRED -> "Quyền sử dụng đã hết hạn. Liên hệ TikTok @hh_1059 để gia hạn."
                            else -> "Không kết nối được máy chủ, kiểm tra mạng"
                        }
                    }
                }
            }
            h.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = License.deviceCode(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(48, 120, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "XÁC THỰC QUYỀN TRUY CẬP"; textSize = 20f; setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Mã thiết bị: $code"; textSize = 18f; setTextColor(Color.BLACK)
            setTextIsSelectable(true); setPadding(0, 48, 0, 24)
        })
        root.addView(Button(this).apply {
            text = "Chép mã"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(this@LockActivity, "Đã chép mã thiết bị. Hãy dán vào tin nhắn gửi cho mình nhé!", Toast.LENGTH_LONG).show()
            }
        })
        tv = TextView(this).apply {
            text = "Đang kiểm tra..."; textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, 32, 0, 32)
        }
        root.addView(tv)
        root.addView(Button(this).apply {
            text = "Liên hệ TikTok @hh_1059"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@hh_1059")))
            }
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        running = true
        h.post(poll)
    }

    override fun onPause() {
        running = false
        h.removeCallbacks(poll)
        super.onPause()
    }
}
