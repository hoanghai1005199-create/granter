package com.example.granter

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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

    private val BG = Color.parseColor("#DCEBFF")        // nền xanh dương nhạt
    private val BLUE = Color.parseColor("#1E88E5")      // nút xanh
    private val BAR = Color.parseColor("#90CAF9")

    private var selected: AppItem? = null
    private lateinit var tvSelected: TextView
    private lateinit var tvLog: TextView
    private lateinit var pinnedBox: LinearLayout
    private lateinit var all: List<AppItem>
    private val prefs by lazy { getSharedPreferences("granter", MODE_PRIVATE) }

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
        window.statusBarColor = BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(32, 48, 32, 32)
        }

        pinnedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply { hint = "Tìm ứng dụng..." }
        val list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        tvSelected = TextView(this).apply {
            text = "Chưa chọn ứng dụng"; textSize = 15f; setTextColor(Color.parseColor("#0D47A1"))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnGrant = blueButton("CẤP QUYỀN") {
            selected?.let { grantAll(it) } ?: toast("Hãy chọn ứng dụng trước")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val btnPin = blueButton("GHIM / BỎ GHIM") {
            selected?.let { togglePin(it.pkg) } ?: toast("Hãy chọn ứng dụng trước")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        row.addView(btnGrant); row.addView(btnPin)

        tvLog = TextView(this).apply { setTextColor(Color.parseColor("#0D47A1")) }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 0.8f)
            addView(tvLog)
        }

        root.addView(TextView(this).apply {
            text = "Ứng dụng đã ghim (bấm 1 lần là xong)"; textSize = 14f
            setTextColor(Color.parseColor("#0D47A1"))
        })
        root.addView(pinnedBox)

        // Thanh lọc
        val chips = mutableListOf<Pair<String, Button>>()
        var refreshList: () -> Unit = {}
        fun paintChips() = chips.forEach { (m, b) ->
            b.backgroundTintList = ColorStateList.valueOf(
                if (m == mode) Color.parseColor("#0D47A1") else Color.parseColor("#64B5F6"))
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
        root.addView(tvSelected); root.addView(row); root.addView(scroll)
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

    private fun grantAll(app: AppItem) {
        if (!Shizuku.pingBinder()) return toast("Shizuku chưa chạy")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1); return
        }
        tvLog.text = "Đang cấp quyền cho ${app.label}...\n"
        thread {
            val pkg = app.pkg
            val cmds = mutableListOf<String>()
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.forEach { cmds += "pm grant $pkg $it" }
            appOps.forEach { cmds += "appops set $pkg $it allow" }
            cmds += "dumpsys deviceidle whitelist +$pkg"

            var ok = 0
            val sb = StringBuilder()
            cmds.forEach { c ->
                val out = sh(c)
                if (out.isBlank() || out.startsWith("Added")) ok++
                else sb.append("• $c\n  → ${out.take(80)}\n")
            }
            val ch = enableChannels(pkg)
            runOnUiThread {
                tvLog.text = "✅ ${app.label}: $ok/${cmds.size} lệnh OK\n🔔 $ch\n\n" +
                        "Lệnh bị bỏ qua (bình thường nếu quyền không áp dụng):\n$sb"
            }
        }
    }

    // Bật lại mọi kênh thông báo đang bị tắt (cần Shizuku)
    private fun enableChannels(pkg: String): String = try {
        HiddenApiBypass.addHiddenApiExemptions("L")
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("notification"))
        val cls = Class.forName("android.app.INotificationManager")
        val nm = Class.forName("android.app.INotificationManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val uid = packageManager.getApplicationInfo(pkg, 0).uid
        val i = Int::class.javaPrimitiveType!!
        val b = Boolean::class.javaPrimitiveType!!
        try {
            cls.getMethod("setNotificationsEnabledForPackage", String::class.java, i, b)
                .invoke(nm, pkg, uid, true)
        } catch (_: Exception) {}
        val slice = cls.getMethod("getNotificationChannelsForPackage", String::class.java, i, b)
            .invoke(nm, pkg, uid, false)!!
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
        var fixed = 0
        for (c in list) {
            val ch = c as NotificationChannel
            if (ch.importance == NotificationManager.IMPORTANCE_NONE) {
                ch.importance = NotificationManager.IMPORTANCE_DEFAULT
                cls.getMethod("updateNotificationChannelForPackage",
                    String::class.java, i, NotificationChannel::class.java)
                    .invoke(nm, pkg, uid, ch)
                fixed++
            }
        }
        "Kênh thông báo: bật lại $fixed/${list.size} kênh đang tắt"
    } catch (e: Throwable) {
        "Không bật được kênh thông báo: ${(e.cause ?: e).message?.take(120)}"
    }

    private fun sh(cmd: String): String = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
        p.waitFor(); out.trim()
    } catch (e: Exception) { "ERR ${e.message}" }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
