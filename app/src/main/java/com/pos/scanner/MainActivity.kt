package com.pos.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * نظام الأوائل — واجهة WebView.
 * التطبيق يفتح نظام الجوّال (scan.html ← الماسح، ومنه الموقع jawwal.html) داخل WebView واحد.
 * لا ماسح كاميرا في Kotlin: النظام يمسح الباركود بنفسه (محرّك ZXing المحليّ داخل الصفحة).
 * دور Kotlin: (1) معرفة عنوان الكمبيوتر مرّة، (2) منح WebView صلاحية الكاميرا والسياق الآمن على http.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var setup: ScrollView
    private lateinit var edtIp: EditText
    private lateinit var edtPort: EditText

    // طلبُ صلاحيةِ كاميرا معلّقٌ من WebView (للماسح داخل الصفحة)
    private var pendingCamRequest: PermissionRequest? = null
    // عنوان السيرفر الحقيقيّ الذي يوجَّه إليه كلّ طلبٍ من الأصل الآمن
    private var serverBase: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("POS_SCANNER_CONFIG", Context.MODE_PRIVATE)

        web = findViewById(R.id.web)
        setup = findViewById(R.id.setup)
        edtIp = findViewById(R.id.edtIp)
        edtPort = findViewById(R.id.edtPort)

        edtIp.setText(prefs.getString("server_ip", ""))
        edtPort.setText(prefs.getString("server_port", "5005"))

        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            val ip = edtIp.text.toString().trim()
            val port = edtPort.text.toString().trim().ifEmpty { "5005" }
            if (ip.isEmpty()) { Toast.makeText(this, "أدخِل عنوان الكمبيوتر (IP)", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            prefs.edit().putString("server_ip", ip).putString("server_port", port).apply()
            openSystem(ip, port)
        }

        configureWebView()

        // إن سبق الربط: افتح النظام مباشرة. وإلا اعرض شاشة الربط.
        val savedIp = prefs.getString("server_ip", "") ?: ""
        val savedPort = prefs.getString("server_port", "5005") ?: "5005"
        if (savedIp.isNotEmpty()) openSystem(savedIp, savedPort)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // فعّل حفظ الكوكيز (جلسة تسجيل الدخول)
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        try { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) } catch (_: Exception) {}

        val s: WebSettings = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true                 // jawwal يستخدم localStorage للتفضيلات
        s.mediaPlaybackRequiresUserGesture = false  // تشغيل الكاميرا بلا لمسة إضافيّة
        s.allowFileAccess = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.textZoom = 100
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // نخدم النظام عبر أصلٍ https وهميّ (سياقٌ آمن) ونوجّه كلّ طلباته للسيرفر الحقيقيّ عبر http.
        // هكذا تعمل الكاميرا (getUserMedia) على كلّ جهازٍ دون الحاجة لـhttps حقيقيّ على الخادم.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith(SECURE_ORIGIN) || url.startsWith("http://") || url.startsWith("https://")) return false
                return try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))); true }
                catch (e: Exception) { true }
            }

            // اعتراضُ كلّ طلبات الأصل الآمن وجلبُها من السيرفر الحقيقيّ (http://IP:port).
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                if (!u.startsWith(SECURE_ORIGIN)) return null
                val path = u.substring(SECURE_ORIGIN.length)  // يبدأ بـ /
                return proxyToServer(req.method ?: "GET", path, req.requestHeaders)
            }
        }

        // منح صلاحية الكاميرا لصفحة النظام (الماسح الداخليّ) + السياق الآمن على http.
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsCamera = request.resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                    if (wantsCamera) {
                        if (hasCameraPermission()) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        } else {
                            pendingCamRequest = request
                            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
                        }
                    } else {
                        request.grant(request.resources)
                    }
                }
            }
        }
    }

    private fun openSystem(ip: String, port: String) {
        serverBase = "http://$ip:$port"
        setup.visibility = View.GONE
        web.visibility = View.VISIBLE
        // نفتح عبر الأصل الآمن (https وهميّ) → سياقٌ آمن → الكاميرا تعمل.
        // نقطة البداية: الموقع (jawwal) — ومنه زرُّ «→ الماسح» عند الحاجة للإدخال للكمبيوتر.
        web.loadUrl("$SECURE_ORIGIN/static/m/jawwal.html")
    }

    /** يجلب المسار من السيرفر الحقيقيّ ويعيده لـWebView كأنّه من الأصل الآمن. */
    private fun proxyToServer(method: String, path: String, headers: Map<String, String>?): WebResourceResponse? {
        val base = serverBase ?: return null
        return try {
            val target = URL(base + path)
            val conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000; readTimeout = 20000
                instanceFollowRedirects = false   // نعالج التحويل يدويّاً لنحفظ الكوكيز
                headers?.forEach { (k, v) -> try { setRequestProperty(k, v) } catch (_: Exception) {} }
            }
            // أرسِل كوكيز الجلسة المحفوظة للسيرفر (تسجيل الدخول)
            val cm = android.webkit.CookieManager.getInstance()
            cm.getCookie(base)?.let { if (it.isNotBlank()) conn.setRequestProperty("Cookie", it) }
            conn.connect()

            val code = conn.responseCode
            // احفظ أيّ كوكيز يرجّعها السيرفر (Set-Cookie) في مخزن WebView
            conn.headerFields?.forEach { (k, vs) ->
                if (k != null && k.equals("Set-Cookie", true)) vs.forEach { sc -> try { cm.setCookie(base, sc) } catch (_: Exception) {} }
            }
            try { cm.flush() } catch (_: Exception) {}

            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            try { stream?.close() } catch (_: Exception) {}

            val ctypeRaw = conn.contentType ?: "application/octet-stream"
            val mime = ctypeRaw.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val enc = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ctypeRaw)?.groupValues?.get(1)?.trim() ?: "utf-8"
            val reason = (conn.responseMessage ?: "OK").ifEmpty { "OK" }
            val respHeaders = hashMapOf("Access-Control-Allow-Origin" to "*")
            // مرّر التحويلات (redirect) كرأس Location داخل الأصل الآمن نفسه
            if (code in 300..399) {
                conn.getHeaderField("Location")?.let { loc ->
                    val rel = if (loc.startsWith(base)) loc.substring(base.length) else if (loc.startsWith("http")) loc else loc
                    respHeaders["Location"] = if (rel.startsWith("/")) SECURE_ORIGIN + rel else rel
                }
            }
            WebResourceResponse(mime, enc, code, reason, respHeaders, ByteArrayInputStream(bytes))
        } catch (e: Exception) { null }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingCamRequest?.let { req ->
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
            }
            pendingCamRequest = null
            if (!granted) Toast.makeText(this, "الماسح يحتاج إذن الكاميرا", Toast.LENGTH_LONG).show()
        }
    }

    // زرُّ الرجوع: تنقّلٌ داخل النظام؛ وإن كنّا في أوّل صفحةٍ عُد لشاشة الربط.
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack()
        else if (web.visibility == View.VISIBLE) {
            web.visibility = View.GONE
            setup.visibility = View.VISIBLE
        } else super.onBackPressed()
    }

    companion object {
        private const val REQ_CAM = 2001
        // أصلٌ https وهميّ محليّ = سياقٌ آمن تعمل فيه الكاميرا. لا يخرج للإنترنت — كلّه يُوجَّه للسيرفر.
        private const val SECURE_ORIGIN = "https://appassets.androidplatform.net"
    }
}
