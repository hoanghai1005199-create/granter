package com.example.granter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String, val system: Boolean, val category: Int)

    private var mode = "user"   // user | social | bank | all

    private val socialPkgs = setOf(
        "com.zing.zalo", "com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
        "com.facebook.mlite", "com.instagram.android", "org.telegram.messenger",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android",
        "com.whatsapp", "com.viber.voip", "com.discord", "com.snapchat.android",
        "com.pinterest", "com.linkedin.android", "com.reddit.frontpage",
        "org.thoughtcrime.securesms", "com.threads.android", "com.instagram.barcelona",
        "com.google.android.youtube", "com.line.android", "jp.naver.line.android"
    )
    private val bankKeys = listOf(
        "bank", "ngân hàng", "momo", "mservice", "zalopay", "vnpay", "viettelpay",
        "mbmobile", "vcb", "vietcom", "vietin", "bidv", "techcom", "agri", "tpb",
        "sacom", "vpbank", "hdbank", "msb", "shb", "ocb", "acb", "napas"
    )

    private val DANGEROUS_PERMS = setOf(
        "android.permission.READ_SMS", "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_MMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
        "android.permission.CALL_PHONE", "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS",
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
        "android.permission.BODY_SENSORS", "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.WRITE_SETTINGS",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.QUERY_ALL_PACKAGES"
    )

    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase()

    private fun matches(a: AppItem): Boolean = when (mode) {
        "user" -> !a.system
        "social" -> a.category == ApplicationInfo.CATEGORY_SOCIAL || a.pkg in socialPkgs
        "bank" -> bankKeys.any { normalize(a.pkg).contains(it) || normalize(a.label).contains(it) }
        else -> true
    }

    private val BG = Color.parseColor("#FEF4F7")
    private val BLUE = Color.parseColor("#F2A0BC")
    private val ACCENT_TEXT = Color.parseColor("#B04A6E")
    private val BAR = Color.parseColor("#FBD2E0")
    private val DARK = Color.parseColor("#6D4C5B")

    private val checked = linkedSetOf<String>()   // các app đang được tích
    private lateinit var tvSelected: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvHealth: TextView
    private lateinit var pinnedBox: LinearLayout
    private lateinit var all: List<AppItem>
    private val prefs by lazy { getSharedPreferences("granter", MODE_PRIVATE) }
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private lateinit var pairBox: LinearLayout
    private var showTab: (Int) -> Unit = {}
    private lateinit var etCode: EditText
    private lateinit var etPort: EditText
    @Volatile private var pairHost: String = ""

    private var lastStatusMs = 0L
    private var lastStatusText = ""
    private var lastHealthText = ""

    private val appOps = listOf(
        "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS",
        "ACCESS_RESTRICTED_SETTINGS", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND",
        "USE_FULL_SCREEN_INTENT", "SCHEDULE_EXACT_ALARM", "POST_NOTIFICATION",
        "REQUEST_INSTALL_PACKAGES", "GET_USAGE_STATS"
    ) + listOf(10001, 10002, 10004, 10008, 10017, 10018, 10020, 10021, 10022,
        10023, 10033, 10037, 10045, 10048, 10049, 10053).map { it.toString() }

    private fun blueButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(DARK)
        backgroundTintList = ColorStateList.valueOf(BLUE)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, oval: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        if (oval) setShape(android.graphics.drawable.GradientDrawable.OVAL)
        else cornerRadius = dp(20).toFloat()
    }

    private fun circleButton(icon: String, label: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(context).apply {
                text = icon; textSize = 24f
                gravity = android.view.Gravity.CENTER
                background = shape(Color.WHITE, true)
                elevation = dp(4).toFloat()
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            })
            addView(TextView(context).apply {
                text = label; textSize = 11f; setTextColor(DARK)
                gravity = android.view.Gravity.CENTER
                maxLines = 3
                setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun styleDialog(d: android.app.AlertDialog) {
        d.window?.setBackgroundDrawable(shape(BG, false).apply { cornerRadius = dp(24).toFloat() })
        d.findViewById<TextView>(android.R.id.message)?.setTextColor(DARK)
        val tid = resources.getIdentifier("alertTitle", "id", "android")
        if (tid != 0) d.findViewById<TextView>(tid)?.setTextColor(DARK)
        listOf(android.content.DialogInterface.BUTTON_POSITIVE,
            android.content.DialogInterface.BUTTON_NEGATIVE).forEach {
            d.getButton(it)?.setTextColor(ACCENT_TEXT)
        }
    }

    private fun circleRow(vararg items: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEach { addView(it) }
        repeat(4 - items.size) {
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (License.local(this) != License.State.ACTIVE) { goLock(); return }
        window.statusBarColor = BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(16), dp(12), dp(8))
        }

        tvStatus = TextView(this).apply { textSize = 13f; setTextColor(DARK) }
        tvHealth = TextView(this).apply {
            textSize = 12f; setTextColor(DARK); setPadding(0, dp(4), 0, dp(4))
        }
        pinnedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply { hint = "Tìm ứng dụng..."; setSingleLine() }
        val list = ListView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        tvSelected = TextView(this).apply {
            text = "Chưa chọn ứng dụng"; textSize = 15f; setTextColor(DARK)
            setPadding(0, dp(6), 0, dp(6))
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(circleRow(
                circleButton("🔑", "Cấp quyền (các app đã tích)") { showTab(2); grantChecked() },
                circleButton("🔗", "Ghép nối ADB") { startPairing() },
                circleButton("📶", "Kết nối ADB") { connectAdb() },
                circleButton("🌐", "Đặt ngôn ngữ vi-VN") { setLocaleVi() }))
            addView(circleRow(
                circleButton("📡", "Tình trạng FCM") { FcmCheck.open(this@MainActivity) },
                circleButton("📋", "Xem Millet") { showMillet() },
                circleButton("⚡", "Cấp full quyền chỉ với 1 nhấn") { confirmFixAll() },
                circleButton("🖥", "Máy chủ shell (bật/tắt)") { toggleServer() }))
            addView(circleRow(
                circleButton("❄", "Freezer") { showFreezeManager() },
                circleButton("📜", "Nhật ký ") { showOverrideLog() },
                circleButton("💾", "Sao lưu / khôi phục") { showBackupMenu() },
                circleButton("🔀", "Chế độ kết nối") { showBackendMode() }))
        }

        tvLog = TextView(this).apply { setTextColor(DARK); setPadding(0, dp(8), 0, dp(16)) }

        etCode = EditText(this).apply {
            hint = "Mã ghép nối 6 số"; textSize = 22f; setTextColor(DARK)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
        }
        etPort = EditText(this).apply {
            hint = "Cổng (tự điền, khớp dòng IP & Port trong Cài đặt)"; textSize = 16f
            setTextColor(DARK)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(5))
        }
        pairBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "Ghép nối ADB: gõ mã 6 số trong hộp Cài đặt vào đây"
                textSize = 14f; setTextColor(DARK)
            })
            addView(etCode)
            addView(etPort)
            addView(blueButton("Ghép nối") { doPairInline() })
        }

        val chipColors = mapOf(
            "user" to Pair(Color.parseColor("#FBD9E4"), Color.parseColor("#F2A0BC")),
            "social" to Pair(Color.parseColor("#D6EAFB"), Color.parseColor("#8EC1F0")),
            "bank" to Pair(Color.parseColor("#D9F0DC"), Color.parseColor("#8FD19A")),
            "all" to Pair(Color.parseColor("#FBD9E4"), Color.parseColor("#F2A0BC")))
        val chips = mutableListOf<Pair<String, Button>>()
        var refreshList: () -> Unit = {}
        fun paintChips() = chips.forEach { (m, b) ->
            val (light, strong) = chipColors.getValue(m)
            b.background = shape(if (m == mode) strong else light, false)
            b.setTypeface(null, if (m == mode) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL)
        }
        val chipBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("user" to "Của tôi", "social" to "Mạng xã hội",
            "bank" to "Ngân hàng", "all" to "Tất cả").forEach { (m, name) ->
            val b = Button(this).apply {
                text = name; isAllCaps = false; setTextColor(DARK)
                stateListAnimator = null
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                setOnClickListener { mode = m; paintChips(); refreshList() }
            }
            chips += m to b; chipBar.addView(b)
        }
        paintChips()

        // ===== 3 trang =====
        // Trang 0: danh sách app (list chiếm hết chiều cao còn lại)
        val pageApps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; addView(chipBar)
            })
            addView(search)
            addView(list)
            addView(tvSelected)
            addView(blueButton(" Cấp full quyền cho app bạn đã chọn") { showTab(2); grantChecked() })
        }
        // Trang 1: app đã cấp
        val pagePinned = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(TextView(context).apply {
                    text = "Ứng dụng đã cấp (bấm 1 lần để cấp lại, nhấn giữ để bỏ)"
                    textSize = 14f; setTextColor(DARK); setPadding(0, 0, 0, dp(8))
                })
                addView(pinnedBox)
            })
        }
        // Trang 2: công cụ + trạng thái + nhật ký (cuộn được)
        val pageTools = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(tvStatus)
                addView(tvHealth)
                addView(pairBox)
                addView(grid)
                addView(tvLog)
            })
        }

        val pages = listOf<View>(pageApps, pagePinned, pageTools)
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            pages.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }

        // Thanh tab dưới cùng
        val tabViews = mutableListOf<TextView>()
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        listOf(" Ứng dụng", " Đã cấp", "Bộ Công cụ").forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name; textSize = 14f; setTextColor(DARK)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
                setOnClickListener { showTab(i) }
            }
            tabViews += t; tabBar.addView(t)
        }
        showTab = { i ->
            pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
            tabViews.forEachIndexed { k, t ->
                t.background = shape(if (k == i) BLUE else BAR, false)
                t.setTypeface(null, if (k == i) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL)
            }
        }
        showTab(0)

        root.addView(content)
        root.addView(tabBar)
        setContentView(root)

        all = packageManager.getInstalledApplications(0)
            .map {
                val sys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppItem(packageManager.getApplicationLabel(it).toString(),
                    it.packageName, sys, it.category)
            }
            .sortedBy { it.label.lowercase() }

        fun show(filter: String) {
            val items = all.filter {
                matches(it) && (it.label.contains(filter, true) || it.pkg.contains(filter, true))
            }
            list.adapter = object : ArrayAdapter<AppItem>(
                this, android.R.layout.simple_list_item_2, items) {
                override fun getView(p: Int, v: View?, g: ViewGroup): View {
                    val row = (v as? LinearLayout) ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        addView(CheckBox(context).apply {
                            id = android.R.id.checkbox
                            isClickable = false; isFocusable = false
                            buttonTintList = ColorStateList.valueOf(ACCENT_TEXT)
                        })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(context).apply {
                                id = android.R.id.text1; textSize = 18f
                                setTextColor(Color.parseColor("#E53935"))
                            })
                            addView(TextView(context).apply {
                                id = android.R.id.text2; textSize = 14f
                                setTextColor(DARK)
                            })
                        })
                    }
                    val app = items[p]
                    row.findViewById<CheckBox>(android.R.id.checkbox).isChecked = app.pkg in checked
                    row.findViewById<TextView>(android.R.id.text1).text = app.label
                    row.findViewById<TextView>(android.R.id.text2).text = app.pkg
                    return row
                }
            }
            list.setOnItemClickListener { _, _, i, _ ->
                val pkg = items[i].pkg
                if (!checked.add(pkg)) checked.remove(pkg)
                (list.adapter as ArrayAdapter<*>).notifyDataSetChanged()
                updateSelectedText()
            }
        }
        refreshList = { show(search.text.toString()) }
        refreshList()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = show(s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        tvSelected.setOnClickListener {
            checked.clear(); refreshList(); updateSelectedText()
        }
        updateSelectedText()
        refreshPinned()
    }

    private fun updateSelectedText() {
        tvSelected.text = if (checked.isEmpty()) "Chưa chọn ứng dụng"
        else "Đã chọn ${checked.size} ứng dụng (chạm vào đây để bỏ chọn hết)"
    }

    private val licH = Handler(Looper.getMainLooper())
    private var licWatching = false
    private val licPoll = object : Runnable {
        override fun run() {
            if (!licWatching) return
            thread {
                val s = License.refresh(applicationContext)
                runOnUiThread {
                    if (!licWatching) return@runOnUiThread
                    if (s != License.State.ACTIVE) goLock()
                    else licH.postDelayed(this, 20_000L)
                }
            }
        }
    }

    private fun startLicenseWatch() {
        licWatching = true
        licH.removeCallbacks(licPoll)
        licH.post(licPoll)
    }

    private fun stopLicenseWatch() {
        licWatching = false
        licH.removeCallbacks(licPoll)
    }

    override fun onPause() {
        stopLicenseWatch()
        super.onPause()
    }

    private fun goLock() {
        stopLicenseWatch()
        gateDialog?.let { try { it.dismiss() } catch (ignored: Throwable) {} }
        gateDialog = null
        try { stopDiscovery() } catch (ignored: Throwable) {}
        try { GuardJob.cancel(this) } catch (ignored: Throwable) {}
        try { stopService(Intent(this, GuardService::class.java)) } catch (ignored: Throwable) {}
        val app = applicationContext
        
        val state = License.local(app)
        if (state == License.State.REVOKED || state == License.State.EXPIRED) {
            thread {
                try { Guard.cleanup(app) } catch (ignored: Throwable) {}
                try { if (HhServer.ping(app)) HhServer.stop(app) } catch (ignored: Throwable) {}
            }
        }
        
        startActivity(Intent(this, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (License.local(this) != License.State.ACTIVE) { goLock(); return }
        prefs.edit().putBoolean("cleaned", false).apply()
        startLicenseWatch()
        if (!checkGate()) return
        updateStatus()
        if (prefs.getBoolean("guard_on", false) && Guard.canWrite(this)) startGuard()
        if (!autoRan) { autoRan = true; enableGuard(auto = true) }
    }

    private var gateDialog: android.app.AlertDialog? = null
    private var autoRan = false

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) { pkg }

    private fun tryStart(vararg intents: Intent) {
        for (i in intents) {
            try { startActivity(i); return } catch (ignored: Exception) {}
        }
        toast("Không mở được, hãy vào Cài đặt > Ứng dụng > HH tool để chỉnh")
    }

    private fun openPowerSettings() = tryStart(
        Intent().setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            .putExtra("package_name", packageName).putExtra("package_label", "HH tool"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))

    private fun openAutoStartSettings() = tryStart(
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))

    private fun showGate(title: String, msg: String,
                         a1: Pair<String, () -> Unit>, a2: Pair<String, () -> Unit>? = null) {
        val b = android.app.AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg).setCancelable(false)
            .setPositiveButton(a1.first, null)
            .setNegativeButton("Thoát") { _, _ -> finishAffinity() }
        if (a2 != null) b.setNeutralButton(a2.first, null)
        val d = b.create()
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { a1.second() }
        if (a2 != null) {
            d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { a2.second() }
        }
        styleDialog(d)
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setTextColor(ACCENT_TEXT)
        gateDialog = d
    }

    private fun checkGate(): Boolean {
        gateDialog?.let { try { it.dismiss() } catch (ignored: Throwable) {} }
        gateDialog = null
        val bad = Gate.installedConflicts(this)
        val needBattery = !Gate.batteryFree(this)
        val needAuto = Gate.autoStart(this) == 0
        if (bad.isEmpty() && !needBattery && !needAuto) return true

        GuardJob.cancel(this)
        try { stopService(Intent(this, GuardService::class.java)) } catch (ignored: Throwable) {}
        if (bad.isNotEmpty()) {
            val list = bad.joinToString("\n") { "• ${appLabel(it)} ($it)" }
            showGate("Cần gỡ ứng dụng xung đột",
                "Các ứng dụng sau đang cài trên máy. Hãy gỡ chúng rồi mở lại HH tool:\n\n$list",
                "Gỡ cài đặt" to {
                    tryStart(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${bad[0]}")))
                })
            return false
        }
        val sb = StringBuilder("HH Tool chỉ chạy khi:\n")
        if (needBattery) sb.append("• Tiết kiệm pin: đặt \"Không hạn chế\"\n")
        if (needAuto) sb.append("• Tự khởi động: bật\n")
        sb.append("\nBật xong quay lại app, hộp thoại này sẽ tự biến mất.")
        val acts = mutableListOf<Pair<String, () -> Unit>>()
        if (needBattery) acts.add("Cài đặt pin" to { openPowerSettings() })
        if (needAuto) acts.add("Tự khởi động" to { openAutoStartSettings() })
        showGate("Cần bật quyền để dùng HH tool", sb.toString(), acts[0], acts.getOrNull(1))
        return false
    }

    private fun shizukuAlive(): Boolean = try { Shizuku.pingBinder() } catch (ignored: Throwable) { false }

    private fun updateStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatusMs < 5000 && lastStatusText.isNotEmpty()) {
            tvStatus.text = lastStatusText
            tvHealth.text = lastHealthText
            return
        }
        thread {
            val srv = HhServer.ping(this)
            val mode = backendMode()
            val adbTxt = "ADB tích hợp (" +
                    (if (AdbBackend.isConnected(this)) "đã kết nối ✅" else "chưa kết nối") + ")"
            val shizTxt = if (shizukuAlive()) "Shizuku đang chạy" else "Shizuku chưa chạy ❌"
            val srvTxt = if (srv) "Máy chủ shell HH tool đang chạy ✅" else "Máy chủ shell chưa chạy ❌"
            val txt = when (mode) {
                "shizuku" -> "Chế độ (chọn tay): $shizTxt"
                "server" -> "Chế độ (chọn tay): $srvTxt"
                "adb" -> "Chế độ (chọn tay): $adbTxt"
                else -> "Chế độ: " + (if (shizukuAlive()) shizTxt else if (srv) srvTxt else adbTxt)
            }
            val health = try { healthText(srv) } catch (e: Throwable) { "" }
            lastStatusMs = System.currentTimeMillis()
            lastStatusText = txt
            lastHealthText = health
            runOnUiThread { tvStatus.text = txt; tvHealth.text = health }
        }
    }

    private fun healthText(serverUp: Boolean): String {
        val sb = StringBuilder()
        val raw = try { Settings.System.getString(contentResolver, Guard.KEY) } catch (e: Throwable) { null }
        val have = (raw ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val miss = Guard.wanted(this).filter { it !in have }
        sb.append(if (miss.isEmpty()) "Millet: ✅ đủ ${have.size} gói" else "Millet: ⚠ thiếu ${miss.size} gói").append('\n')

        val bad = mutableListOf<String>()
        var any = false
        for (t in FreezeStore.TABLES) {
            val l = FreezeStore.parse(FreezeStore.read(this, t))
            if (l.isEmpty()) continue
            any = true
            val mm = FreezeStore.REQUIRED.filter { it !in l }
            if (mm.isNotEmpty()) bad += "$t thiếu ${mm.joinToString("/") { it.substringAfterLast('.') }}"
        }
        sb.append(when {
            !any -> "Freeze: (chưa có khóa)"
            bad.isEmpty() -> "Freeze✅"
            else -> "Freeze: ⚠ " + bad.joinToString("; ")
        }).append('\n')

        sb.append("GuardJob: ").append(if (GuardJob.isScheduled(this)) "✅ Đã đăng ký" else "❌ Chưa đăng ký")
            .append("  |  Máy chủ shell: ").append(if (serverUp) "✅ Chạy" else "❌ Tắt").append('\n')

        val log = Guard.overrideLog(this)
        if (log.isEmpty()) sb.append("Ghi đè: chưa có lần nào")
        else {
            val day = log.count { it.first > System.currentTimeMillis() - 24 * 3600_000L }
            val t = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.US).format(java.util.Date(log.last().first))
            sb.append("Ghi đè gần nhất: $t ($day lần/24h)")
        }
        return sb.toString()
    }

    // Chế độ kết nối chọn tay: auto | shizuku | server | adb
    private fun backendMode(): String = prefs.getString("backend_mode", "auto") ?: "auto"

    private fun modeName(m: String) = when (m) {
        "shizuku" -> "Shizuku"
        "server" -> "Máy chủ shell"
        "adb" -> "ADB tích hợp"
        else -> "Tự động"
    }

    private fun showBackendMode() {
        val modes = arrayOf("auto", "shizuku", "server", "adb")
        val labels = arrayOf(
            "Tự động (Shizuku → Máy chủ shell → ADB)",
            "Shizuku",
            "Máy chủ shell",
            "ADB tích hợp")
        val d = android.app.AlertDialog.Builder(this)
            .setTitle("Chế độ kết nối")
            .setSingleChoiceItems(labels, modes.indexOf(backendMode()).coerceAtLeast(0)) { dlg, i ->
                prefs.edit().putString("backend_mode", modes[i]).apply()
                lastStatusMs = 0L
                updateStatus()
                toast("Đã chọn: ${modeName(modes[i])}")
                dlg.dismiss()
            }
            .setNegativeButton("Đóng", null)
            .create()
        d.show()
        styleDialog(d)
    }

    private fun shizukuBackend(): ((String) -> String)? {
        if (!shizukuAlive()) {
            log("Shizuku không chạy.\nMở app Shizuku để bật, hoặc đổi chế độ ở nút Chế độ kết nối.")
            return null
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                Shizuku.requestPermission(1)
                toast("Hãy cho phép Shizuku rồi bấm lại")
            }
            return null
        }
        return { s -> shizukuSh(s) }
    }

    private fun serverBackend(): ((String) -> String)? {
        if (!HhServer.ping(this)) {
            log("Máy chủ shell chưa chạy.\nBấm nút Máy chủ shell (bật/tắt) để bật, hoặc đổi chế độ ở nút Chế độ kết nối.")
            return null
        }
        return { s -> HhServer.exec(this, s) ?: "ERR ${HhServer.lastError}" }
    }

    private fun adbBackend(): ((String) -> String)? {
        if (!AdbBackend.ensureConnected(this)) {
            log("Chưa kết nối ADB.\nBấm GHÉP NỐI ADB (lần đầu) rồi KẾT NỐI ADB.\n" +
                    AdbBackend.lastError)
            return null
        }
        return { s -> AdbBackend.run(this, s) }
    }

    private fun backend(): ((String) -> String)? {
        if (License.local(this) != License.State.ACTIVE) { runOnUiThread { goLock() }; return null }
        return when (backendMode()) {
            "shizuku" -> shizukuBackend()
            "server" -> serverBackend()
            "adb" -> adbBackend()
            else -> {
                if (shizukuAlive()) return shizukuBackend()
                if (HhServer.ping(this)) return serverBackend()
                adbBackend()
            }
        }
    }

    private fun shizukuSh(cmd: String): String = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
        p.waitFor(); out
    } catch (e: Exception) { "ERR ${e.message}" }

    private fun connectAdb() {
        tvLog.text = "Đang kết nối ADB...\n"
        thread {
            val ok = AdbBackend.ensureConnected(this)
            log(if (ok) "Đã kết nối ADB ✅" else
                "Kết nối thất bại. Hãy bật Gỡ lỗi không dây (cần Wi-Fi) và ghép nối trước.\n" +
                        AdbBackend.lastError)
            updateStatus(true)
            if (ok && !Guard.canWrite(this)) {
                runOnUiThread { grantMany(listOf(AppItem("HH tool", packageName, false, -1)), remember = false) }
            }
        }
    }

    private fun toggleServer() {
        tvLog.text = "Đang xử lý máy chủ shell...\n"
        thread {
            if (HhServer.ping(this)) {
                HhServer.stop(this)
                Thread.sleep(500)
                log("Đã tắt máy chủ shell.")
                updateStatus(true)
                return@thread
            }
            val run = backend() ?: return@thread
            val ok = HhServer.start(this, run)
            log(if (ok) "✅ Máy chủ shell đang chạy.\nVẫn cần giữ gỡ lỗi USB/không dây, " +
                    "máy chủ vẫn chạy đến khi khởi động lại máy."
            else "❌ ${HhServer.lastError}")
            updateStatus(true)
        }
    }

    private fun startPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            toast("Hãy cho phép thông báo rồi bấm lại")
            return
        }
        val canOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        pairHost = ""
        etCode.setText(""); etPort.setText("")
        pairBox.visibility = View.VISIBLE
        tvLog.text = "Các bước:\n" +
                "1. Trong Tùy chọn nhà phát triển, bật Gỡ lỗi không dây.\n" +
                "2. Bấm \"Ghép nối thiết bị bằng mã ghép nối\".\n" +
                "3. Gõ mã 6 số vào ô \"Mã ghép nối\" ở đầu màn hình HH tool rồi bấm Ghép nối.\n" +
                "(Nên bật chia đôi màn hình: Cài đặt một nửa, HH tool một nửa.)\n" +
                "Đang chờ cổng ghép nối...\n"
        stopDiscovery()
        val nsd = getSystemService(NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { log("Lỗi dò dịch vụ: $e") }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(i: NsdServiceInfo?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val host = r.host?.hostAddress ?: return
                        pairHost = host
                        runOnUiThread {
                            etPort.setText(r.port.toString())
                            etCode.requestFocus()
                        }
                        Notif.askCode(this@MainActivity, host, r.port)
                        val split = Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
                        if (canOverlay && !split) PairOverlay.show(applicationContext, host, r.port)
                        log("Đã thấy cổng ghép nối ${r.port}. Gõ mã 6 số vào ô ở đầu màn hình HH tool.")
                        stopDiscovery()
                    }
                })
            }
        }
        nsdListener = l
        try {
            nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) { log("Không dò được: ${e.message}") }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        } catch (ignored: Exception) {}
    }

    private fun doPairInline() {
        val code = etCode.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull()
        val host = pairHost.ifEmpty { "127.0.0.1" }
        if (code.length != 6) { toast("Cần đủ 6 số"); return }
        if (port == null || port < 1024) { toast("Nhập cổng đúng"); return }
        tvLog.text = "Đang ghép nối...\n"
        thread {
            val ok = AdbBackend.pair(applicationContext, host, port, code)
            if (!ok) {
                log("❌ Ghép nối thất bại: ${AdbBackend.lastError}\n" +
                    "Mã và cổng đổi mỗi lần mở hộp. Bấm lại Ghép nối ADB rồi nhập mã mới.")
                return@thread
            }
            val conn = AdbBackend.ensureConnected(applicationContext)
            log(if (conn) "✅ Ghép nối và kết nối thành công"
                else "Đã ghép nối. Bấm KẾT NỐI ADB")
            runOnUiThread { pairBox.visibility = View.GONE; etCode.setText("") }
            updateStatus(true)
        }
    }

    private fun stopDiscovery() {
        val l = nsdListener ?: return
        nsdListener = null
        try { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(l) } catch (ignored: Exception) {}
    }

    private fun rememberGranted(pkgs: List<String>) {
        val set = prefs.getStringSet("pinned", emptySet())!!.toMutableSet()
        set.addAll(pkgs)
        prefs.edit().putStringSet("pinned", set).apply()
    }

    private fun forgetGranted(pkg: String) {
        val set = prefs.getStringSet("pinned", emptySet())!!.toMutableSet()
        set.remove(pkg)
        prefs.edit().putStringSet("pinned", set).apply()
        refreshPinned()
    }

    private fun refreshPinned() {
        pinnedBox.removeAllViews()
        val set = prefs.getStringSet("pinned", emptySet())!!
        all.filter { it.pkg in set }.forEach { app ->
            pinnedBox.addView(blueButton("⚡ ${app.label}") { showTab(2); grantMany(listOf(app), remember = false) }.apply {
                background = shape(BLUE, false); stateListAnimator = null
                setOnLongClickListener { forgetGranted(app.pkg); toast("Đã bỏ ${app.label} khỏi danh sách"); true }
            })
        }
    }

    private fun startGuard() {
        if (!Gate.ok(this)) return
        if (GuardJob.schedule(this)) {
            try { stopService(Intent(this, GuardService::class.java)) } catch (e: Exception) {}
        } else {
            try { startForegroundService(Intent(this, GuardService::class.java)) } catch (e: Exception) {}
        }
    }

    private fun freezerInfo(): String {
        val key = "cloud_memory_freeze_whitelist"
        val sb = StringBuilder("\n❄ $key:\n")
        val tables = listOf(
            "system" to { Settings.System.getString(contentResolver, key) },
            "global" to { Settings.Global.getString(contentResolver, key) },
            "secure" to { Settings.Secure.getString(contentResolver, key) })
        for ((name, get) in tables) {
            val v = try { get() } catch (e: Throwable) { "(lỗi: ${e.message})" }
            sb.append("• $name: ").append(if (v.isNullOrEmpty()) "(không có)" else v).append("\n")
        }
        val ex = FreezeStore.extra(this); val xc = FreezeStore.exclude(this)
        if (ex.isNotEmpty()) sb.append("➕ thêm tay: ").append(ex.sorted().joinToString(", ")).append("\n")
        if (xc.isNotEmpty()) sb.append("🚫 loại trừ: ").append(xc.sorted().joinToString(", ")).append("\n")
        return sb.toString()
    }

    private fun showMillet() {
        val raw = try { Settings.System.getString(contentResolver, Guard.KEY) } catch (e: Throwable) { null }
        val list = (raw ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) {
            tvLog.text = "MILLET_NO_RESTRICT_APP đang trống (hoặc không đọc được).\n" +
                    "Có thể PowerKeeper đã xóa. Mở lại app để tự ghi lại." + freezerInfo()
            return
        }
        val want = Guard.wanted(this).toSet()
        val sb = StringBuilder("Millet đang có ${list.size} gói:\n\n")
        for (pkg in list) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { null }
            val tag = if (pkg in want) "🛡" else "•"
            sb.append("$tag ${label ?: "(chưa cài)"}\n   $pkg\n")
        }
        val missing = want.filter { it !in list }
        if (missing.isNotEmpty()) {
            sb.append("\n⚠ Đang thiếu so với danh sách cần giữ:\n")
            missing.forEach { sb.append("   $it\n") }
        }
        sb.append("\n🛡 = gói Granter giữ (mặc định, đã ghim, đã sửa)")
        sb.append(freezerInfo())
        tvLog.text = sb.toString()
    }

    private fun showFreezeManager() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val et = EditText(this).apply {
            hint = "com.example.app"; setSingleLine(); setTextColor(DARK)
        }
        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(ACCENT_TEXT)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }
        fun section(head: String, items: Set<String>, onRemove: (String) -> Unit) {
            box.addView(title(head))
            if (items.isEmpty()) box.addView(TextView(this).apply { text = "(trống)"; setTextColor(DARK) })
            items.sorted().forEach { p ->
                box.addView(TextView(this).apply {
                    text = "✕  $p"; textSize = 14f; setTextColor(DARK)
                    setPadding(0, dp(8), 0, dp(8))
                    setOnClickListener { onRemove(p) }
                })
            }
        }
        fun render() {
            box.removeAllViews()
            val extra = FreezeStore.extra(this)
            val ex = FreezeStore.exclude(this)
            box.addView(TextView(this).apply {
                text = "Luôn có: ${FreezeStore.REQUIRED.joinToString(", ")} (không loại trừ được)"
                textSize = 13f; setTextColor(DARK)
            })
            section("Gói thêm tay (luôn nối vào), chạm để bỏ", extra) {
                FreezeStore.save(this, extra - it, ex); render()
            }
            section("Loại trừ (luôn gỡ, không nối lại), chạm để bỏ", ex) {
                FreezeStore.save(this, extra, ex - it); render()
            }
            box.addView(title("Thêm gói"))
            (et.parent as? ViewGroup)?.removeView(et)
            box.addView(et)
        }
        fun add(toExclude: Boolean) {
            val p = et.text.toString().trim()
            if (!FreezeStore.isValid(p)) { toast("Tên gói không hợp lệ"); return }
            val extra = FreezeStore.extra(this)
            val ex = FreezeStore.exclude(this)
            if (toExclude) {
                if (p in FreezeStore.REQUIRED) { toast("GMS và Zalo bắt buộc giữ"); return }
                FreezeStore.save(this, extra - p, ex + p)
            } else {
                FreezeStore.save(this, extra + p, ex - p)
            }
            et.setText(""); render()
        }
        render()
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(blueButton("Thêm vào giữ") { add(false) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(blueButton("Loại trừ") { add(true) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); addView(box)
            })
            addView(btns)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Danh sách freeze")
            .setView(outer)
            .setPositiveButton("Áp dụng ngay") { _, _ ->
                tvLog.text = "Đang cập nhật danh sách freeze...\n"
                thread {
                    try { Guard.applyFreeze(this) } catch (e: Throwable) {}
                    log("Đã cập nhật.\n" + freezerInfo())
                }
            }
            .setNegativeButton("Đóng", null)
            .show().also { styleDialog(it) }
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s giây"
            s < 3600 -> "${s / 60} phút"
            else -> "${s / 3600} giờ ${(s % 3600) / 60} phút"
        }
    }

    private fun showOverrideLog() {
        val log = Guard.overrideLog(this).reversed()
        val fmt = java.text.SimpleDateFormat("EEE dd/MM HH:mm:ss", java.util.Locale("vi"))
        val sb = StringBuilder()
        if (log.isEmpty()) {
            sb.append("Chưa ghi nhận lần ghi đè nào.\n\nMỗi khi hệ thống ghi đè Millet hoặc danh sách freeze và app nối lại, giờ sẽ được lưu ở đây (cần bật Tự động giữ).")
        } else {
            val gaps = log.map { it.first }.sorted().zipWithNext { a, b -> b - a }
            sb.append("Tổng ${log.size} lần (giữ tối đa 100)\n")
            if (gaps.isNotEmpty()) sb.append("Trung bình ${fmtDur(gaps.average().toLong())} một lần\n")
            sb.append("\n")
            log.take(40).forEach { sb.append("• ${fmt.format(java.util.Date(it.first))}  ${it.second}\n") }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Nhật ký ghi đè")
            .setMessage(sb.toString())
            .setPositiveButton("Đóng", null)
            .setNegativeButton("Xóa nhật ký") { _, _ -> Guard.clearOverrideLog(this); updateStatus() }
            .show().also { styleDialog(it) }
    }

    private val REQ_EXPORT = 4101
    private val REQ_IMPORT = 4102

    private fun showBackupMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Sao lưu / khôi phục")
            .setItems(arrayOf("Sao lưu ra file", "Khôi phục từ file (thay thế cấu hình hiện tại)")) { _, i ->
                try {
                    if (i == 0) startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "granter-backup.json"),
                        REQ_EXPORT)
                    else startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"), REQ_IMPORT)
                } catch (e: Exception) { toast("Không mở được trình chọn file") }
            }
            .setNegativeButton("Hủy", null)
            .show().also { styleDialog(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> exportConfig(uri)
            REQ_IMPORT -> importConfig(uri)
        }
    }

    private fun exportConfig(uri: android.net.Uri) {
        thread {
            try {
                val j = org.json.JSONObject()
                j.put("app", "granter")
                j.put("version", 1)
                j.put("pinned", org.json.JSONArray(prefs.getStringSet("pinned", emptySet())!!.sorted()))
                j.put("fixed", org.json.JSONArray(prefs.getStringSet("fixed", emptySet())!!.sorted()))
                j.put("freeze_extra", org.json.JSONArray(FreezeStore.extra(this).sorted()))
                j.put("freeze_exclude", org.json.JSONArray(FreezeStore.exclude(this).sorted()))
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(j.toString(2).toByteArray(Charsets.UTF_8)) }
                log("✅ Đã sao lưu: ${prefs.getStringSet("pinned", emptySet())!!.size} app đã cấp, " +
                        "${FreezeStore.extra(this).size} gói thêm tay, ${FreezeStore.exclude(this).size} gói loại trừ.")
            } catch (e: Throwable) { log("❌ Sao lưu lỗi: ${e.message}") }
        }
    }

    private fun importConfig(uri: android.net.Uri) {
        thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 512 * 1024) { log("❌ File quá lớn, không phải bản sao lưu Granter."); return@thread }
                val j = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                if (j.optString("app") != "granter" || j.optInt("version", 0) != 1) {
                    log("❌ File không phải bản sao lưu Granter."); return@thread
                }
                fun arr(k: String): Set<String> {
                    val a = j.optJSONArray(k) ?: return emptySet()
                    return (0 until a.length()).map { a.optString(it) }.filter { FreezeStore.isValid(it) }.toSet()
                }
                val pinned = arr("pinned"); val fixed = arr("fixed")
                val extra = arr("freeze_extra"); val exclude = arr("freeze_exclude")
                prefs.edit().putStringSet("pinned", pinned).putStringSet("fixed", fixed).apply()
                FreezeStore.save(this, extra, exclude)
                try { Guard.applyFreeze(this) } catch (e: Throwable) {}
                runOnUiThread { refreshPinned() }
                updateStatus()
                log("✅ Đã khôi phục: ${pinned.size} app đã cấp, ${fixed.size} app sửa hàng loạt, " +
                        "${extra.size} gói thêm tay, ${exclude.size} gói loại trừ.\n" +
                        "Lưu ý: quyền của từng app chưa được cấp lại, bấm ⚡ ở từng app (hoặc Cấp full quyền).")
            } catch (e: Throwable) { log("❌ Khôi phục lỗi: ${e.message}") }
        }
    }

    private fun openWriteSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) { toast("Không mở được trang cấp quyền") }
    }

    private fun enableGuard(auto: Boolean = false) {
        tvLog.text = "Đang bật tự động giữ...\n"
        thread {
            if (!Guard.hasSecure(this) && (shizukuAlive() || HhServer.ping(this) || AdbBackend.isConnected(this))) {
                val run = backend()
                run?.invoke("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            }
            if (!Guard.canWrite(this) && !HhServer.ping(this)) {
                if (auto) {
                    log("Tự động giữ chưa bật được: chưa có quyền ghi cài đặt.\n" +
                            "Bật Máy chủ shell (hoặc Kết nối ADB) rồi bấm ⚡ Cấp full quyền, từ sau mở app là tự chạy.")
                    return@thread
                }
                runOnUiThread { openWriteSettings() }
                log("Cần bật quyền \"Sửa đổi cài đặt hệ thống\" cho HH tool.\n" +
                        "Đã mở trang cấp quyền: bật xong thì quay lại app (tự động giữ sẽ tự bật).\n" +
                        "(Không cần bật gỡ lỗi USB.)")
                return@thread
            }
            if (!Guard.applyMillet(this)) {
                log("Có quyền nhưng không ghi được MILLET_NO_RESTRICT_APP.\n" +
                        "Lỗi thật: ${Guard.lastError}\n" +
                        "WRITE_SECURE_SETTINGS=${Guard.hasSecure(this)}, WRITE_SETTINGS=${Guard.hasWriteSettings(this)}\n" +
                        "Có thể HyperOS chặn ghi khóa này bằng quyền thường. " +
                        "Hãy cấp WRITE_SECURE_SETTINGS qua ADB/Shizuku một lần.")
                return@thread
            }
            prefs.edit().putBoolean("guard_on", true).apply()
            Guard.fire(this)
            runOnUiThread { startGuard() }
            val mode = if (Guard.hasSecure(this)) "WRITE_SECURE_SETTINGS" else "Sửa đổi cài đặt hệ thống"
            log("✅ Đã bật tự động giữ (quyền: $mode).\n" +
                    "• Ghi Millet no-restrict cho Google + app đã ghim + HH tool\n" +
                    "• Tự động fix + FCM (" +
                    (if (GuardJob.supported() && GuardJob.schedule(applicationContext))
                        "Hệ thống tự vận hành✅"
                    else "dịch vụ nền có thông báo") + ")\n" +
                    "• Tự chạy lại sau khi khởi động máy\n\n" +
                    "Chúc mừng bạn.")
        }
    }

    private val GMS = "com.google.android.gms"

    private fun milletScript(pkgs: List<String>): String =
        "cur=\$(settings --user 0 get system MILLET_NO_RESTRICT_APP); " +
        "[ \"\$cur\" = null ] && cur=''; " +
        "for p in ${pkgs.joinToString(" ")}; do " +
        "case \",\$cur,\" in *\",\$p,\"*) ;; *) cur=\"\${cur:+\$cur,}\$p\";; esac; done; " +
        "settings --user 0 put system MILLET_NO_RESTRICT_APP \"\$cur\"; " +
        FreezeStore.script(this, Guard.freezeWanted(this)) +
        "echo \"MILLET_NO_RESTRICT_APP=\$(settings --user 0 get system MILLET_NO_RESTRICT_APP)\""

    private fun grantChecked() {
        val apps = all.filter { it.pkg in checked }
        if (apps.isEmpty()) { toast("Hãy tích chọn ít nhất 1 ứng dụng"); return }
        grantMany(apps, remember = true)
    }

    private fun grantMany(apps: List<AppItem>, remember: Boolean) {
        tvLog.text = "Đang cấp quyền cho ${apps.size} ứng dụng...\n"
        thread {
            val run = backend() ?: return@thread
            val report = StringBuilder()
            apps.forEachIndexed { i, app ->
                log("Đang cấp ${i + 1}/${apps.size}: ${app.label}\n\n$report")
                report.append(grantOne(app, run)).append("\n")
            }
            if (remember) {
                rememberGranted(apps.map { it.pkg })
                runOnUiThread { refreshPinned() }
            }
            log(report.toString())
        }
    }

    private fun grantOne(app: AppItem, run: (String) -> String): String {
        val pkg = app.pkg
        val cmds = mutableListOf<String>()
        try {
            packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.forEach {
                    if (it !in DANGEROUS_PERMS) cmds += "pm grant $pkg $it"
                }
        } catch (e: Throwable) {}
        appOps.forEach { cmds += "appops set $pkg $it allow" }
        cmds += "dumpsys deviceidle whitelist +$pkg"

        val script = cmds.joinToString("; ") { "echo \"@@ $it\"; $it 2>&1" }
        val out = run(script)

        var ok = 0
        val sb = StringBuilder()
        var cur: String? = null
        val body = StringBuilder()
        fun flush() {
            val c = cur ?: return
            val o = body.toString().trim()
            if (o.isEmpty() || o.startsWith("Added")) ok++
            else sb.append("• $c\n  → ${o.take(80)}\n")
            body.setLength(0)
        }
        for (line in out.lines()) {
            if (line.startsWith("@@ ")) { flush(); cur = line.substring(3) }
            else body.append(line).append('\n')
        }
        flush()

        val uid = try { packageManager.getApplicationInfo(pkg, 0).uid } catch (e: Throwable) { -1 }
        val ch = run("CLASSPATH=$packageCodePath app_process /system/bin " +
                "com.example.granter.ChannelFixer $pkg $uid 2>&1").trim()
        val ml = run(milletScript(listOf(pkg) + Guard.DEFAULT + packageName)).trim()

        return "✅ ${app.label}: $ok/${cmds.size} lệnh OK\n🔔 ${ch.take(200)}\n🛡 ${ml.take(200)}\n" +
                (if (sb.isNotEmpty()) "Lệnh bị bỏ qua (bình thường nếu quyền không áp dụng):\n$sb" else "")
    }

    private fun isSocial(a: AppItem) =
        a.category == ApplicationInfo.CATEGORY_SOCIAL || a.pkg in socialPkgs

    private fun isBank(a: AppItem) =
        bankKeys.any { normalize(a.pkg).contains(it) || normalize(a.label).contains(it) }

    private fun isGms(a: AppItem) =
        a.pkg == GMS || a.pkg == "app.revanced.android.gms" || a.pkg == "com.google.android.gsf"

    private fun fixTargets(): List<AppItem> = all.filter {
        it.pkg != packageName &&
                (((!it.system || it.pkg in socialPkgs) && (isSocial(it) || isBank(it))) || isGms(it))
    }

    private fun batteryFree(pkg: String): Boolean = try {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(pkg)
    } catch (e: Throwable) { false }

    private fun autoStartOk(pkg: String): Int = try {
        val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val uid = packageManager.getApplicationInfo(pkg, 0).uid
        val m = android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        if ((m.invoke(ops, 10008, uid, pkg) as Int) == 0) 1 else 0
    } catch (e: Throwable) { -1 }

    private fun confirmFixAll() {
        val n = fixTargets().size
        if (n == 0) { toast("Không thấy ứng dụng mạng xã hội / ngân hàng nào"); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cấp full quyền")
            .setMessage("Sẽ cấp toàn bộ quyền với 1 nhấn cho $n ứng dụng mạng xã hội, ngân hàng. Tiếp tục?")
            .setPositiveButton("Cấp") { _, _ -> fixAll() }
            .setNegativeButton("Hủy", null)
            .show().also { styleDialog(it) }
    }

    private fun fixAll() {
        val targets = fixTargets()
        tvLog.text = "Đang sửa ${targets.size} ứng dụng...\n"
        thread {
            val run = backend() ?: return@thread
            val done = StringBuilder()
            targets.forEachIndexed { i, app ->
                log("Đang sửa ${i + 1}/${targets.size}: ${app.label}\n\n$done")
                val pkg = app.pkg
                val cmds = mutableListOf<String>()
                try {
                    packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.forEach {
                            if (it !in DANGEROUS_PERMS) cmds += "pm grant $pkg $it"
                        }
                } catch (e: Throwable) {}
                appOps.forEach { cmds += "appops set $pkg $it allow" }
                cmds += "dumpsys deviceidle whitelist +$pkg"
                cmds += "cmd deviceidle whitelist +$pkg"
                cmds += "am set-standby-bucket $pkg active"
                cmds += "cmd package set-stopped-state --user 0 $pkg false"
                run(cmds.joinToString("; ") { "$it >/dev/null 2>&1" })
                try {
                    val uid = packageManager.getApplicationInfo(pkg, 0).uid
                    run("CLASSPATH=$packageCodePath app_process /system/bin " +
                            "com.example.granter.ChannelFixer $pkg $uid 2>&1")
                } catch (e: Throwable) {}
                val bat = if (batteryFree(pkg)) "✔" else "✘"
                val au = when (autoStartOk(pkg)) { 1 -> "✔"; 0 -> "✘"; else -> "?" }
                done.append("${app.label}  | pin: $bat  | tự khởi chạy: $au\n")
            }
            val pkgs = targets.map { it.pkg }
            val ml = run(milletScript(pkgs + Guard.DEFAULT + packageName)).trim()
            val fixed = prefs.getStringSet("fixed", emptySet())!!.toMutableSet()
            fixed.addAll(pkgs)
            prefs.edit().putStringSet("fixed", fixed).apply()
            
            if (!Guard.canWrite(this)) run("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            val guardOn = Guard.canWrite(this)
            if (guardOn) {
                prefs.edit().putBoolean("guard_on", true).apply()
                Guard.fire(this)
                runOnUiThread { startGuard() }
            }
            log("✅ Đã sửa ${targets.size} ứng dụng\n🛡 ${ml.take(200)}\n" +
                    (if (guardOn) "🔒 Đã bật tự động fix\n\n"
                    else "⚠ Chưa bật được tự động fix (thiếu quyền ghi cài đặt hệ thống). Bật Máy chủ shell hoặc Kết nối ADB rồi mở lại app.\n\n") +
                    done)
        }
    }

    private fun log(s: String) = runOnUiThread { tvLog.text = s }
    // ===== Đặt ngôn ngữ hệ thống = vi-VN =====
    private fun setLocaleVi() {
        tvLog.text = "Đang đặt system_locales = vi-VN...\n"
        thread {
            val run = backend() ?: return@thread
            val before = run("settings get system system_locales").trim()
            val res = run("settings put system system_locales vi-VN").trim()
            val after = run("settings get system system_locales").trim()
            val prop = run("getprop persist.sys.locale").trim()
            val ok = after.startsWith("vi-VN")
            log((if (ok) "✅ Đã đặt system_locales = $after" else "❌ Không đặt được (đang là: $after)") +
                    "\nTrước đó: $before" +
                    (if (res.isNotEmpty()) "\nLệnh trả về: $res" else "") +
                    "\npersist.sys.locale hiện tại: ${prop.ifEmpty { "(trống)" }}" +
                    (if (ok) "\nNếu giao diện hệ thống chưa đổi, hãy khởi động lại máy." else ""))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
