package com.example.granter

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** Ô nhập mã ghép nối nổi trên mọi màn hình (không phụ thuộc thông báo). */
object PairOverlay {
    private var current: View? = null

    @Suppress("DEPRECATION")
    private val OVERLAY_TYPE =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    fun show(ctx: Context, host: String, port: Int) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                showNow(app, host, port)
            } catch (e: Throwable) {
                Notif.show(app, "Không hiện được ô nhập mã: ${e.message}")
            }
        }
    }

    fun dismiss(c: Context) {
        val v = current ?: return
        current = null
        try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (ignored: Throwable) {}
    }

    private fun showNow(c: Context, host: String, port: Int) {
        dismiss(c)
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = c.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val dark = Color.parseColor("#6D4C5B")

        fun pill(color: Int, r: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(r).toFloat()
        }

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = pill(Color.parseColor("#FEF4F7"), 24)
        }
        card.addView(TextView(c).apply {
            text = "Nhập mã ghép nối 6 số"; textSize = 16f; setTextColor(dark)
        })
        val input = EditText(c).apply {
            hint = "000000"; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(dark)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
        }
        card.addView(input)

        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = Button(c).apply {
            text = "Hủy"; isAllCaps = false; setTextColor(dark)
            background = pill(Color.parseColor("#FBD9E4"), 20)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, dp(8), dp(6), 0) }
            setOnClickListener { dismiss(c) }
        }
        val ok = Button(c).apply {
            text = "Ghép nối"; isAllCaps = false; setTextColor(dark)
            background = pill(Color.parseColor("#F2A0BC"), 20)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), dp(8), 0, 0) }
            setOnClickListener {
                val code = input.text.toString().trim()
                if (code.length != 6) {
                    input.error = "Cần đủ 6 số"
                } else {
                    dismiss(c)
                    Notif.show(c, "Đang ghép nối...")
                    thread {
                        val paired = AdbBackend.pair(c, host, port, code)
                        if (!paired) {
                            Notif.show(c, "Ghép nối thất bại: ${AdbBackend.lastError.take(80)}. Thử lại.")
                        } else {
                            val conn = AdbBackend.ensureConnected(c)
                            Notif.show(c, if (conn) "Ghép nối và kết nối thành công ✅"
                            else "Đã ghép nối. Mở HH tool và bấm KẾT NỐI ADB")
                        }
                    }
                }
            }
        }
        row.addView(cancel); row.addView(ok)
        card.addView(row)

        val lp = WindowManager.LayoutParams(
            dm.widthPixels - dp(32), WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_TYPE, 0, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        wm.addView(card, lp)
        current = card
        input.requestFocus()
        input.postDelayed({
            try {
                (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } catch (ignored: Throwable) {}
        }, 300)
        // Tự đóng sau 5 phút nếu quên
        Handler(Looper.getMainLooper()).postDelayed({
            if (current === card) dismiss(c)
        }, 300_000)
    }
}
