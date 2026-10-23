package com.example.granter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Nhập file .mtz vào Xiaomi Themes, làm theo đúng cách của HyperOS Tool:
 *   .data/meta/<module>/<uuid>.mrm     JSON metadata (dựng từ mẫu metadata_template)
 *   .data/content/<module>/<uuid>.mrc  chép nguyên entry module trong MTZ
 *   .data/content/theme/<uuid>.mrc     file rỗng (0 byte), contentPath của theme trỏ tới đây
 *   .data/preview/theme/<themeUuid>/ và preview/launcher/<launcherUuid>/  ảnh preview
 *
 * Cài bằng script run.sh chạy với quyền shell (uid 2000): umask 007, không ghi đè file có sẵn,
 * chmod 0660, kiểm tra SHA-1 từng file sau khi ghi.
 *
 * Dùng:
 *   val st = MtzImporter.stage(this, { assets.open("MTZOS4.mtz") }) { log(it) }
 *   log(MtzImporter.install(st, run))   // run = backend() của app (uid 2000)
 */
object MtzImporter {
    const val THEME_DATA = "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    private const val CONTENT_THEME_PATH =
        "/system/../storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data/content/theme/"

    private const val MAX_MTZ = 350L * 1024 * 1024
    private const val MAX_TOTAL = 700L * 1024 * 1024
    private const val MAX_PREVIEW = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 500
    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png")
    private val COVER_SOURCES = listOf("preview_cover_0.", "preview_launcher_0.", "preview_0.", "preview_lockscreen_0.")
    private val MODULE_ALIAS = mapOf(
        "com.miui.home" to "launcher",
        "com.android.systemui" to "statusbar",
        "com.android.contacts" to "contact"
    )

    /** files: (đường dẫn tương đối trong .data, SHA-1) */
    class Staged(val work: File, val root: File, val files: List<Pair<String, String>>)
    private class Info(
        val title: String, val author: String, val designer: String,
        val desc: String, val ui: Int?
    )

    private class Mod(val code: String, val id: String, val hash: String, val size: Long)

    fun stage(ctx: Context, open: () -> InputStream, log: (String) -> Unit): Staged {
        val base = ctx.getExternalFilesDir("theme") ?: error("Không có bộ nhớ ngoài của app")
        val work = File(base, "mtz_${UUID.randomUUID()}")
        check(work.mkdirs()) { "Không tạo được thư mục tạm" }
        check(!work.absolutePath.contains('\'')) { "Đường dẫn tạm không an toàn" }

        log("Đang đọc file MTZ…")
        val original = File(work, "original.mtz")
        val (mtzSha1, mtzSize) = copyLimited(open(), original, MAX_MTZ)

        val root = File(work, "stage")
        val files = ArrayList<Pair<String, String>>()
        var budget = MAX_TOTAL

        fun put(rel: String, bytes: ByteArray) {
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            files.add(rel to sha1Hex(bytes))
        }

        java.util.zip.ZipFile(original).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= MAX_ENTRIES) { "MTZ có quá nhiều tệp" }
            for (e in entries) {
                val n = e.name
                require(!n.startsWith("/") && !n.contains("..") && !n.contains('\\')) {
                    "Đường dẫn không hợp lệ: $n"
                }
            }
            val descEntry = entries.firstOrNull { it.name == "description.xml" }
                ?: error("MTZ thiếu description.xml")
            val info = zip.getInputStream(descEntry).use { parseDescription(it) }

            val modules = entries.filter {
                !it.isDirectory && !it.name.contains('/') && it.name != "description.xml"
            }
            require(modules.isNotEmpty()) { "MTZ không chứa module có thể import" }

            val tpl = JSONObject(TEMPLATE_JSON)
            val platform = info.ui ?: 17
            val themeId = UUID.randomUUID().toString()
            val subs = JSONArray()
            val seen = HashSet<String>()
            var launcher: Mod? = null

            // module: .mrc chép nguyên, .mrm dựng từ mẫu "component" (launcher: đợi có preview)
            fun moduleMeta(m: Mod, launcherPreviews: List<String>?): ByteArray {
                val o = JSONObject(tpl.getJSONObject(if (m.code == "launcher") "launcher" else "component").toString())
                fill(o, platform, m.id, m.hash, m.size, info)
                o.put("parentResources", JSONArray().put(resource(themeId, "theme")))
                o.put("subResources", JSONArray())
                if (launcherPreviews != null) {
                    o.put("builtInThumbnails", locList(launcherPreviews))
                    o.put("builtInPreviews", locList(launcherPreviews))
                }
                return o.toString(4).toByteArray(Charsets.UTF_8)
            }

