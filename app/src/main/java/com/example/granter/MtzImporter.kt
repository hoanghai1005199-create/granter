package com.example.granter

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Nhập file .mtz vào Xiaomi Themes theo định dạng quan sát được trên máy:
 *   .data/meta/<module>/<uuid>.mrm     JSON metadata
 *   .data/content/<module>/<uuid>.mrc  chép nguyên entry module trong MTZ (bản thân là zip)
 *   .data/content/theme/<uuid>.mrc     file rỗng (0 byte)
 *   .data/preview/theme/<themeUuid>/ và preview/launcher/<launcherUuid>/  ảnh preview
 *
 * Giả định CHƯA kiểm chứng (cần đối chiếu với .mrm module thật trên máy):
 *  1. .mrm của module dùng cùng schema với .mrm của theme, parentResources trỏ về theme.
 *  2. Ảnh preview của theme nằm trong preview/theme/<themeUuid>/.
 *
 * Dùng:
 *   val st = MtzImporter.stage(this, { assets.open("MTZOS4.mtz") }) { log(it) }
 *   log(MtzImporter.install(st, run))   // run = backend() của app (uid 2000)
 */
object MtzImporter {
    const val THEME_DATA = "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data"

    private const val MAX_MTZ = 350L * 1024 * 1024
    private const val MAX_TOTAL = 700L * 1024 * 1024
    private const val MAX_PREVIEW = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 500
    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png")
    private val MODULE_ALIAS = mapOf(
        "com.miui.home" to "launcher",
        "com.android.systemui" to "statusbar",
        "com.android.contacts" to "contact"
    )

    class Staged(val work: File, val root: File, val files: List<String>)
    private class Info(val title: String, val author: String, val designer: String, val desc: String)

    fun stage(ctx: Context, open: () -> InputStream, log: (String) -> Unit): Staged {
        val base = ctx.getExternalFilesDir("theme") ?: error("Không có bộ nhớ ngoài của app")
        val work = File(base, "mtz_${UUID.randomUUID()}")
        check(work.mkdirs()) { "Không tạo được thư mục tạm" }
        check(!work.absolutePath.contains('\'')) { "Đường dẫn tạm không an toàn" }

        log("Đang đọc file MTZ…")
        val original = File(work, "original.mtz")
        val (mtzSha1, mtzSize) = copyLimited(open(), original, MAX_MTZ)

        val root = File(work, "stage")
        val files = ArrayList<String>()
        var budget = MAX_TOTAL

        fun put(rel: String, bytes: ByteArray) {
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            files += rel
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

            val themeId = UUID.randomUUID().toString()
            val subs = JSONArray()
            var launcherId: String? = null
            val seen = HashSet<String>()

            for (m in modules) {
                val code = MODULE_ALIAS[m.name] ?: m.name
                require(SAFE_NAME.matches(code)) { "Tên module không hợp lệ: ${m.name}" }
                require(seen.add(code)) { "Tên module bị trùng sau khi ánh xạ: $code" }
                val id = UUID.randomUUID().toString()
                if (code == "launcher") launcherId = id

                log("Đang chuyển module $code…")
                val (h, s) = copyLimited(zip.getInputStream(m), File(root, "content/$code/$id.mrc"), MAX_TOTAL)
                budget -= s
                require(budget >= 0) { "MTZ giải nén vượt giới hạn an toàn" }
                files += "content/$code/$id.mrc"

                val parents = JSONArray().put(resource(themeId, "theme"))
                put("meta/$code/$id.mrm",
                    meta(id, h, s, info, code, emptyList(), emptyList(), parents, JSONArray(), module = true)
                        .toByteArray(Charsets.UTF_8))
                subs.put(resource(id, code))
            }

            // ảnh preview
            val previews = entries.filter {
                if (it.isDirectory || !it.name.startsWith("preview/")) return@filter false
                val f = it.name.removePrefix("preview/")
                SAFE_NAME.matches(f) && f.substringAfterLast('.').lowercase() in IMAGE_EXT
            }
            val names = ArrayList<String>()
            var firstJpg: ByteArray? = null
            for (p in previews) {
                val f = p.name.removePrefix("preview/")
                val bytes = zip.getInputStream(p).use { readLimited(it, MAX_PREVIEW) }
                put("preview/theme/$themeId/$f", bytes)
                val lid = launcherId
                if (f.startsWith("preview_launcher_") && lid != null) put("preview/launcher/$lid/$f", bytes)
                names += f
                if (firstJpg == null && f.endsWith(".jpg", true)) firstJpg = bytes
            }
            val cover = "preview_cover_0.jpg"
            if (cover !in names && firstJpg != null) {
                put("preview/theme/$themeId/$cover", firstJpg)
                names.add(0, cover)
            }
            val thumbs = if (cover in names) listOf(cover) else emptyList()

            put("content/theme/$themeId.mrc", ByteArray(0))
            put("meta/theme/$themeId.mrm",
                meta(themeId, mtzSha1, mtzSize, info, info.title, thumbs, names, JSONArray(), subs)
                    .toByteArray(Charsets.UTF_8))
        }
        return Staged(work, root, files)
    }

