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
    private val offlineQueue = mutableListOf<String>()
    private var isProcessingQueue = false
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var isServerConnected = false
    private var toneGenerator: ToneGenerator? = null
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
        loadOfflineQueue()
        loadSettings()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
        startHeartbeat()
    }
    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        dotConnectionStatus = findViewById(R.id.dotConnectionStatus)
        txtConnectionStatus = findViewById(R.id.txtConnectionStatus)
        btnTorch = findViewById(R.id.btnTorch)
        btnSettings = findViewById(R.id.btnSettings)
        layoutPendingQueue = findViewById(R.id.layoutPendingQueue)
        txtPendingCount = findViewById(R.id.txtPendingCount)
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
        setDotColor("#EF4444")
        setBadgeStyle("#1E293B", "#38BDF8", "#334155")
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
                if (connected && offlineQueue.isNotEmpty()) {
                    processOfflineQueue()
                }
            }
        })
    }
    private fun updateConnectionUi(connected: Boolean) {
        isServerConnected = connected
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
            if (ip.isNullOrBlank()) {
                runOnUiThread { playToneWarning(); vibrateWarning()
                    txtItemName.text = "⚠️ رمز ربط غير صالح"; txtItemDetails.text = "لا يحتوي عنوان الكمبيوتر" }
                return
            }
            prefs.edit()
                .putString("server_ip", ip)
                .putString("server_port", port)
                .putString("session_id", sid)
                .apply()
            runOnUiThread {
                // حدّث خانات الإعدادات إن كانت مفتوحة
                edtServerIp.setText(ip); edtServerPort.setText(port)
                playToneSuccess(); vibrateSuccess()
                txtItemName.text = "✅ تم الربط بنجاح"
                txtItemDetails.text = "الكاشير: $sid  |  $ip:$port"
                txtStatusBadge.text = "🔗 هذا الجوّال مقترنٌ بالكاشير ($sid)"
                setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                layoutSettings.visibility = View.GONE
                Toast.makeText(this, "تم ربط الجوّال بالكاشير: $sid", Toast.LENGTH_LONG).show()
            }
            checkServerStatus()
        } catch (e: Exception) {
            runOnUiThread { playToneError(); vibrateError()
                txtItemName.text = "🔴 تعذّر قراءة رمز الربط"; txtItemDetails.text = e.message ?: "" }
        }
    }

    private fun onBarcodeDetected(code: String) {
        // 🔗 رمزُ ربطٍ (QR من شاشة /link في الكمبيوتر)؟ عالِجه كإعداداتٍ لا كباركودِ صنف.
        //   الصيغة: awael://link?ip=..&port=..&sid=..  — يملأ العنوان ويتصل تلقائياً.
        if (code.startsWith("awael://link")) {
            handleLinkQR(code)
            return
        }
        val targetUrl = "${getServerUrl()}/api/scan"
        // نرسل sid مع الباركود ليصل للكاشير المقترن به هذا الجوّال (عزلُ الأجهزة المتعدّدة).
        val sid = prefs.getString("session_id", "default") ?: "default"
        val jsonPayload = JSONObject().apply { put("barcode", code); put("sid", sid) }
        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(targetUrl).post(requestBody).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                addToOfflineQueue(code)
                runOnUiThread {
                    playToneError()
                    vibrateError()
                    txtItemName.text = "🔴 فشل الاتصال بالشبكة!"
                    txtItemDetails.text = "الباركود: $code (تم حفظه في الانتظار)"
                    txtStatusBadge.text = "⚠️ تم الحفظ محلياً للإرسال التلقائي"
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
                    runOnUiThread {
                        playToneSuccess()
                        vibrateSuccess()
                        txtItemName.text = "الباركود: $code"
                        txtItemDetails.text = "تم الإرسال بنجاح"
                        txtStatusBadge.text = "✅ تم الاستلام بنجاح"
                        setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                    }
                }
            }
        })
    }
    private fun addToOfflineQueue(code: String) {
        synchronized(offlineQueue) {
            if (!offlineQueue.contains(code)) {
                offlineQueue.add(code)
                saveOfflineQueue()
            }
        }
        updateQueueUi()
    }
    private fun processOfflineQueue() {
        if (isProcessingQueue || offlineQueue.isEmpty()) return
        isProcessingQueue = true
        val queueCopy: List<String>
        synchronized(offlineQueue) {
            queueCopy = ArrayList(offlineQueue)
        }
        val sid = prefs.getString("session_id", "default") ?: "default"
        Executors.newSingleThreadExecutor().execute {
            for (code in queueCopy) {
                val targetUrl = "${getServerUrl()}/api/scan"
                val jsonPayload = JSONObject().apply { put("barcode", code); put("sid", sid) }
                val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(targetUrl).post(requestBody).build()
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        synchronized(offlineQueue) {
                            offlineQueue.remove(code)
                            saveOfflineQueue()
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
            isProcessingQueue = false
            updateQueueUi()
        }
    }
    private fun updateQueueUi() {
        runOnUiThread {
            if (offlineQueue.isEmpty()) {
                layoutPendingQueue.visibility = View.GONE
            } else {
                layoutPendingQueue.visibility = View.VISIBLE
                txtPendingCount.text = "📦 يوجد ${offlineQueue.size} مسحات معلقة — جاري الإرسال تلقائياً..."
            }
        }
    }
    private fun saveOfflineQueue() {
        val jsonArray = JSONArray(offlineQueue)
        prefs.edit().putString("offline_queue_data", jsonArray.toString()).apply()
    }
    private fun loadOfflineQueue() {
        val data = prefs.getString("offline_queue_data", null) ?: return
        try {
            val jsonArray = JSONArray(data)
            offlineQueue.clear()
            for (i in 0 until jsonArray.length()) {
                offlineQueue.add(jsonArray.getString(i))
            }
            updateQueueUi()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
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
    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
    }
}
