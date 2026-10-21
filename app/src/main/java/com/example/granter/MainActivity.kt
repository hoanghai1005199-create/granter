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

    private fun matches(a: AppItem): Boolean = when (mode) {
        "user" -> !a.system
        "social" -> a.category == ApplicationInfo.CATEGORY_SOCIAL || a.pkg in socialPkgs
        "bank" -> bankKeys.any { a.pkg.lowercase().contains(it) || a.label.lowercase().contains(it) }
        else -> true
    }

    private val BG = Color.parseColor("#FEF4F7")
    private val BLUE = Color.parseColor("#F2A0BC")
    private val ACCENT_TEXT = Color.parseColor("#B04A6E")
    private val BAR = Color.parseColor("#FBD2E0")
    private val DARK = Color.parseColor("#6D4C5B")

    private var selected: AppItem? = null
    private lateinit var tvSelected: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pinnedBox: LinearLayout
    private lateinit var all: List<AppItem>
    private val prefs by lazy { getSharedPreferences("granter", MODE_PRIVATE) }
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private lateinit var pairBox: LinearLayout
    private lateinit var etCode: EditText
    private lateinit var etPort: EditText
    @Volatile private var pairHost: String = ""

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

    /** Nút tròn có biểu tượng, nhãn nằm bên dưới (giống màn hình Tính năng). */
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

    /** Hộp thoại bo góc, nền hồng, chữ/nút cùng tông với giao diện. */
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
        pinnedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply { hint = "Tìm ứng dụng..." }
        val list = ListView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        tvSelected = TextView(this).apply {
            text = "Chưa chọn ứng dụng"; textSize = 15f; setTextColor(DARK)
        }
        val none = { toast("Hãy chọn ứng dụng trước") }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(circleRow(
                circleButton("🔑", "Cấp quyền") { selected?.let { grantAll(it) } ?: none() },
                circleButton("📌", "Ghim / Bỏ ghim") { selected?.let { togglePin(it.pkg) } ?: none() },
                circleButton("🔗", "Ghép nối ADB") { startPairing() },
                circleButton("📶", "Kết nối ADB") { connectAdb() }))
            addView(circleRow(
                circleButton("🛡", "Millet không giới hạn") { selected?.let { milletOnly(it) } ?: none() },
                circleButton("🔔", "Đánh thức FCM") { kickFcm(selected?.pkg) },
                circleButton("🔒", "Bật tự động giữ") { enableGuard() },
                circleButton("🔓", "Tắt tự động") { disableGuard() }))
            addView(circleRow(
                circleButton("📡", "Kiểm tra FCM") { FcmCheck.open(this@MainActivity) },
                circleButton("📋", "Xem Millet") { showMillet() },
                circleButton("⚡", "Cấp full quyền chỉ với 1 nhấn") { confirmFixAll() },
                circleButton("🖥", "Máy chủ shell (bật/tắt)") { toggleServer() }))
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
        root.addView(pairBox)
        root.addView(TextView(this).apply {
            text = "Ứng dụng đã ghim (bấm 1 lần là xong)"; textSize = 14f; setTextColor(DARK)
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
                    val view = v ?: layoutInflater.inflate(
                        android.R.layout.simple_list_item_2, g, false)
                    view.findViewById<TextView>(android.R.id.text1).apply {
                        text = items[p].label
                        setTextColor(Color.parseColor("#E53935"))
                    }
                    view.findViewById<TextView>(android.R.id.text2).text = items[p].pkg
                    return view
                }
            }
            list.setOnItemClickListener { _, _, i, _ ->
                selected = items[i]
                tvSelected.text = "Đã chọn: ${items[i].label} (${items[i].pkg})"
            }
        }
        refreshList = { show(search.text.toString()) }
        refreshList()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = show(s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refreshPinned()
    }

    private fun goLock() {
        startActivity(Intent(this, LockActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (License.local(this) != License.State.ACTIVE) { goLock(); return }
        thread {
            if (License.refreshIfDue(applicationContext) != License.State.ACTIVE) runOnUiThread { goLock() }
        }
        updateStatus()
        if (prefs.getBoolean("guard_on", false) && Guard.canWrite(this)) startGuard()
    }

    // ---------- Trạng thái / backend ----------
    private fun shizukuAlive(): Boolean = try { Shizuku.pingBinder() } catch (ignored: Throwable) { false }

    private fun updateStatus() {
        thread {
            val txt = if (shizukuAlive()) "Chế độ: Shizuku đang chạy"
            else if (HhServer.ping(this)) "Chế độ: Máy chủ shell HH tool đang chạy ✅"
            else "Chế độ: ADB tích hợp (" +
                    (if (AdbBackend.isConnected(this)) "đã kết nối ✅" else "chưa kết nối") + ")"
            runOnUiThread { tvStatus.text = txt }
        }
    }

    /** Trả về hàm chạy lệnh shell, hoặc null (đã ghi lý do vào log). Gọi trong thread nền. */
    private fun backend(): ((String) -> String)? {
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

    // ---------- Ghép nối / kết nối ADB ----------
    private fun connectAdb() {
        tvLog.text = "Đang kết nối ADB...\n"
        thread {
            val ok = AdbBackend.ensureConnected(this)
            log(if (ok) "Đã kết nối ADB ✅" else
                "Kết nối thất bại. Hãy bật Gỡ lỗi không dây (cần Wi-Fi) và ghép nối trước.\n" +
                        AdbBackend.lastError)
            updateStatus()
            // Lần đầu kết nối: tự cấp đủ quyền cho chính HH tool (giống nút CẤP QUYỀN)
            if (ok && !Guard.canWrite(this)) {
                runOnUiThread { grantAll(AppItem("HH tool", packageName, false, -1)) }
            }
        }
    }

    /** Bật/tắt máy chủ shell. Bật lần đầu cần Shizuku hoặc ADB đang kết nối. */
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
            updateStatus()
        }
    }

    private fun stopDiscovery() {
        val l = nsdListener ?: return
        nsdListener = null
        try { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(l) } catch (ignored: Exception) {}
    }

    // ---------- Ghim ----------
    private fun togglePin(pkg: String) {
        val set = prefs.getStringSet("pinned", emptySet())!!.toMutableSet()
        if (!set.add(pkg)) set.remove(pkg)
        prefs.edit().putStringSet("pinned", set).apply()
        refreshPinned()
    }

    private fun refreshPinned() {
        pinnedBox.removeAllViews()
        val set = prefs.getStringSet("pinned", emptySet())!!
        all.filter { it.pkg in set }.forEach { app ->
            pinnedBox.addView(blueButton("⚡ ${app.label}") { grantAll(app) }.apply {
                background = shape(BLUE, false); stateListAnimator = null
            })
        }
    }

    // ---------- Tự động giữ ----------
    private fun startGuard() {
        try { startForegroundService(Intent(this, GuardService::class.java)) } catch (e: Exception) {}
    }

    /** Đọc danh sách MILLET_NO_RESTRICT_APP hiện tại (đọc không cần quyền đặc biệt). */
    private fun showMillet() {
        val raw = try { Settings.System.getString(contentResolver, Guard.KEY) } catch (e: Throwable) { null }
        val list = (raw ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) {
            tvLog.text = "MILLET_NO_RESTRICT_APP đang trống (hoặc không đọc được).\n" +
                    "Có thể PowerKeeper đã xóa. Bấm BẬT TỰ ĐỘNG GIỮ để ghi lại."
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
        tvLog.text = sb.toString()
    }

    private fun openWriteSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) { toast("Không mở được trang cấp quyền") }
    }

    private fun enableGuard() {
        tvLog.text = "Đang bật tự động giữ...\n"
        thread {
            // Có sẵn ADB/Shizuku thì cấp quyền ghi cài đặt bảo mật (bền hơn, giữ sau khi tắt gỡ lỗi USB)
            if (!Guard.hasSecure(this) && (shizukuAlive() || AdbBackend.isConnected(this))) {
                val run = backend()
                run?.invoke("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            }
            // Không có ADB: dùng quyền "Sửa đổi cài đặt hệ thống"
            if (!Guard.canWrite(this) && !HhServer.ping(this)) {
                runOnUiThread { openWriteSettings() }
                log("Cần bật quyền \"Sửa đổi cài đặt hệ thống\" cho HH tool.\n" +
                        "Đã mở trang cấp quyền: bật xong thì quay lại bấm BẬT TỰ ĐỘNG GIỮ lần nữa.\n" +
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
                    "• Bị ghi đè hoặc bật màn hình sẽ tự ghi lại và đá FCM\n" +
                    "• Tự chạy lại sau khi khởi động máy\n\n" +
                    "Nên đặt HH tool ở chế độ pin Không giới hạn.")
        }
    }

    private fun disableGuard() {
        prefs.edit().putBoolean("guard_on", false).apply()
        stopService(Intent(this, GuardService::class.java))
        tvLog.text = "Đã tắt tự động giữ."
    }

    // ---------- Millet / FCM ----------
    private val GMS = "com.google.android.gms"

    /** Thêm các gói vào danh sách MILLET_NO_RESTRICT_APP (HyperOS/MIUI), giữ nguyên các gói đã có. */
    private fun milletScript(pkgs: List<String>): String =
        "cur=\$(settings --user 0 get system MILLET_NO_RESTRICT_APP); " +
        "[ \"\$cur\" = null ] && cur=''; " +
        "for p in ${pkgs.joinToString(" ")}; do " +
        "case \",\$cur,\" in *\",\$p,\"*) ;; *) cur=\"\${cur:+\$cur,}\$p\";; esac; done; " +
        "settings --user 0 put system MILLET_NO_RESTRICT_APP \"\$cur\"; " +
        "echo \"MILLET_NO_RESTRICT_APP=\$(settings --user 0 get system MILLET_NO_RESTRICT_APP)\""

    private fun fcmScript(pkg: String?): String {
        val c = mutableListOf(
            "am broadcast -a com.google.android.intent.action.GTALK_HEARTBEAT -p $GMS",
            "am broadcast -a com.google.android.intent.action.MCS_HEARTBEAT -p $GMS",
            "dumpsys deviceidle whitelist +$GMS",
            "cmd appops set $GMS RUN_IN_BACKGROUND allow",
            "cmd appops set $GMS RUN_ANY_IN_BACKGROUND allow",
            "am set-standby-bucket $GMS active"
        )
        if (pkg != null) {
            c += "am set-standby-bucket $pkg active"
            c += "cmd package set-stopped-state --user 0 $pkg false"
        }
        return c.joinToString("; ") { "$it >/dev/null 2>&1" } +
                "; " + milletScript(listOfNotNull(pkg) + Guard.DEFAULT + packageName)
    }

    private fun milletOnly(app: AppItem) {
        tvLog.text = "Đang ghi Millet cho ${app.label}...\n"
        thread {
            val run = backend() ?: return@thread
            val out = run(milletScript(listOf(app.pkg) + Guard.DEFAULT + packageName)).trim()
            log("🛡 Millet: ${app.label}\n$out\n\n" +
                    "Lưu ý: PowerKeeper có thể ghi đè danh sách này. Nếu mất, bấm lại.")
        }
    }

    private fun kickFcm(pkg: String?) {
        tvLog.text = "Đang đánh thức FCM...\n"
        thread {
            val run = backend() ?: return@thread
            val out = run(fcmScript(pkg)).trim()
            log("📡 Đã đánh thức FCM (Google Play services" +
                    (if (pkg != null) " + $pkg" else "") + ")\n$out")
        }
    }

    // ---------- Cấp quyền ----------
    private fun grantAll(app: AppItem) {
        tvLog.text = "Đang cấp quyền cho ${app.label}...\n"
        thread {
            val run = backend() ?: return@thread
            val pkg = app.pkg
            val cmds = mutableListOf<String>()
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.forEach { cmds += "pm grant $pkg $it" }
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

            val uid = packageManager.getApplicationInfo(pkg, 0).uid
            val ch = run("CLASSPATH=$packageCodePath app_process /system/bin " +
                    "com.example.granter.ChannelFixer $pkg $uid 2>&1").trim()

            val ml = run(milletScript(listOf(pkg) + Guard.DEFAULT + packageName)).trim()

            log("✅ ${app.label}: $ok/${cmds.size} lệnh OK\n🔔 ${ch.take(200)}\n🛡 ${ml.take(200)}\n\n" +
                    "Lệnh bị bỏ qua (bình thường nếu quyền không áp dụng):\n$sb")
        }
    }

    // ---------- Sửa hàng loạt: mạng xã hội + ngân hàng ----------
    private fun isSocial(a: AppItem) =
        a.category == ApplicationInfo.CATEGORY_SOCIAL || a.pkg in socialPkgs

    private fun isBank(a: AppItem) =
        bankKeys.any { a.pkg.lowercase().contains(it) || a.label.lowercase().contains(it) }

    private fun fixTargets(): List<AppItem> = all.filter {
        it.pkg != packageName && (!it.system || it.pkg in socialPkgs) && (isSocial(it) || isBank(it))
    }

    /** Android đã ghi nhận app "không tối ưu pin" (whitelist) chưa. */
    private fun batteryFree(pkg: String): Boolean = try {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(pkg)
    } catch (e: Throwable) { false }

    /** Quyền tự khởi chạy kiểu Xiaomi: 1 = cho phép, 0 = chặn, -1 = không đọc được. */
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
            .setMessage("Sẽ cấp toàn bộ quyền (giống nút CẤP QUYỀN) cho $n ứng dụng mạng xã hội + ngân hàng, sửa thông báo, rồi bật tự động giữ Millet + FCM. Tiếp tục?")
            .setPositiveButton("Cấp") { _, _ -> fixAll() }
            .setNegativeButton("Hủy", null)
            .show().also { styleDialog(it) }
    }

    /** Chạy đúng bộ lệnh của nút CẤP QUYỀN cho từng app trong danh sách. */
    private fun fixAll() {
        val targets = fixTargets()
        tvLog.text = "Đang sửa ${targets.size} ứng dụng...\n"
        thread {
            val run = backend() ?: return@thread
            val done = StringBuilder()
            targets.forEachIndexed { i, app ->
                log("Đang sửa ${i + 1}/${targets.size}: ${app.label}\n\n$done")
                val pkg = app.pkg
                // Y hệt nút CẤP QUYỀN từng app
                val cmds = mutableListOf<String>()
                try {
                    packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.forEach { cmds += "pm grant $pkg $it" }
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
            // Bật luôn tự động giữ (giống nút BẬT TỰ ĐỘNG GIỮ)
            if (!Guard.canWrite(this)) run("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            val guardOn = Guard.canWrite(this)
            if (guardOn) {
                prefs.edit().putBoolean("guard_on", true).apply()
                Guard.fire(this)
                runOnUiThread { startGuard() }
            }
            log("✅ Đã sửa ${targets.size} ứng dụng\n🛡 ${ml.take(200)}\n" +
                    (if (guardOn) "🔒 Đã bật tự động giữ Millet + FCM\n\n"
                    else "⚠ Chưa bật được tự động giữ (thiếu quyền ghi cài đặt hệ thống). Kết nối ADB rồi bấm BẬT TỰ ĐỘNG GIỮ.\n\n") +
                    done)
        }
    }

    private fun log(s: String) = runOnUiThread { tvLog.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