            for (m in modules) {
                val code = MODULE_ALIAS[m.name] ?: m.name
                require(SAFE_NAME.matches(code)) { "Tên module không hợp lệ: ${m.name}" }
                require(seen.add(code)) { "Tên module bị trùng sau khi ánh xạ: $code" }
                val id = UUID.randomUUID().toString()

                log("Đang chuyển module $code…")
                val (h, s) = copyLimited(zip.getInputStream(m), File(root, "content/$code/$id.mrc"), MAX_TOTAL)
                budget -= s
                require(budget >= 0) { "MTZ giải nén vượt giới hạn an toàn" }
                files.add("content/$code/$id.mrc" to h)

                val mod = Mod(code, id, h, s)
                subs.put(resource(id, code))
                if (code == "launcher") launcher = mod
                else put("meta/$code/$id.mrm", moduleMeta(mod, null))
            }

            // ảnh preview
            val previews = entries.filter {
                if (it.isDirectory || !it.name.startsWith("preview/")) return@filter false
                val f = it.name.removePrefix("preview/")
                SAFE_NAME.matches(f) && f.substringAfterLast('.').lowercase() in IMAGE_EXT
            }
            val data = LinkedHashMap<String, ByteArray>()
            for (p in previews) {
                val f = p.name.removePrefix("preview/")
                data[f] = zip.getInputStream(p).use { readLimited(it, MAX_PREVIEW) }
            }
            val names = ArrayList<String>(data.keys)
            val launcherPreviews = names.filter { it.startsWith("preview_launcher_") }
            for ((f, bytes) in data) {
                put("preview/theme/$themeId/$f", bytes)
                val l = launcher
                if (l != null && f.startsWith("preview_launcher_")) put("preview/launcher/${l.id}/$f", bytes)
            }

            // ảnh bìa: nếu chưa có preview_cover_0.jpg thì tạo (JPEG) từ preview có sẵn
            val cover = "preview_cover_0.jpg"
            if (cover !in data) {
                val src = COVER_SOURCES.firstNotNullOfOrNull { pre -> data.keys.firstOrNull { it.startsWith(pre) } }
                if (src != null) {
                    put("preview/theme/$themeId/$cover", toJpeg(data.getValue(src)))
                    names.add(0, cover)
                    log("Đã tạo ảnh bìa cho Xiaomi Themes từ $src")
                }
            }
            val thumbs = if (cover in names) listOf(cover) else emptyList()

            launcher?.let { put("meta/launcher/${it.id}.mrm", moduleMeta(it, launcherPreviews)) }

