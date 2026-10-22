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
            setPadding(32, 48, 32, 32)
        }

        tvStatus = TextView(this).apply { textSize = 13f; setTextColor(DARK) }
        tvHealth = TextView(this).apply {
            textSize = 12f; setTextColor(DARK); setPadding(0, dp(4), 0, dp(4))
        }
        pinnedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply { hint = "Tìm ứng dụng..." }
        val list = ListView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        tvSelected = TextView(this).apply {
            text = "Chưa chọn ứng dụng"; textSize = 15f; setTextColor(DARK)
            setPadding(0, dp(6), 0, dp(6))
        }
        val none = { toast("Hãy tích chọn ít nhất 1 ứng dụng") }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(circleRow(
                circleButton("🔑", "Cấp quyền (các app đã tích)") { grantChecked() },
                circleButton("🔗", "Ghép nối ADB") { startPairing() },
                circleButton("📶", "Kết nối ADB") { connectAdb() }))
            addView(circleRow(
                circleButton("📡", "Kiểm tra FCM") { FcmCheck.open(this@MainActivity) },
                circleButton("📋", "Xem Millet") { showMillet() },
                circleButton("⚡", "Cấp full quyền chỉ với 1 nhấn") { confirmFixAll() },
                circleButton("🖥", "Máy chủ shell (bật/tắt)") { toggleServer() }))
            addView(circleRow(
                circleButton("❄", "Danh sách freeze") { showFreezeManager() },
                circleButton("📜", "Nhật ký ghi đè") { showOverrideLog() },
                circleButton("💾", "Sao lưu / khôi phục") { showBackupMenu() }))
        }

        tvLog = TextView(this).apply { setTextColor(DARK) }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 0.8f)
            addView(tvLog)
        }

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

        root.addView(tvStatus)
        root.addView(tvHealth)
        root.addView(pairBox)
        root.addView(TextView(this).apply {
            text = "Ứng dụng đã cấp (bấm 1 lần để cấp lại, nhấn giữ để bỏ)"; textSize = 14f; setTextColor(DARK)
        })
        root.addView(pinnedBox)

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
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(chipBar)
        })

        root.addView(search); root.addView(list)
        root.addView(tvSelected); root.addView(grid); root.addView(scroll)
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
        val sb = StringBuilder("HH tool chỉ chạy khi:\n")
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

    private fun updateStatus() {
        val now = System.currentTimeMillis()
        if (now - lastStatusMs < 5000 && lastStatusText.isNotEmpty()) {
            tvStatus.text = lastStatusText
            tvHealth.text = lastHealthText
            return
        }
        thread {
            val srv = HhServer.ping(this)
            val txt = if (shizukuAlive()) "Chế độ: Shizuku đang chạy"
            else if (srv) "Chế độ: Máy chủ shell HH tool đang chạy ✅"
            else "Chế độ: ADB tích hợp (" +
                    (if (AdbBackend.isConnected(this)) "đã kết nối ✅" else "chưa kết nối") + ")"
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
            bad.isEmpty() -> "Freeze: ✅ có GMS + Zalo"
            else -> "Freeze: ⚠ " + bad.joinToString("; ")
        }).append('\n')

        sb.append("GuardJob: ").append(if (GuardJob.isScheduled(this)) "✅ đã đăng ký" else "❌ chưa đăng ký")
            .append("  |  Máy chủ shell: ").append(if (serverUp) "✅ chạy" else "❌ tắt").append('\n')

        val log = Guard.overrideLog(this)
        if (log.isEmpty()) sb.append("Ghi đè: chưa có lần nào")
        else {
            val day = log.count { it.first > System.currentTimeMillis() - 24 * 3600_000L }
            val t = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.US).format(java.util.Date(log.last().first))
            sb.append("Ghi đè gần nhất: $t ($day lần/24h)")
        }
        return sb.toString()
    }

    private fun backend(): ((String) -> String)? {
        if (License.local(this) != License.State.ACTIVE) { runOnUiThread { goLock() }; return null }
        if (shizukuAlive()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread {
                    Shizuku.requestPermission(1)
                    toast("Hãy cho phép Shizuku rồi bấm lại")
                }
                return null
            }
            return { s -> shizukuSh(s) }
        }
        if (HhServer.ping(this)) {
            return { s -> HhServer.exec(this, s) ?: "ERR ${HhServer.lastError}" }
        }
        if (!AdbBackend.ensureConnected(this)) {
            log("Chưa kết nối ADB.\nBấm GHÉP NỐI ADB (lần đầu) rồi KẾT NỐI ADB.\n" +
                    AdbBackend.lastError)
            return null
        }
        return { s -> AdbBackend.run(this, s) }
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
            updateStatus()
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
                updateStatus()
                return@thread
            }
            val run = backend() ?: return@thread
            val ok = HhServer.start(this, run)
            log(if (ok) "✅ Máy chủ shell đang chạy.\nTừ giờ có thể tắt gỡ lỗi USB/không dây, " +
                    "máy chủ vẫn chạy đến khi khởi động lại máy."
            else "❌ ${HhServer.lastError}")
            updateStatus()
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
                        Notif.askCode(this@MainActivity, host