    /** Chép các tệp đã dựng vào .data của Xiaomi Themes. run phải chạy với quyền shell (uid 2000). */
    fun install(st: Staged, run: (String) -> String): String {
        val root = st.root.absolutePath
        val sb = StringBuilder("set -eu\n")
        sb.append("test \"\$(id -u)\" = 2000 || { echo MTZ_FAIL_UID; exit 1; }\n")
        sb.append("test -d '$THEME_DATA' || { echo MTZ_FAIL_NO_THEME_DIR; exit 1; }\n")
        for (rel in st.files) {
            val dst = "$THEME_DATA/$rel"
            sb.append("mkdir -p '${dst.substringBeforeLast('/')}' && cp '$root/$rel' '$dst' && chmod 0660 '$dst' || { echo MTZ_FAIL_WRITE; exit 1; }\n")
        }
        sb.append("echo MTZ_OK\n")
        val out = run(sb.toString()).trim()
        st.work.deleteRecursively()
        check(out.endsWith("MTZ_OK")) { "Import thất bại: $out" }
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

    private fun meta(
        id: String, sha1: String, size: Long, info: Info, title: String,
        thumbs: List<String>, previews: List<String>, parents: JSONArray, subs: JSONArray,
        module: Boolean = false
    ): String = JSONObject()
        .put("localId", id)
        .put("onlineId", JSONObject.NULL).put("assemblyId", JSONObject.NULL).put("productId", JSONObject.NULL)
        .put("hash", sha1).put("platform", 17).put("size", size)
        .put("updatedTime", 0).put("version", "1")
        .put("authors", locStr(info.author)).put("designers", locStr(info.designer))
        .put("titles", locStr(title)).put("descriptions", locStr(info.desc))
        .put("builtInThumbnails", locList(thumbs)).put("builtInPreviews", locList(previews))
        .put("thumbnails", JSONArray()).put("previews", JSONArray())
        .put("parentResources", parents).put("subResources", subs)
        // Các trường còn lại theo mẫu metadata của Xiaomi Themes (price -1 = miễn phí/cục bộ)
        .put("extraMeta", JSONObject())
        .put("metaPath", JSONObject.NULL).put("contentPath", JSONObject.NULL).put("rightsPath", JSONObject.NULL)
        .put("screenRatio", JSONObject.NULL).put("supportHomeSearchBar", false)
        .put("packageVersion", JSONObject.NULL).put("packageName", JSONObject.NULL)
        .put("officialIcons", false).put("iconsCount", JSONObject.NULL).put("fontWeight", JSONObject.NULL)
        .put("price", -1).put("themeType", 0)
        .also {
            if (module) {
                it.put("wallpaperStyle", 0).put("isSingleResource", false)
                    .put("isBackUpVersion", false).put("miuiAdapterVersion", "4.0")
            } else {
                it.put("isBackupVersion", false).put("miuiAdapterVersion", JSONObject.NULL)
            }
        }
        .toString(4)

    // ---------- tiện ích ----------
    private fun parseDescription(ins: InputStream): Info {
        val p = Xml.newPullParser()
        p.setInput(ins, "UTF-8")
        val v = HashMap<String, String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name in setOf("title", "author", "designer", "description")) {
                val tag = p.name
                try { v.putIfAbsent(tag, p.nextText().trim()) } catch (_: Exception) { }
            }
            ev = try { p.next() } catch (_: Exception) { break }
        }
        val author = v["author"] ?: v["designer"] ?: ""
        return Info(v["title"]?.ifEmpty { null } ?: "MTZ", author, v["designer"] ?: author, v["description"] ?: "")
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
}
