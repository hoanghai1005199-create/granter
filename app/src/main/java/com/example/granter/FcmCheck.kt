package com.example.granter

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Kiểm tra kết nối FCM: mở màn hình chẩn đoán của Google Play services; chưa có Google thì mở CH Play để tải. */
object FcmCheck {
    private const val GMS = "com.google.android.gms"
    private const val SEARCH = "com.google.android.googlequicksearchbox"

    private fun hasGoogle(a: Activity): Boolean =
        try { a.packageManager.getPackageInfo(GMS, 0); true } catch (e: Exception) { false }

    fun open(a: Activity) {
        if (!hasGoogle(a)) { openPlayStore(a); return }
        val screens = listOf(
            "com.google.android.gms.gcm.GcmDiagnostics",
            "com.google.android.gms.gtalkservice.GTalkServiceDiagnostics"
        )
        for (cls in screens) {
            try {
                a.startActivity(Intent().setComponent(ComponentName(GMS, cls)))
                return
            } catch (e: Exception) {}
        }
        Toast.makeText(a, "Không tìm thấy màn hình chẩn đoán FCM trên máy này", Toast.LENGTH_LONG).show()
    }

    private fun openPlayStore(a: Activity) {
        Toast.makeText(a, "Thiết bị chưa có dịch vụ Google, đang mở CH Play để tải Google...", Toast.LENGTH_LONG).show()
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SEARCH")))
        } catch (e: Exception) {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$SEARCH")))
            } catch (e2: Exception) {}
        }
    }
}
