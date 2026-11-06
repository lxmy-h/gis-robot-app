package com.robotctrl.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 机器人控制页的 WebView 外壳。
 *
 * 使用场景：手机开热点，平板和机器人同时连这个热点；机器人还没开机时网页是打不开的。
 * 所以这里的重点是"等待 + 自动重试"，而不是做一个漂亮的启动页。
 *
 * 1. 加载失败 / 超时 -> 显示遮罩 + 大号"刷新"按钮，并且每 3 秒自动重试一次。
 * 2. 机器人开机、页面能访问后 -> 自动进入控制画面，遮罩消失。
 * 3. 手动刷新：点遮罩上的按钮，或按【音量下键】。
 * 4. 改地址：按【音量上键】（默认地址写死在 DEFAULT_URL）。
 * 5. 自签名证书只对内网地址放行；进程默认网络绑定到 Wi-Fi（热点没外网时必需）。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "RobotWeb"
        private const val PREFS = "robot_settings"
        private const val KEY_URL = "url"
        private const val KEY_FIRST_RUN = "first_run"

        /** 是否显示页面上的放大镜圆钮（在设置对话框里可以关掉） */
        private const val KEY_MAG_HANDLE = "mag_handle"

        /** 默认地址：手机热点下机器人的地址（http，Flask 默认 5000 端口）。 */
        const val DEFAULT_URL = "http://172.20.10.10:5000"

        /** 想关掉"强制走 Wi-Fi"就改成 false。 */
        private const val BIND_WIFI_NETWORK = true

        private const val REQ_WEB_PERMISSION = 1001

        /** 失败后自动重试的间隔 */
        private const val RETRY_DELAY_MS = 3000L

        /** 一次加载最多等多久（机器人没开机时 TCP 连接可能挂很久，必须自己兜底） */
        private const val LOAD_TIMEOUT_MS = 12000L
    }

    private lateinit var webView: WebView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var prefs: SharedPreferences

    private val ui = Handler(Looper.getMainLooper())

    /** 当前这次加载是否已经成功（主框架） */
    private var pageLoaded = false

    /** 当前这次加载是否已经报过错，避免 error + timeout 重复计数 */
    private var failureShown = false

    private var retryCount = 0
    private var exitArmed = false
    private var pendingPermissionRequest: PermissionRequest? = null

    private var boundNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 注入脚本的缓存（assets 文件名 -> 内容） */
    private val assetCache = HashMap<String, String>()

    /** 页面"拍照 / 保存录像"的接管 */
    private lateinit var downloads: Downloads

    private val targetUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    private val retryRunnable = Runnable { load() }
    private val timeoutRunnable = Runnable {
        if (!pageLoaded) onLoadFailed("等待超时（${LOAD_TIMEOUT_MS / 1000} 秒没有响应）")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        overlay = findViewById(R.id.overlayPanel)
        overlayText = findViewById(R.id.overlayText)
        progress = findViewById(R.id.progress)
        retryButton = findViewById(R.id.btnRetry)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        retryButton.setOnClickListener { reloadNow() }

        enableWebViewDebugging()
        setupWebView()
        keepWifiAsProcessNetwork()
        load()
        showFirstRunHint()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.BLACK)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false           // 视频流自动播放
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false

        // 页面上的"拍照 / 保存录像"在浏览器里是自动下载，WebView 必须自己接住
        downloads = Downloads(this, webView)
        webView.addJavascriptInterface(downloads.Bridge(), Downloads.BRIDGE)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloads.handleUrl(url, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val target = request.url
                val controlHost = Uri.parse(targetUrl).host
                return if (target.host == controlHost) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, target))
                    } catch (e: Exception) {
                        Log.w(TAG, "无法打开外部链接: $target", e)
                    }
                    true
                }
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                pageLoaded = false
                failureShown = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!failureShown) onLoadOk()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                Log.w(TAG, "加载失败: ${error.errorCode} ${error.description}")
                onLoadFailed("连不上 $targetUrl\n(${error.description})")
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                val code = errorResponse.statusCode
                if (code >= 400) onLoadFailed("服务器返回 HTTP $code\n地址：$targetUrl")
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                val host = error.url?.let { Uri.parse(it).host }
                if (host != null && isPrivateHost(host)) {
                    // 机器人用自签名证书：只对局域网地址放行
                    Log.w(TAG, "放行内网自签名证书: ${error.url} (code=${error.primaryError})")
                    handler.proceed()
                } else {
                    Log.e(TAG, "拒绝非内网证书: ${error.url}")
                    handler.cancel()
                    onLoadFailed("证书校验失败：$host\n只允许访问局域网地址", autoRetry = false)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { grantWebPermission(request) }
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[console] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }
        }
    }

    /** 页面请求摄像头/麦克风（WebRTC）时：先补运行时权限，再授权给页面。 */
    private fun grantWebPermission(request: PermissionRequest) {
        val needed = ArrayList<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            request.grant(request.resources)
            return
        }
        pendingPermissionRequest = request
        requestPermissions(missing.toTypedArray(), REQ_WEB_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 保存到相册的存储权限（只有 Android 9 及以下需要）
        val storageGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (requestCode == Downloads.REQ_STORAGE) {
            downloads.onPermissionResult(requestCode, storageGranted)
            return
        }

        if (requestCode != REQ_WEB_PERMISSION) return
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request == null) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            request.grant(request.resources)
        } else {
            request.deny()
            Toast.makeText(this, "已拒绝摄像头/麦克风权限，视频可能无法显示", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ 加载 / 重试状态机

    private fun load() {
        cancelTimers()
        pageLoaded = false
        failureShown = false
        showLoadingUi()
        ui.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS)
        webView.loadUrl(targetUrl)
    }

    /** 手动刷新：按钮 / 音量下键都走这里，计数清零重新开始。 */
    private fun reloadNow() {
        retryCount = 0
        load()
    }

    private fun showLoadingUi() {
        progress.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        retryButton.text = getString(R.string.refresh)
        overlayText.text = getString(R.string.connecting, targetUrl)
        overlay.visibility = View.VISIBLE
    }

    private fun onLoadOk() {
        val recovered = retryCount > 0
        pageLoaded = true
        failureShown = false
        retryCount = 0
        cancelTimers()
        hideOverlay()
        injectScripts()
        if (recovered) {
            Toast.makeText(this, "已连接机器人", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 把"画面放大模式"和"下载接管"注入页面。
     * 两个脚本都不改页面 DOM 结构和布局，且自带幂等保护，重复注入无副作用。
     */
    private fun injectScripts() {
        injectAsset("magnifier.js")
        injectAsset("downloads.js")
        val visible = prefs.getBoolean(KEY_MAG_HANDLE, true)
        webView.evaluateJavascript(
            "window.__rwMag && window.__rwMag.setHandleVisible($visible);",
            null
        )
    }

    /** 从 assets 读脚本并注入（只读一次，之后走缓存） */
    private fun injectAsset(name: String) {
        val js = assetCache.getOrPut(name) {
            try {
                assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                Log.w(TAG, "$name 读取失败", e)
                ""
            }
        }
        if (js.isNotEmpty()) webView.evaluateJavascript(js, null)
    }

    /**
     * @param autoRetry false 用于"再试也没用"的错误（例如证书不被允许），避免死循环
     */
    private fun onLoadFailed(reason: String, autoRetry: Boolean = true) {
        if (pageLoaded || failureShown) return
        failureShown = true
        cancelTimers()
        retryCount++

        val hint = if (autoRetry) {
            "3 秒后自动重试，也可以点下面的按钮立即刷新。"
        } else {
            "请检查地址后再试。"
        }

        progress.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        retryButton.text = getString(R.string.refresh)
        overlayText.text = getString(R.string.connecting_failed, reason, retryCount, hint)
        overlay.visibility = View.VISIBLE

        if (autoRetry) ui.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun cancelTimers() {
        ui.removeCallbacks(retryRunnable)
        ui.removeCallbacks(timeoutRunnable)
    }

    private fun hideOverlay() {
        if (overlay.visibility != View.GONE) overlay.visibility = View.GONE
    }

    // ------------------------------------------------------------------ 网络：热点没外网也要能连机器人

    /**
     * 手机开的个人热点往往没有"互联网"，Android 会判定这个 Wi-Fi 不可用，
     * 把 App 的请求转到移动数据 -> http://172.20.10.10:5000 永远打不开。
     * 这里把本进程的默认网络绑到 Wi-Fi 上（只影响本 App）。
     */
    private fun keepWifiAsProcessNetwork() {
        if (!BIND_WIFI_NETWORK) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    cm.bindProcessToNetwork(network)
                    boundNetwork = network
                    Log.i(TAG, "默认网络已绑定到 Wi-Fi: $network")
                    if (failureShown) runOnUiThread { load() }
                } catch (e: Exception) {
                    Log.w(TAG, "绑定 Wi-Fi 失败", e)
                }
            }

            override fun onLost(network: Network) {
                if (boundNetwork == network) {
                    try {
                        cm.bindProcessToNetwork(null)
                    } catch (e: Exception) {
                        Log.w(TAG, "解绑 Wi-Fi 失败", e)
                    }
                    boundNetwork = null
                    Log.w(TAG, "Wi-Fi 断开，等待重连")
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "注册网络回调失败", e)
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("127.")) return true
        if (host.endsWith(".local") || host.endsWith(".lan")) return true
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    // ------------------------------------------------------------------ 按键

    /**
     * 音量上 = 改地址；音量下 = 刷新（就是"浏览器那个刷新按钮"）。
     *
     * 用 dispatchKeyEvent 而不是 onKeyDown：页面里的 <video> 播放时会先把音量键吃掉，
     * 走 onKeyDown 有可能收不到。
     * 代价是本 App 内音量键不再调音量（控制类 App 通常也不需要）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    showSettingsDialog()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    reloadNow()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val edit = view.findViewById<EditText>(R.id.editUrl)
        edit.setText(targetUrl)
        edit.setSelection(edit.text.length)

        val chkMagnifier = view.findViewById<CheckBox>(R.id.chkMagnifier)
        chkMagnifier.isChecked = prefs.getBoolean(KEY_MAG_HANDLE, true)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.save_and_reload, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.refresh) { _, _ -> reloadNow() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = edit.text.toString().trim()
                if (!input.startsWith("http://") && !input.startsWith("https://")) {
                    edit.error = "地址需要以 http:// 或 https:// 开头"
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString(KEY_URL, input)
                    .putBoolean(KEY_MAG_HANDLE, chkMagnifier.isChecked)
                    .apply()
                dialog.dismiss()
                reloadNow()
            }
        }
        dialog.setOnDismissListener { enterImmersiveMode() }
        dialog.show()
    }

    private fun showFirstRunHint() {
        if (!prefs.getBoolean(KEY_FIRST_RUN, true)) return
        prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply()
        Toast.makeText(
            this,
            "机器人没开机时连不上是正常的，会自动重试。\n音量下=刷新　音量上=改地址",
            Toast.LENGTH_LONG
        ).show()
    }

    @Deprecated("平台迁移到 OnBackPressedDispatcher 前继续沿用")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        if (exitArmed) {
            super.onBackPressed()
            return
        }
        exitArmed = true
        Toast.makeText(this, "再按一次返回键退出（退出前请先在页面里停车）", Toast.LENGTH_SHORT).show()
        ui.postDelayed({ exitArmed = false }, 2500)
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun enableWebViewDebugging() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        networkCallback?.let { callback ->
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
                getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
            } catch (e: Exception) {
                Log.w(TAG, "清理网络回调失败", e)
            }
        }
        networkCallback = null
        boundNetwork = null

        // 不要在 onPause/onStop 里调 webView.onPause()：
        // 否则视频流和页面定时器被冻结，回到前台时界面状态是过期的。
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
