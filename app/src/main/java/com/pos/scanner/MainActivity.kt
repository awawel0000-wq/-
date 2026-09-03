package com.pos.scanner

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.util.Size
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var dotConnectionStatus: View
    private lateinit var txtConnectionStatus: TextView
    private lateinit var btnTorch: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var layoutPendingQueue: LinearLayout
    private lateinit var txtPendingCount: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var layoutSettings: LinearLayout
    private lateinit var edtServerIp: EditText
    private lateinit var edtServerPort: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var txtTestResult: TextView
    private lateinit var btnSaveSettings: Button
    private lateinit var txtItemName: TextView
    private lateinit var txtItemDetails: TextView
    private lateinit var txtStatusBadge: TextView
    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()
    private var camera: Camera? = null
    private var isTorchOn = false
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0L
    private var isFrameClear: Boolean = true
    private var emptyFramesCount: Int = 0
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var isServerConnected = false
    private var toneGenerator: ToneGenerator? = null
    // الموقع (jawwal) داخل التطبيق
    private var web: android.webkit.WebView? = null
    private var btnSite: Button? = null
    private var siteLoaded = false
    // وضعُ المسح للموقع: عند طلبِ الموقعِ باركوداً، نُظهرُ الكاميرا فوقه ونحقنُ النتيجةَ فيه بدلاً من الكمبيوتر
    private var scanForSite = false
    private var scanSiteField = ""      // مُعرِّفُ الخانةِ في الموقع
    private var overlayScan: View? = null
    private var scanFrame: View? = null      // الإطارُ الأخضر (يتحرّك مع الكاميرا عند السحب)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            // STREAM_ALARM: أعلى صوتاً وأوضح — يتجاوز الوضع الصامت غالباً · المستوى 100 = الأقصى
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            try { toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) {}
        }
        // ارفع صوت الإنذار للأقصى برمجياً، فلا يفوت الكاشيرَ صوتُ المسح
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
        } catch (e: Exception) { e.printStackTrace() }
        prefs = getSharedPreferences("POS_SCANNER_CONFIG", Context.MODE_PRIVATE)
        initViews()
        loadSettings()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
        startHeartbeat()
    }
    // بعد منحِ إذنِ الكاميرا أوّلَ مرّة: شغّلِ الكاميرا فوراً (بلا حاجةٍ لإغلاقِ التطبيقِ وفتحِه)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }
    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        dotConnectionStatus = findViewById(R.id.dotConnectionStatus)
        txtConnectionStatus = findViewById(R.id.txtConnectionStatus)
        btnTorch = findViewById(R.id.btnTorch)
        // 🔄 زرُّ التحديث: يُعيدُ فحصَ الاتصال وحالةَ الاقتران بلا إعادةِ تشغيلِ البرنامج
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            txtItemName.text = "🔄 جارٍ تحديث الحالة…"
            txtItemDetails.text = "إعادة فحص الاتصال بالخادم"
            txtStatusBadge.text = "⏳ تحديث…"
            setBadgeStyle("#1E293B", "#38BDF8", "#334155")
            setDotColor("#F59E0B")
            refreshStatus()
        }
        btnSettings = findViewById(R.id.btnSettings)
        layoutPendingQueue = findViewById(R.id.layoutPendingQueue)
        txtPendingCount = findViewById(R.id.txtPendingCount)
        bottomBar = findViewById(R.id.bottomBar)
        layoutPendingQueue.visibility = View.GONE   // لا قائمةَ انتظار — أُلغِيَ الحفظُ المحلي
        layoutSettings = findViewById(R.id.layoutSettings)
        edtServerIp = findViewById(R.id.edtServerIp)
        edtServerPort = findViewById(R.id.edtServerPort)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        txtTestResult = findViewById(R.id.txtTestResult)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        txtItemName = findViewById(R.id.txtItemName)
        txtItemDetails = findViewById(R.id.txtItemDetails)
        txtStatusBadge = findViewById(R.id.txtStatusBadge)
        btnTorch.setOnClickListener { toggleTorch() }
        btnSettings.setOnClickListener {
            layoutSettings.visibility = if (layoutSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            txtTestResult.visibility = View.GONE
        }
        btnTestConnection.setOnClickListener { testServerConnection() }
        btnSaveSettings.setOnClickListener {
            val ip = edtServerIp.text.toString().trim()
            val port = edtServerPort.text.toString().trim()
            prefs.edit().putString("server_ip", ip).putString("server_port", port).apply()
            layoutSettings.visibility = View.GONE
            Toast.makeText(this, "تم حفظ الإعدادات بنجاح!", Toast.LENGTH_SHORT).show()
            checkServerStatus()
        }
        // زر: أعد الربط بمسح رمز QR — يقفل اللوحة ويوجّه الكاميرا للرمز (تُلتقط تلقائياً)
        findViewById<Button>(R.id.btnScanLink).setOnClickListener {
            layoutSettings.visibility = View.GONE
            txtItemName.text = "📷 وجّه الكاميرا لرمز الربط"
            txtItemDetails.text = "امسح QR الجهاز الجديد — يقترن فوراً"
            txtStatusBadge.text = "بانتظار رمز الربط…"
            setBadgeStyle("#1E293B", "#38BDF8", "#334155")
            Toast.makeText(this, "وجّه الكاميرا نحو رمز الربط", Toast.LENGTH_LONG).show()
        }
        setDotColor("#EF4444")
        setBadgeStyle("#1E293B", "#38BDF8", "#334155")

        // زر الانتقال إلى الموقع
        web = findViewById(R.id.web)
        btnSite = findViewById(R.id.btnSite)
        btnSite?.setOnClickListener { openSite() }
        // جسرٌ بين الموقع (jawwal) والتطبيق
        web?.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun backToScanner() { runOnUiThread { closeSite() } }
            // يطلبه زرُّ الباركود في الموقع: يُظهر مستطيلَ الكاميرا فوق الموقع لخانةٍ محدّدة
            @android.webkit.JavascriptInterface
            fun scanToField(fieldId: String) { runOnUiThread { startSiteScan(fieldId) } }
            // يناديه الموقعُ بعد نجاحِ الصنف: أغلقِ الكاميرا (المسحُ اكتمل بنجاح)
            @android.webkit.JavascriptInterface
            fun closeScan() { runOnUiThread { if (scanForSite) { playToneSuccess(); stopSiteScan() } } }
        }, "AndroidApp")
    }

    /** يُظهر الكاميرا (الماسح السريع) ملءَ الشاشة فوق الموقع — قراءةٌ سهلةٌ قويّة. */
    private fun startSiteScan(fieldId: String) {
        scanForSite = true
        scanSiteField = fieldId
        lastScannedCode = null; lastScanTime = 0L; isFrameClear = true
        // الكاميرا ملءُ الشاشة أوّلاً، ثمّ الطبقةُ الشفّافةُ (الإطار+الأزرار) فوقها لتظهرَ الأزرار
        previewView.visibility = View.VISIBLE
        previewView.bringToFront()
        val ov = overlayScan ?: buildScanOverlay().also { overlayScan = it }
        ov.visibility = View.VISIBLE
        ov.bringToFront()   // الطبقةُ فوق الكاميرا (شفّافةٌ فتظهرُ الكاميرا، والأزرارُ عليها)
    }

    private fun stopSiteScan() {
        scanForSite = false; scanSiteField = ""
        overlayScan?.visibility = View.GONE
        web?.bringToFront()
    }

    /** طبقةٌ شفّافةٌ فوق الكاميرا: إطارٌ أخضرُ (منطقةُ القراءة) + تلميحٌ + زرُّ إلغاء. */
    private fun buildScanOverlay(): View {
        val fl = android.widget.FrameLayout(this)
        fl.setBackgroundColor(Color.TRANSPARENT)   // شفّافٌ — الكاميرا ملءُ الشاشة خلفه
        fl.layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
        // إطارٌ أخضرُ في الوسط — دليلُ التصويب فقط (لا يحدُّ القراءة؛ الماسح يقرأ ملءَ الشاشة)
        val frame = View(this)
        val fp = android.widget.FrameLayout.LayoutParams(dp(300), dp(190))
        fp.gravity = android.view.Gravity.CENTER
        frame.layoutParams = fp
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat()
            setStroke(dp(3), Color.parseColor("#26D07C")); setColor(Color.TRANSPARENT)
        }
        scanFrame = frame
        // زرُّ الكشّاف (الإضاءة) — أعلى اليسار
        val torch = Button(this)
        torch.text = "💡"; torch.textSize = 18f
        torch.setBackgroundColor(Color.parseColor("#CC1C6FBF"))
        val tp = android.widget.FrameLayout.LayoutParams(dp(52), dp(46))
        tp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        tp.topMargin = dp(40); tp.leftMargin = dp(16)
        torch.layoutParams = tp
        torch.setOnClickListener { toggleTorch() }
        // زرُّ الإلغاء — أسفل الوسط
        val close = Button(this)
        close.text = "إلغاء"; close.setTextColor(Color.WHITE)
        close.setBackgroundColor(Color.parseColor("#CC7F1D1D"))
        val cp = android.widget.FrameLayout.LayoutParams(-2, dp(46))
        cp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        cp.bottomMargin = dp(48)
        close.layoutParams = cp
        close.setOnClickListener { stopSiteScan() }
        fl.addView(frame); fl.addView(torch); fl.addView(close)
        (window.decorView as android.view.ViewGroup).addView(fl)
        return fl
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** يفتح الموقع (jawwal) داخل التطبيق فوق شاشة الماسح. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openSite() {
        val w = web ?: return
        if (!siteLoaded) {
            val s = w.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.mediaPlaybackRequiresUserGesture = false   // تشغيل الكاميرا بلا لمسة إضافيّة
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            w.webViewClient = android.webkit.WebViewClient()
            w.loadUrl("${getServerUrl()}/static/m/jawwal.html")
            siteLoaded = true
        }
        w.visibility = View.VISIBLE
        // نحن الآن داخل الموقع: أخفِ أزرار الماسح (العودة تكون من زرٍّ داخل الموقع أو زرّ الرجوع)
        btnSite?.visibility = View.GONE
        btnSettings.visibility = View.GONE
        btnTorch.visibility = View.GONE
        findViewById<View>(R.id.btnRefresh).visibility = View.GONE
    }

    /** يعود من الموقع إلى شاشة الماسح. */
    private fun closeSite() {
        val w = web ?: return
        if (scanForSite) stopSiteScan()   // احتياطاً: أوقفْ وضعَ مسح الموقع إن كان مفعّلاً
        w.visibility = View.GONE
        // عُدنا للماسح: أرجِع أزراره
        btnSite?.visibility = View.VISIBLE
        btnSettings.visibility = View.VISIBLE
        btnTorch.visibility = View.VISIBLE
        findViewById<View>(R.id.btnRefresh).visibility = View.VISIBLE
        // إصلاحُ الطبقات: الـ web كان مرفوعاً foreground فوق المستطيلِ والشريطِ السفلي.
        // نُعيدُ ترتيبَ العناصرِ الثابتةِ للأمامِ لتظهرَ فوراً بلا إغلاقِ التطبيقِ وفتحِه.
        previewView.bringToFront()                       // الكاميرا خلفية
        (findViewById<View>(R.id.scanBox))?.bringToFront() // المستطيلُ الأخضرُ الثابت
        bottomBar.bringToFront()                          // شريطُ البياناتِ السفلي
        btnSite?.bringToFront(); btnSettings.bringToFront(); btnTorch.bringToFront()
        findViewById<View>(R.id.btnRefresh).bringToFront()
        layoutPendingQueue.bringToFront()
        val root = w.parent as? android.view.ViewGroup
        root?.requestLayout(); root?.invalidate()          // إجبارُ إعادةِ الرسمِ فوراً
    }

    private fun loadSettings() {
        edtServerIp.setText(prefs.getString("server_ip", "192.168.1.100"))
        edtServerPort.setText(prefs.getString("server_port", "5005"))
    }
    private fun getServerUrl(): String {
        val ip = prefs.getString("server_ip", "192.168.1.100")?.trim() ?: "192.168.1.100"
        val port = prefs.getString("server_port", "5005")?.trim() ?: "5005"
        return "http://$ip:$port"
    }
    private fun setDotColor(colorHex: String) {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
        }
        dotConnectionStatus.background = shape
    }
    private fun setBadgeStyle(bgColorHex: String, textColorHex: String, strokeColorHex: String) {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(Color.parseColor(bgColorHex))
            setStroke(2, Color.parseColor(strokeColorHex))
        }
        txtStatusBadge.background = shape
        txtStatusBadge.setTextColor(Color.parseColor(textColorHex))
    }
    private fun startHeartbeat() {
        heartbeatHandler.post(object : Runnable {
            override fun run() {
                checkServerStatus()
                heartbeatHandler.postDelayed(this, 4000)
            }
        })
    }
    private fun checkServerStatus() {
        val testUrl = "${getServerUrl()}/api/scan"
        val request = Request.Builder().url(testUrl).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateConnectionUi(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val connected = response.code in 200..499
                updateConnectionUi(connected)
                // لا نُرسلُ المعلّقاتِ تلقائياً — نُنبّهُ فقط ليُرسلَها الكاشيرُ بضغطةٍ واعيةٍ
                //   (منعاً لحقنِها في فاتورةٍ خطأٍ لو تغيّرتِ الفاتورةُ أثناءَ الانقطاع).
            }
        })
    }
    // 🔄 تحديثٌ يدويٌّ بنتيجةٍ صريحةٍ (بلا إعادةِ تشغيلِ البرنامج).
    private fun refreshStatus() {
        val testUrl = "${getServerUrl()}/api/scan"
        val request = Request.Builder().url(testUrl).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateConnectionUi(false)
                val paired = prefs.getBoolean("is_paired", false)
                runOnUiThread {
                    playToneWarning(); vibrateWarning()
                    if (paired) {
                        txtItemName.text = "🔴 غير متصل بالخادم"
                        txtItemDetails.text = "تأكّد أن الجوال والكمبيوتر على نفس الشبكة والبرنامج يعمل"
                        txtStatusBadge.text = "❌ لا يوجد اتصال — أعد المحاولة"
                    } else {
                        txtItemName.text = "🔴 لست مقترناً بالخادم"
                        txtItemDetails.text = "اضغط \"أعد الربط\" وامسح رمز الربط (QR) من الكمبيوتر"
                        txtStatusBadge.text = "❌ يلزم الاقتران أولاً"
                    }
                    setBadgeStyle("#7F1D1D", "#EF4444", "#DC2626")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val connected = response.code in 200..499
                updateConnectionUi(connected)   // يضبطُ is_paired=true عند النجاح
                runOnUiThread {
                    if (connected) {
                        playToneSuccess(); vibrateSuccess()
                        txtItemName.text = "✅ متصل بالخادم — جاهز للمسح"
                        txtItemDetails.text = "الحالة مُحدّثة"
                        txtStatusBadge.text = "🔗 متصلٌ وجاهز"
                        setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                    } else {
                        playToneWarning(); vibrateWarning()
                        txtItemName.text = "⚠️ الخادم ردّ بخطأ (${response.code})"
                        txtItemDetails.text = "تأكّد أن البرنامج يعمل على الكمبيوتر"
                        txtStatusBadge.text = "❌ اتصالٌ غيرُ مكتمل"
                        setBadgeStyle("#7F1D1D", "#EF4444", "#DC2626")
                    }
                }
            }
        })
    }
    private fun updateConnectionUi(connected: Boolean) {
        isServerConnected = connected
        // أيُّ اتصالٍ ناجحٍ فعليٍّ = مقترنٌ (يفتحُ المسح). يشملُ الفحصَ الدوريَّ والاختبارَ اليدوي.
        if (connected) prefs.edit().putBoolean("is_paired", true).apply()
        runOnUiThread {
            if (connected) {
                setDotColor("#22C55E")
                txtConnectionStatus.text = "متصل بنظام الأوائل ✅"
                txtConnectionStatus.setTextColor(Color.parseColor("#22C55E"))
            } else {
                setDotColor("#EF4444")
                txtConnectionStatus.text = "غير متصل بالخادم 🔴"
                txtConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
            }
        }
    }
    private fun testServerConnection() {
        txtTestResult.visibility = View.VISIBLE
        txtTestResult.text = "جاري فحص الاتصال..."
        txtTestResult.setTextColor(Color.parseColor("#38BDF8"))
        val ip = edtServerIp.text.toString().trim()
        val port = edtServerPort.text.toString().trim()
        val testUrl = "http://$ip:$port/api/scan"
        val request = Request.Builder().url(testUrl).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    txtTestResult.text = "❌ تعذر الاتصال! تأكد أن الخادم يعمل على بورت $port"
                    txtTestResult.setTextColor(Color.parseColor("#EF4444"))
                }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    txtTestResult.text = "✅ تم الاتصال بنجاح بخادم الأوائل!"
                    txtTestResult.setTextColor(Color.parseColor("#22C55E"))
                }
            }
        })
    }
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val scanner = BarcodeScanning.getClient()
            val cameraExecutor = Executors.newSingleThreadExecutor()
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val now = System.currentTimeMillis()
                            if (barcodes.isEmpty()) {
                                emptyFramesCount++
                                if (emptyFramesCount >= 3) {
                                    isFrameClear = true
                                }
                            } else {
                                emptyFramesCount = 0
                                val barcode = barcodes[0]
                                val code = barcode.rawValue ?: return@addOnSuccessListener
                                val isDifferentCode = (code != lastScannedCode)
                                val hasLeftAndReturned = (isFrameClear && (now - lastScanTime > 1200))
                                val isCooldownPassed = (now - lastScanTime > 3500)
                                if (isDifferentCode || hasLeftAndReturned || isCooldownPassed) {
                                    lastScannedCode = code
                                    lastScanTime = now
                                    isFrameClear = false
                                    onBarcodeDetected(code)
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            btnTorch.setImageResource(if (isTorchOn) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_day)
        } else {
            Toast.makeText(this, "الفلاش غير متاح", Toast.LENGTH_SHORT).show()
        }
    }
    // 🔗 يفكّكُ رمزَ الربط ويحفظُ عنوانَ الكمبيوتر ورمزَ الكاشير، ثمّ يتصلُ فوراً.
    private fun handleLinkQR(code: String) {
        try {
            val uri = android.net.Uri.parse(code)
            val ip = uri.getQueryParameter("ip")
            val port = uri.getQueryParameter("port") ?: "5005"
            val sid = uri.getQueryParameter("sid") ?: "default"
            // 🔑 مفتاحُ الجلسة (اختياريّ): يأتي في رمز الربطِ الجديد. القديمُ بلا مفتاحٍ = ""
            //    فيبقى العملُ كما كان حتى يُفعّلَ المالكُ فرضَ المفتاحِ من لوحته.
            val token = uri.getQueryParameter("token") ?: ""
            if (ip.isNullOrBlank()) {
                runOnUiThread { playToneWarning(); vibrateWarning()
                    txtItemName.text = "⚠️ رمز ربط غير صالح"; txtItemDetails.text = "لا يحتوي عنوان الكمبيوتر" }
                return
            }
            prefs.edit()
                .putString("server_ip", ip)
                .putString("server_port", port)
                .putString("session_id", sid)
                .putString("scan_token", token)
                .apply()
            runOnUiThread {
                // حُفظ العنوان — لكن لا نُعلنُ النجاحَ قبلَ التحقّقِ الفعليِّ من الخادم
                edtServerIp.setText(ip); edtServerPort.setText(port)
                txtItemName.text = "⏳ جارٍ التحقّق من الاتصال…"
                txtItemDetails.text = "الكاشير: $sid  |  $ip:$port"
                txtStatusBadge.text = "⏳ فحص الاتصال بالكمبيوتر…"
                setBadgeStyle("#1E293B", "#38BDF8", "#334155")
                layoutSettings.visibility = View.GONE
            }
            // 🔎 تحقّقٌ حقيقيّ: الاقترانُ ناجحٌ فقط إن ردَّ الخادم
            verifyLinkConnection(ip, port, sid)
        } catch (e: Exception) {
            runOnUiThread { playToneError(); vibrateError()
                txtItemName.text = "🔴 تعذّر قراءة رمز الربط"; txtItemDetails.text = e.message ?: "" }
        }
    }

    // 🔎 يتحقّقُ فعلياً من وصولِ الخادم بعدَ قراءةِ رمزِ الربط.
    // النجاحُ (صوتٌ أخضرُ ورسالةُ "تم الربط") لا يظهرُ إلا إن ردَّ الكمبيوتر فعلاً.
    private fun verifyLinkConnection(ip: String, port: String, sid: String) {
        val testUrl = "http://$ip:$port/api/scan"
        val request = Request.Builder().url(testUrl).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // العنوانُ محفوظٌ لكنِ الخادمُ لا يردّ — نصارحُ المستخدمَ بلا ادّعاءِ نجاح
                updateConnectionUi(false)
                runOnUiThread {
                    playToneWarning(); vibrateWarning()
                    txtItemName.text = "⚠️ حُفظ العنوان — لكن لا يوجد اتصال"
                    txtItemDetails.text = "الكمبيوتر ($ip) لا يردّ. تأكّد: الجوّال والكمبيوتر على نفس الواي فاي، والبرنامج يعمل."
                    txtStatusBadge.text = "🔴 غير متصل — أعد المحاولة بعد التأكّد من الشبكة"
                    setBadgeStyle("#450A0A", "#F87171", "#EF4444")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val connected = response.code in 200..499
                updateConnectionUi(connected)
                runOnUiThread {
                    if (connected) {
                        prefs.edit().putBoolean("is_paired", true).apply()   // اقترانٌ حقيقيٌّ مؤكّد
                        playToneSuccess(); vibrateSuccess()
                        txtItemName.text = "✅ تم الربط والاتصال بالخادم"
                        txtItemDetails.text = "الكاشير: $sid  |  $ip:$port"
                        txtStatusBadge.text = "🔗 مقترنٌ ومتصلٌ بالكاشير ($sid)"
                        setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                        Toast.makeText(this@MainActivity, "تم ربط الجوّال والاتصال بالكاشير: $sid", Toast.LENGTH_LONG).show()
                        // لا إرسالَ تلقائياً للمعلّقات — الكاشيرُ يُرسلُها بضغطةٍ واعيةٍ على الشريط
                        //   بعد التأكّدِ أنّ الفاتورةَ الصحيحةَ مفتوحة (منعَ الحقنِ في فاتورةٍ خطأ).
                    } else {
                        playToneWarning(); vibrateWarning()
                        txtItemName.text = "⚠️ حُفظ العنوان — الخادم ردّ بخطأ"
                        txtItemDetails.text = "استجابة غير متوقعة (${response.code}). تأكّد أن البرنامج يعمل على المنفذ $port."
                        txtStatusBadge.text = "🔴 اتصالٌ غيرُ مكتمل"
                        setBadgeStyle("#450A0A", "#F87171", "#EF4444")
                    }
                }
            }
        })
    }

    private fun onBarcodeDetected(code: String) {
        // 🔗 رمزُ ربطٍ (QR من شاشة /link في الكمبيوتر)؟ عالِجه كإعداداتٍ لا كباركودِ صنف.
        if (code.startsWith("awael://link")) {
            handleLinkQR(code)
            return
        }
        // 📲 وضعُ الموقع: نحقنُ الباركودَ في خانةِ الموقع (jawwal). لا نُغلقُ الكاميرا هنا —
        //   الموقعُ هو مَن يقرّر: صنفٌ موجودٌ ⇒ ينادي AndroidApp.closeScan() فنُغلق؛ غيرُ موجودٍ ⇒ تبقى مفتوحةً للمسح الصحيح.
        if (scanForSite) {
            val field = scanSiteField
            runOnUiThread {
                vibrateSuccess()
                val safe = code.replace("\\", "\\\\").replace("'", "\\'")
                val fid = field.replace("\\", "\\\\").replace("'", "\\'")
                web?.evaluateJavascript("window.awaelScanInject && window.awaelScanInject('$safe','$fid');", null)
            }
            return
        }
        // 🔒 بوابةُ الوضوح: لا نُرسلُ ونحن غيرُ مقترنين — نُصارحُ الكاشيرَ بالسبب مباشرةً.
        val isPaired = prefs.getBoolean("is_paired", false)
        if (!isPaired) {
            runOnUiThread {
                playToneError(); vibrateError()
                txtItemName.text = "🔴 فشل — لست مقترناً بالخادم"
                txtItemDetails.text = "الباركود: $code — يجب الاقتران أولاً"
                txtStatusBadge.text = "❌ اضغط \"أعد الربط\" وامسح رمز الربط (QR) من الكمبيوتر"
                setBadgeStyle("#7F1D1D", "#EF4444", "#DC2626")
            }
            return
        }
        val targetUrl = "${getServerUrl()}/api/scan"
        // نرسل sid مع الباركود ليصل للكاشير المقترن به هذا الجوّال (عزلُ الأجهزة المتعدّدة).
        val sid = prefs.getString("session_id", "default") ?: "default"
        // 🔑 مفتاحُ الجلسة — يُرسَل مع كلّ مسح إن وُجد؛ فارغٌ = التطبيقُ لم يُقترن بمفتاحٍ بعد
        //    (يُرفض المسحُ فقط إن فعّل المالكُ الفرضَ في لوحته).
        val token = prefs.getString("scan_token", "") ?: ""
        val jsonPayload = JSONObject().apply {
            put("barcode", code); put("sid", sid)
            if (token.isNotBlank()) put("token", token)
        }
        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(targetUrl).post(requestBody).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // لا حفظَ محلياً: فشلٌ صريحٌ ⇒ يُعيدُ الكاشيرُ المسحَ بعدَ رجوعِ الاتصال
                runOnUiThread {
                    playToneError()
                    vibrateError()
                    txtItemName.text = "🔴 فشل — غير متصل بالخادم"
                    txtItemDetails.text = "الباركود: $code — تأكّد أن الجوال والكمبيوتر على نفس الشبكة والبرنامج يعمل، ثم أعِد المسح"
                    txtStatusBadge.text = "❌ لم يُرسَل — انقطاع الاتصال بالخادم"
                    setBadgeStyle("#7F1D1D", "#EF4444", "#DC2626")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                try {
                    val resJson = JSONObject(responseBody)
                    val isFound = resJson.optBoolean("found", true)
                    val itemName = resJson.optString("item_name", resJson.optString("name", "صنف: $code"))
                    val itemPrice = resJson.optString("price", "")
                    runOnUiThread {
                        if (response.isSuccessful && isFound) {
                            playToneSuccess()
                            vibrateSuccess()
                            txtItemName.text = itemName
                            txtItemDetails.text = if (itemPrice.isNotEmpty()) "السعر: $itemPrice ₪  |  الباركود: $code" else "الباركود: $code"
                            txtStatusBadge.text = "✅ تم الإرسال والإضافة للفاتورة"
                            setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                        } else if (!isFound || response.code == 404) {
                            playToneWarning()
                            vibrateWarning()
                            txtItemName.text = "⚠️ صنف غير معرّف!"
                            txtItemDetails.text = "الباركود: $code"
                            txtStatusBadge.text = "لا يوجد صنف بهذا الباركود في النظام"
                            setBadgeStyle("#78350F", "#F59E0B", "#D97706")
                        } else {
                            playToneSuccess()
                            vibrateSuccess()
                            txtItemName.text = "الباركود: $code"
                            txtItemDetails.text = "تم الاستلام بنجاح"
                            txtStatusBadge.text = "✅ تم النقل بنجاح"
                            setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                        }
                    }
                } catch (e: Exception) {
                    // الردُّ وصلَ لكن تعذّرَ تحليلُه. لا نَكذِبُ بالنجاح:
                    //   نجاحٌ فقط إن كانتِ الاستجابةُ ناجحةً فعلاً (2xx)؛ وإلا نحفظُ في الانتظار.
                    if (response.isSuccessful) {
                        runOnUiThread {
                            playToneSuccess(); vibrateSuccess()
                            txtItemName.text = "الباركود: $code"
                            txtItemDetails.text = "تم الاستلام بنجاح"
                            txtStatusBadge.text = "✅ تم الاستلام بنجاح"
                            setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                        }
                    } else {
                        // لا حفظَ محلياً: فشلٌ صريحٌ ⇒ يُعيدُ الكاشيرُ المسح
                        runOnUiThread {
                            playToneError(); vibrateError()
                            txtItemName.text = "🔴 فشل الإرسال — الخادم ردّ بخطأ (${response.code})"
                            txtItemDetails.text = "الباركود: $code — أعِد المسح"
                            txtStatusBadge.text = "❌ لم يُرسَل — أعد المسح"
                            setBadgeStyle("#7F1D1D", "#EF4444", "#DC2626")
                        }
                    }
                }
            }
        })
    }
    // (أُلغِيَ الحفظُ المحليُّ للمسحاتِ نهائياً: لا قائمةَ انتظار، لا إرسالَ مؤجّل.
    //  فشلُ الاتصالِ يُعرَضُ صريحاً ويُعيدُ الكاشيرُ المسح — تفادياً لحقنِ مسحاتٍ في فاتورةٍ خطأ.)
    // 🔊 نجاح: نغمتان صاعدتان واضحتان (بِيب-بِيب) — يسمعها الكاشيرُ بلا نظرٍ للشاشة.
    private fun playToneSuccess() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
            heartbeatHandler.postDelayed({
                try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150) } catch (e: Exception) {}
            }, 160)
        } catch (e: Exception) {}
    }
    // ⚠️ صنفٌ غير معرّف: نغمةُ تنبيهٍ متوسطةٌ مختلفةٌ عن النجاحِ والفشل.
    private fun playToneWarning() {
        try { toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 500) } catch (e: Exception) {}
    }
    // 🔴 فشلُ الشبكة: نغمةُ إنذارٍ طويلةٌ قويّةٌ مميّزةٌ جداً — لا تُخطئها الأذن.
    private fun playToneError() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 400)
            heartbeatHandler.postDelayed({
                try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500) } catch (e: Exception) {}
            }, 420)
        } catch (e: Exception) {}
    }
    private fun vibrateSuccess() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(90)
        }
    }
    private fun vibrateWarning() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val pattern = longArrayOf(0, 100, 80, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator?.vibrate(pattern, -1)
        }
    }
    private fun vibrateError() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        // اهتزازٌ ثلاثيٌّ قويٌّ طويل — يميّز الفشلَ حتى في ضجيج المتجر.
        val pattern = longArrayOf(0, 400, 150, 400, 150, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator?.vibrate(pattern, -1)
        }
    }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    // زرُّ الرجوع: إن كان الموقع مفتوحاً تنقّل داخله ثم عُد للماسح
    override fun onBackPressed() {
        // إن كانت كاميرا المسح (وضع الموقع) مفتوحةً ⇒ أغلقها أوّلاً (وأوقفْ وضعَ المسح)
        if (scanForSite) { stopSiteScan(); return }
        val w = web
        if (w != null && w.visibility == View.VISIBLE) { closeSite(); return }
        super.onBackPressed()
    }
    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
    }
}
