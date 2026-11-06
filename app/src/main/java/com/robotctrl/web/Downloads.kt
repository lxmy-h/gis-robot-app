package com.robotctrl.web

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 接手页面上的"拍照 / 保存录像"。
 *
 * 在 Chrome 里点一下就会自动下载到相册，但 WebView 不会——三条路都要自己管：
 *
 *  1. 服务器直接返回文件（http/https）：DownloadListener 接住 -> 后台线程下载 -> 写进相册
 *  2. 页面用 blob: / data: 自己生成：WebView 根本不触发 onDownloadStart，
 *     所以注入的 JS 会把内容分块用 base64 推给本类的 Bridge（begin/append/finish）
 *  3. 写文件：Android 10+ 走 MediaStore（不需要存储权限，图库能直接看到）；
 *     Android 9 及以下写公共目录 + MediaScanner 扫描，需要 WRITE_EXTERNAL_STORAGE
 *
 * 保存位置（照片与录像按系统惯例分目录存放）：
 *   图片 -> Pictures/GIS内检机器人
 *   视频 -> Movies/GIS内检机器人
 *   其它 -> Download/GIS内检机器人
 */
class Downloads(
    private val activity: Activity,
    private val webView: WebView
) {

    companion object {
        private const val TAG = "RobotWeb"

        /** 相册里的文件夹名 */
        private const val ALBUM = "GIS内检机器人"

        /** JS 侧用的桥名字，必须和 assets/downloads.js 里一致 */
        const val BRIDGE = "__rwDownload"

        const val REQ_STORAGE = 1002
    }

    private val ui = Handler(Looper.getMainLooper())

    /** 等存储权限的分片任务 */
    private var pendingAction: (() -> Unit)? = null

    /** 正在接收的分块文件：id -> 临时文件 */
    private val tmpFiles = ConcurrentHashMap<String, File>()

    /** 分块文件的信息：id -> (文件名, mime) */
    private val tmpMeta = ConcurrentHashMap<String, Pair<String, String>>()

    // ------------------------------------------------------------------ 对外入口

    /** http/https 下载（由 WebView 的 DownloadListener 调用，这里是主线程） */
    fun handleUrl(url: String, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // 这种走 JS 那条路，这里什么都不用做
            return
        }

        // 注意：WebView / CookieManager 的方法只能在主线程调用（否则抛
        // "A WebView method was called on thread ..."），所以 UA 和 Cookie 必须在这里先取好，
        // 再交给后台线程去下载。
        val userAgent = try {
            webView.settings.userAgentString
        } catch (e: Throwable) {
            "Mozilla/5.0 (Linux; Android)"
        }
        val cookie = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Throwable) {
            null
        }

        Log.i(TAG, "开始下载: $url (mime=$mimeType)")
        withPermission {
            Thread { downloadUrl(url, contentDisposition, mimeType, userAgent, cookie) }.start()
        }
    }

    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode != REQ_STORAGE) return
        val action = pendingAction
        pendingAction = null
        if (granted) {
            action?.invoke()
        } else {
            toast("没有存储权限，图片/录像无法保存到相册")
        }
    }

    // ------------------------------------------------------------------ JS 桥

    inner class Bridge {

        @JavascriptInterface
        fun begin(id: String?, name: String?, mime: String?) {
            val key = id ?: return
            try {
                drop(key)
                val f = File.createTempFile("rwdl_", ".part", activity.cacheDir)
                FileOutputStream(f).close()
                tmpFiles[key] = f
                tmpMeta[key] = Pair(
                    sanitizeName(name?.takeIf { it.isNotBlank() } ?: defaultName(mime ?: "")),
                    mime ?: ""
                )
                Log.d(TAG, "分块保存开始: $key $name")
            } catch (e: Throwable) {
                Log.w(TAG, "begin 失败", e)
            }
        }

        @JavascriptInterface
        fun append(id: String?, base64: String?) {
            val key = id ?: return
            val f = tmpFiles[key] ?: return
            if (base64.isNullOrEmpty()) return
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                FileOutputStream(f, true).use { it.write(bytes) }
            } catch (e: Throwable) {
                Log.w(TAG, "append 失败", e)
            }
        }

        @JavascriptInterface
        fun finish(id: String?) {
            val key = id ?: return
            val f = tmpFiles.remove(key) ?: return
            val meta = tmpMeta.remove(key)
            val name = meta?.first ?: defaultName("")
            val mime = meta?.second ?: ""
            withPermission {
                Thread {
                    try {
                        val saved = f.inputStream().use { saveToGallery(it, name, mime) }
                        f.delete()
                        ui.post { toastResult(saved, name) }
                    } catch (e: Throwable) {
                        Log.w(TAG, "保存失败", e)
                        f.delete()
                        val msg = e.message ?: e.javaClass.simpleName
                        ui.post { toast("保存失败：$msg") }
                    }
                }.start()
            }
        }

        @JavascriptInterface
        fun abort(id: String?) {
            id?.let { drop(it) }
        }

        /** 方便在 JS 里写日志排查 */
        @JavascriptInterface
        fun log(msg: String?) {
            Log.d(TAG, "[js-dl] $msg")
        }
    }

    private fun drop(id: String) {
        tmpFiles.remove(id)?.delete()
        tmpMeta.remove(id)
    }

    // ------------------------------------------------------------------ 下载实现

    private fun downloadUrl(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        userAgent: String,
        cookie: String?
    ) {
        var saved: String? = null
        var name = defaultName(mimeType ?: "")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 120000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                cookie?.let { setRequestProperty("Cookie", it) }
            }

            val disposition = contentDisposition ?: conn.getHeaderField("Content-Disposition")
            name = nameFromDisposition(disposition)
                ?: nameFromUrl(url)
                ?: defaultName(mimeType ?: "")
            val mime = (mimeType ?: conn.contentType ?: "")
                .substringBefore(';')
                .trim()
                .ifBlank { mimeFromName(name) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val hint = if (code == 405 || code == 400) "（服务器要求 POST，这种下载方式不适用）" else ""
                Log.w(TAG, "下载失败 HTTP $code: $url")
                ui.post { toast("下载失败：服务器返回 HTTP $code$hint") }
                conn.disconnect()
                return
            }

            Log.i(TAG, "下载中: $url -> $name ($mime, ${conn.contentLength} bytes)")
            conn.inputStream.use { input ->
                saved = saveToGallery(input, name, mime)
            }
            conn.disconnect()
        } catch (e: Throwable) {
            Log.w(TAG, "下载失败: $url", e)
            val msg = e.message ?: e.javaClass.simpleName
            ui.post { toast("下载失败：$msg") }
            return
        }
        val result = saved
        ui.post { toastResult(result, name) }
    }

    // ------------------------------------------------------------------ 写进相册

    /**
     * @return 保存成功返回可读路径/URI，失败返回 null
     */
    private fun saveToGallery(input: InputStream, rawName: String, rawMime: String): String? {
        val mime = rawMime.ifBlank { mimeFromName(rawName) }
        val name = ensureExtension(sanitizeName(rawName), mime)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveModern(input, name, mime)
        } else {
            saveLegacy(input, name, mime)
        }
    }

    /** Android 10+：MediaStore，不需要存储权限，图库直接可见 */
    private fun saveModern(input: InputStream, name: String, mime: String): String? {
        val resolver = activity.contentResolver
        val collection: android.net.Uri
        val relative: String
        when {
            mime.startsWith("image/") -> {
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                relative = "${Environment.DIRECTORY_PICTURES}/$ALBUM"
            }
            mime.startsWith("video/") -> {
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                relative = "${Environment.DIRECTORY_MOVIES}/$ALBUM"
            }
            else -> {
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                relative = "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM"
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri) ?: return null
            out.use { o -> input.use { it.copyTo(o, 64 * 1024) } }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            Log.i(TAG, "已保存: $uri")
            return uri.toString()
        } catch (e: Exception) {
            Log.w(TAG, "写入 MediaStore 失败", e)
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    /** Android 9 及以下：写公共目录 + 通知媒体库扫描 */
    private fun saveLegacy(input: InputStream, name: String, mime: String): String? {
        val dirType = when {
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(dirType), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) return null

        var file = File(dir, name)
        var i = 1
        while (file.exists()) {
            file = File(dir, withSuffix(name, "_${i++}"))
        }

        FileOutputStream(file).use { out -> input.use { it.copyTo(out, 64 * 1024) } }
        MediaScannerConnection.scanFile(activity, arrayOf(file.absolutePath), arrayOf(mime), null)
        Log.i(TAG, "已保存: ${file.absolutePath}")
        return file.absolutePath
    }

    // ------------------------------------------------------------------ 权限

    private fun withPermission(action: () -> Unit) {
        ui.post {
            val needPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needPermission) {
                pendingAction = action
                activity.requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_STORAGE
                )
            } else {
                action()
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    private fun toastResult(path: String?, name: String) {
        if (path != null) {
            toast("已保存到相册：$name")
        } else {
            toast("保存失败：$name")
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun defaultName(mime: String): String {
        return "robot_${stamp()}${extOf(mime)}"
    }

    private fun extOf(mime: String): String = when {
        mime.startsWith("image/png") -> ".png"
        mime.startsWith("image/jpeg") -> ".jpg"
        mime.startsWith("image/webp") -> ".webp"
        mime.startsWith("image/gif") -> ".gif"
        mime.startsWith("video/mp4") -> ".mp4"
        mime.startsWith("video/webm") -> ".webm"
        mime.startsWith("video/quicktime") -> ".mov"
        else -> ".bin"
    }

    private fun ensureExtension(name: String, mime: String): String {
        if (name.contains('.') && name.substringAfterLast('.').length in 1..5) return name
        return name + extOf(mime)
    }

    private fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim()
        return cleaned.ifBlank { "robot_${stamp()}.bin" }.take(96)
    }

    private fun nameFromUrl(url: String): String? {
        return try {
            val path = url.substringBefore('?').substringBefore('#')
            val last = path.substringAfterLast('/')
            if (last.isBlank() || !last.contains('.')) null else UriDecode.decode(last)
        } catch (e: Exception) {
            null
        }
    }

    private fun nameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        // filename*=UTF-8''xxx 或 filename="xxx"
        val star = Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
        if (star != null) return UriDecode.decode(star.trim().trim('"'))
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
        return plain?.let { UriDecode.decode(it.trim()) }
    }

    private fun withSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot) else name + suffix
    }

    /** 轻量 URL 解码，避免依赖 android.net.Uri 的行为差异 */
    private object UriDecode {
        fun decode(s: String): String = try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }
    }
}
