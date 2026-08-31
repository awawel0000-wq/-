package com.pos.scanner

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var txtLastCode: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var layoutSettings: LinearLayout
    private lateinit var edtServerIp: EditText
    private lateinit var edtServerPort: EditText
    private lateinit var btnSaveSettings: Button

    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES))
        .build()

    // متغيرات التحكم الذكي في منع التكرار
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0L
    private var isFrameClear: Boolean = true
    private var emptyFramesCount: Int = 0

    // مولد صوت الصافرة عند المسح الناجح
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        prefs = getSharedPreferences("POS_SCANNER_CONFIG", Context.MODE_PRIVATE)

        previewView = findViewById(R.id.previewView)
        txtLastCode = findViewById(R.id.txtLastCode)
        txtStatus = findViewById(R.id.txtStatus)
        btnSettings = findViewById(R.id.btnSettings)
        layoutSettings = findViewById(R.id.layoutSettings)
        edtServerIp = findViewById(R.id.edtServerIp)
        edtServerPort = findViewById(R.id.edtServerPort)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)

        // تحميل الإعدادات المحفوظة
        val savedIp = prefs.getString("server_ip", "192.168.1.100")
        val savedPort = prefs.getString("server_port", "5005")
        edtServerIp.setText(savedIp)
        edtServerPort.setText(savedPort)

        btnSettings.setOnClickListener {
            layoutSettings.visibility = if (layoutSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnSaveSettings.setOnClickListener {
            prefs.edit()
                .putString("server_ip", edtServerIp.text.toString().trim())
                .putString("server_port", edtServerPort.text.toString().trim())
                .apply()
            layoutSettings.visibility = View.GONE
            Toast.makeText(this, "تم حفظ الإعدادات بنجاح!", Toast.LENGTH_SHORT).show()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
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

                            // الحالة 1: الكادر فارغ (لا يوجد أي باركود أمام الكاميرا)
                            if (barcodes.isEmpty()) {
                                emptyFramesCount++
                                if (emptyFramesCount >= 3) { // تأكيد خلو الكادر
                                    isFrameClear = true
                                }
                            } else {
                                // الحالة 2: تم اكتشاف باركود
                                emptyFramesCount = 0
                                val barcode = barcodes[0]
                                val code = barcode.rawValue ?: return@addOnSuccessListener

                                val isDifferentCode = (code != lastScannedCode)
                                val hasLeftAndReturned = (isFrameClear && (now - lastScanTime > 1500))
                                val isCooldownPassed = (now - lastScanTime > 3500)

                                // شروط الإرسال: صنف جديد، أو أُبعد وأُعيد، أو مر وقت كافي
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
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodeDetected(code: String) {
        triggerFeedback()

        val ip = prefs.getString("server_ip", "192.168.1.100")
        val port = prefs.getString("server_port", "5005")
        val targetUrl = "http://$ip:$port/api/scan"

        runOnUiThread {
            txtLastCode.text = "الباركود: $code"
            txtStatus.text = "⚡ تم الإرسال إلى $targetUrl..."
        }

        val jsonPayload = JSONObject().apply {
            put("barcode", code)
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(targetUrl)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    txtStatus.text = "❌ فشل الاتصال بالخادم على بورت $port!"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val isSuccess = response.isSuccessful
                runOnUiThread {
                    if (isSuccess) {
                        txtStatus.text = "✅ تم الإرسال بنجاح (مسحة واحدة)"
                    } else {
                        txtStatus.text = "⚠️ رد الخادم بكود: ${response.code}"
                    }
                }
            }
        })
    }

    private fun triggerFeedback() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(70)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
    }
}
