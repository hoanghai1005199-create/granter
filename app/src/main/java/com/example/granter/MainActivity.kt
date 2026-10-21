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

    private val BG = Color.parseColor("#DCEBFF")
    private val BLUE = Color.parseColor("#1E88E5")
    private val BAR = Color.parseColor("#90CAF9")
    private val DARK = Color.parseColor("#0D47A1")

    private var selected: AppItem? = null
    private lateinit var tvSelected: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pinnedBox: LinearLayout
    private lateinit var all: List<AppItem>
    private val prefs by lazy { getSharedPreferences("granter", MODE_PRIVATE) }
    private var nsdListener: NsdManager.DiscoveryListener? = null

    private val appOps = listOf(
        "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS",
        "ACCESS_RESTRICTED_SETTINGS", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND",
        "USE_FULL_SCREEN_INTENT", "SCHEDULE_EXACT_ALARM", "POST_NOTIFICATION",
        "REQUEST_INSTALL_PACKAGES", "GET_USAGE_STATS"
    ) + listOf(10001, 10002, 10004, 10008, 10017, 10018, 10020, 10021, 10022,
        10023, 10033, 10037, 10045, 10048, 10049, 10053).map { it.toString() }

    private fun blueButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(BLUE)
        setOnClickListener { onClick() }
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
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(blueButton("CẤP QUYỀN") {
            selected?.let { grantAll(it) } ?: toast("Hãy chọn ứng dụng trước")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        row.addView(blueButton("GHIM / BỎ GHIM") {
            selected?.let { togglePin(it.pkg) } ?: toast("Hãy chọn ứng dụng trước")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(blueButton("GHÉP NỐI ADB") { startPairing() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        row2.addView(blueButton("KẾT NỐI ADB") { connectAdb() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(blueButton("MILLET KHÔNG GIỚI HẠN") {
            selected?.let { milletOnly(it) } ?: toast("Hãy chọn ứng dụng trước")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        row3.addView(blueButton("ĐÁNH THỨC FCM") { kickFcm(selected?.pkg) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(blueButton("BẬT TỰ ĐỘNG GIỮ") { enableGuard() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        row4.addView(blueButton("TẮT TỰ ĐỘNG") { disableGuard() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })

        val row5 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row5.addView(blueButton("KIỂM TRA KẾT NỐI FCM") { FcmCheck.open(this) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })

        val row6 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row6.addView(blueButton("SỬA TẤT CẢ + BẬT TỰ ĐỘNG GIỮ") { confirmFixAll() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })

        tvLog = TextView(this).apply { setTextColor(DARK) }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 0.8f)
            addView(tvLog)
        }

        root.addView(tvStatus)
        root.addView(TextView(this).apply {
            text = "Ứng dụng đã ghim (bấm 1 lần là xong)"; textSize = 14f; setTextColor(DARK)
        })
        root.addView(pinnedBox)

        val chips = mutableListOf<Pair<String, Button>>()
        var refreshList: () -> Unit = {}
        fun paintChips() = chips.forEach { (m, b) ->
            b.backgroundTintList = ColorStateList.valueOf(
                if (m == mode) DARK else Color.parseColor("#64B5F6"))
        }
        val chipBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("user" to "Của tôi", "social" to "Mạng xã hội",
            "bank" to "Ngân hàng", "all" to "Tất cả").forEach { (m, name) ->
            val b = Button(this).apply {
                text = name; isAllCaps = false; setTextColor(Color.WHITE)
                setOnClickListener { mode = m; paintChips(); refreshList() }
            }
            chips += m to b; chipBar.addView(b)
        }
        paintChips()
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(chipBar)
        })

        root.addView(search); root.addView(list)
        root.addView(tvSelected); root.addView(row); root.addView(row2); root.addView(row3); root.addView(row4); root.addView(row5); root.addView(row6); root.addView(scroll)
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
                    view.findViewById<TextView>(android.R.id.text1).text = items[p].label
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

    private fun startPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            toast("Hãy cho phép thông báo rồi bấm lại")
            return
        }
        tvLog.text = "Các bước:\n" +
                "1. Trong Tùy chọn nhà phát triển, bật Gỡ lỗi không dây.\n" +
                "2. Bấm \"Ghép nối thiết bị bằng mã ghép nối\".\n" +
                "3. Kéo thanh thông báo xuống, bấm \"Nhập mã ghép nối\" và gõ mã 6 số.\n" +
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
                        Notif.askCode(this@MainActivity, host, r.port)
                        log("Đã thấy cổng ghép nối ${r.port}. Kéo thông báo xuống để nhập mã.")
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
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (ignored: Exception) {}
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
            pinnedBox.addView(blueButton("⚡ ${app.label}") { grantAll(app) })
        }
    }

    // ---------- Tự động giữ ----------
    private fun startGuard() {
        try { startForegroundService(Intent(this, GuardService::class.java)) } catch (e: Exception) {}
    }

    private fun enableGuard() {
        tvLog.text = "Đang bật tự động giữ...\n"
        thread {
            if (!Guard.canWrite(this)) {
                val run = backend() ?: return@thread
                run("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            }
            if (!Guard.canWrite(this)) {
                log("Chưa cấp được quyền ghi cài đặt hệ thống.\nKết nối ADB (hoặc Shizuku) rồi bấm lại.\n" +
                        AdbBackend.lastError)
                return@thread
            }
            prefs.edit().putBoolean("guard_on", true).apply()
            Guard.fire(this)
            runOnUiThread { startGuard() }
            log("✅ Đã bật tự động giữ.\n" +
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

    private fun confirmFixAll() {
        val n = fixTargets().size
        if (n == 0) { toast("Không thấy ứng dụng mạng xã hội / ngân hàng nào"); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Sửa tất cả")
            .setMessage("Sẽ sửa thông báo và chạy nền cho $n ứng dụng mạng xã hội + ngân hàng, rồi bật tự động giữ Millet + FCM. Tiếp tục?")
            .setPositiveButton("Sửa") { _, _ -> fixAll() }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Bản "nhẹ": chỉ những gì liên quan thông báo / chạy nền, không cấp quyền SMS, vị trí, danh bạ... */
    private fun fixAll() {
        val targets = fixTargets()
        tvLog.text = "Đang sửa ${targets.size} ứng dụng...\n"
        thread {
            val run = backend() ?: return@thread
            val done = StringBuilder()
            targets.forEachIndexed { i, app ->
                log("Đang sửa ${i + 1}/${targets.size}: ${app.label}\n\n$done")
                val pkg = app.pkg
                val cmds = mutableListOf("pm grant $pkg android.permission.POST_NOTIFICATIONS")
                listOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "POST_NOTIFICATION",
                    "USE_FULL_SCREEN_INTENT", "SCHEDULE_EXACT_ALARM", "10008")
                    .forEach { cmds += "appops set $pkg $it allow" }
                cmds += "dumpsys deviceidle whitelist +$pkg"
                cmds += "cmd package set-stopped-state --user 0 $pkg false"
                run(cmds.joinToString("; ") { "$it >/dev/null 2>&1" })
                try {
                    val uid = packageManager.getApplicationInfo(pkg, 0).uid
                    run("CLASSPATH=$packageCodePath app_process /system/bin " +
                            "com.example.granter.ChannelFixer $pkg $uid 2>&1")
                } catch (e: Throwable) {}
                done.append("✓ ${app.label}\n")
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