            // theme: .mrc rỗng + .mrm có contentPath tuyệt đối
            put("content/theme/$themeId.mrc", ByteArray(0))
            val t = JSONObject(tpl.getJSONObject("theme").toString())
            fill(t, platform, themeId, mtzSha1, mtzSize, info)
            t.put("contentPath", "$CONTENT_THEME_PATH$themeId.mrc")
            t.put("subResources", subs)
            t.put("builtInThumbnails", locList(thumbs))
            t.put("builtInPreviews", locList(names))
            put("meta/theme/$themeId.mrm", t.toString(4).toByteArray(Charsets.UTF_8))
            log("Đã tạo ${files.size} tệp để import")
        }
        return Staged(work, root, files)
    }

    /** Chép các tệp đã dựng vào .data của Xiaomi Themes. run phải chạy với quyền shell (uid 2000). */
    fun install(st: Staged, run: (String) -> String): String {
        val root = st.root.absolutePath
        val sb = StringBuilder()
        sb.append("#!/system/bin/sh\n")
        sb.append("umask 007\n")
        sb.append("set -eu\n")
        sb.append("test \"\$(id -u)\" = 2000 || { echo MTZ_IMPORT_FAILED_BAD_UID; exit 1; }\n")
        for ((rel, sha) in st.files) {
            val src = "$root/$rel"
            val dst = "$THEME_DATA/$rel"
            sb.append("test -r '$src' || { echo MTZ_IMPORT_FAILED_STAGE_$rel; exit 1; }\n")
            sb.append("test ! -e '$dst' || { echo MTZ_IMPORT_FAILED_EXISTS_$rel; exit 1; }\n")
            sb.append("mkdir -p '${dst.substringBeforeLast('/')}'\n")
            sb.append("( set -C; cat '$src' > '$dst' ) || { echo MTZ_IMPORT_FAILED_WRITE_$rel; exit 1; }\n")
            sb.append("chmod 0660 '$dst' 2>/dev/null || true\n")
            sb.append("test \"\$(sha1sum '$dst' | cut -d' ' -f1)\" = '$sha' || { echo MTZ_IMPORT_FAILED_SHA1_$rel; exit 1; }\n")
        }
        sb.append("echo MTZ_IMPORT_OK\n")
        val script = File(st.work, "run.sh")
        script.writeText(sb.toString())
        val out = run("sh '${script.absolutePath}' 2>&1").trim()
        st.work.deleteRecursively()
        check(out.endsWith("MTZ_IMPORT_OK")) { "Import thất bại: $out" }
        return "Đã chép ${st.files.size} tệp vào Xiaomi Themes."
    }

    // ---------- JSON ----------
    private fun resource(id: String, code: String) = JSONObject()
        .put("localId", id).put("resourceCode", code)
        .put("extraMeta", JSONObject())
        .put("metaPath", JSONObject.NULL).put("contentPath", JSONObject.NULL)

    private fun locStr(v: String) = JSONObject().put("fallback", v).put("zh_CN", v)

    private fun locList(l: List<String>): JSONObject {
        val o = JSONObject()
        for (k in listOf("fallback", "zh_CN", "en_US", "vi_VN")) o.put(k, JSONArray(l))
        return o
    }

    private fun fill(o: JSONObject, platform: Int, id: String, sha1: String, size: Long, info: Info) {
        o.put("localId", id).put("hash", sha1).put("size", size).put("platform", platform)
        o.put("authors", locStr(info.author)).put("designers", locStr(info.designer))
        o.put("titles", locStr(info.title)).put("descriptions", locStr(info.desc))
    }

    // ---------- tiện ích ----------
    private fun parseDescription(ins: InputStream): Info {
        val p = Xml.newPullParser()
        p.setInput(ins, "UTF-8")
        val v = HashMap<String, String>()
        val keys = setOf("title", "author", "designer", "description", "version", "uiVersion")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name in keys) {
                val tag = p.name
                try { v.putIfAbsent(tag, p.nextText().trim()) } catch (_: Exception) { }
            }
            ev = try { p.next() } catch (_: Exception) { break }
        }
        val author = v["author"] ?: v["designer"] ?: ""
        return Info(
            v["title"]?.ifEmpty { null } ?: "Chủ đề MTZ", author,
            v["designer"] ?: author, v["description"] ?: "", v["uiVersion"]?.toIntOrNull()
        )
    }

    private fun sha1Hex(b: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun toJpeg(b: ByteArray): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Ảnh preview không hợp lệ" }
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: error("Không giải mã được ảnh preview")
        try {
            val out = java.io.ByteArrayOutputStream()
            check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "Không tạo được ảnh bìa" }
            return out.toByteArray()
        } finally { bmp.recycle() }
    }

    private fun copyLimited(input: InputStream, dst: File, limit: Long): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-1")
        var n = 0L
        dst.parentFile?.mkdirs()
        input.use { i ->
            dst.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = i.read(buf)
                    if (r < 0) break
                    n += r
                    require(n <= limit) { "Tệp vượt giới hạn $limit byte" }
                    md.update(buf, 0, r)
                    o.write(buf, 0, r)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } to n
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            require(n <= limit) { "Ảnh preview quá lớn" }
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    // Mẫu metadata của Xiaomi Themes (lấy từ HyperOS Tool): theme / component / launcher
    private const val TEMPLATE_JSON = """{
  "theme": {
    "localId": "TEMPLATE_UUID",
    "onlineId": null,
    "assemblyId": null,
    "productId": null,
    "hash": "TEMPLATE_HASH",
    "platform": 17,
    "size": 0,
    "updatedTime": 0,
    "version": "1",
    "authors": {
      "zh_CN": "Truong Nguyen",
      "fallback": "Truong Nguyen"
    },
    "designers": {
      "zh_CN": "Truong Nguyen",
      "fallback": "Truong Nguyen"
    },
    "titles": {
      "zh_CN": "Xiaomi Theme VN",
      "fallback": "Xiaomi Theme VN"
    },
    "descriptions": {
      "zh_CN": "",
      "fallback": ""
    },
    "builtInThumbnails": {
      "fallback": [
        "preview_cover_0.jpg",
        "preview_launcher_0.jpg",
        "preview_lockscreen_0.jpg"
      ]
    },
    "builtInPreviews": {
      "fallback": [
        "preview_cover_0.jpg",
        "preview_launcher_0.jpg",
        "preview_lockscreen_0.jpg"
      ]
    },
    "thumbnails": [],
    "previews": [],
    "parentResources": [],
    "subResources": [],
    "extraMeta": {},
    "metaPath": null,
    "contentPath": "TEMPLATE_PATH",
    "rightsPath": null,
    "screenRatio": null,
    "supportHomeSearchBar": false,
    "packageVersion": null,
    "packageName": null,
    "officialIcons": false,
    "iconsCount": null,
    "fontWeight": null,
    "price": -1,
    "isBackupVersion": false,
    "themeType": 0,
    "miuiAdapterVersion": null
  },
  "component": {
    "localId": "TEMPLATE_UUID",
    "onlineId": null,
    "assemblyId": null,
    "productId": null,
    "hash": "TEMPLATE_HASH",
    "platform": 17,
    "size": 0,
    "updatedTime": 0,
    "version": "1",
    "authors": {
      "fallback": "Truong Nguyen",
      "zh_CN": "Truong Nguyen"
    },
    "designers": {
      "fallback": "Truong Nguyen",
      "zh_CN": "Truong Nguyen"
    },
    "titles": {
      "fallback": "Xiaomi Theme VN",
      "zh_CN": "Xiaomi Theme VN"
    },
    "descriptions": {
      "fallback": "",
      "zh_CN": ""
    },
    "builtInThumbnails": {
      "fallback": []
    },
    "builtInPreviews": {
      "fallback": []
    },
    "thumbnails": [],
    "previews": [],
    "parentResources": [],
    "subResources": [],
    "extraMeta": {},
    "metaPath": null,
    "contentPath": null,
    "rightsPath": null,
    "screenRatio": null,
    "supportHomeSearchBar": false,
    "packageVersion": null,
    "packageName": null,
    "officialIcons": false,
    "wallpaperStyle": 0,
    "isSingleResource": false,
    "iconsCount": null,
    "fontWeight": null,
    "price": -1,
    "isBackUpVersion": false,
    "themeType": 0,
    "miuiAdapterVersion": "4.0"
  },
  "launcher": {
    "localId": "TEMPLATE_UUID",
    "onlineId": null,
    "assemblyId": null,
    "productId": null,
    "hash": "TEMPLATE_HASH",
    "platform": 17,
    "size": 0,
    "updatedTime": 0,
    "version": "1",
    "authors": {
      "fallback": "Truong Nguyen",
      "zh_CN": "Truong Nguyen"
    },
    "designers": {
      "fallback": "Truong Nguyen",
      "zh_CN": "Truong Nguyen"
    },
    "titles": {
      "fallback": "Xiaomi Theme VN",
      "zh_CN": "Xiaomi Theme VN"
    },
    "descriptions": {
      "fallback": "",
      "zh_CN": ""
    },
    "builtInThumbnails": {
      "fallback": [
        "preview_launcher_0.jpg"
      ]
    },
    "builtInPreviews": {
      "fallback": [
        "preview_launcher_0.jpg"
      ]
    },
    "thumbnails": [],
    "previews": [],
    "parentResources": [],
    "subResources": [],
    "extraMeta": {},
    "metaPath": null,
    "contentPath": null,
    "rightsPath": null,
    "screenRatio": null,
    "supportHomeSearchBar": false,
    "packageVersion": null,
    "packageName": null,
    "officialIcons": false,
    "wallpaperStyle": 0,
    "isSingleResource": false,
    "iconsCount": null,
    "fontWeight": null,
    "price": -1,
    "isBackUpVersion": false,
    "themeType": 0,
    "miuiAdapterVersion": "4.0"
  }
}"""
}
