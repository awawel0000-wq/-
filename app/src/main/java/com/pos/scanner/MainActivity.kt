package com.pos.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.tts.TextToSpeech
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
import java.util.Locale
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
    private lateinit var layoutSettings: View       // شاشةُ الإعداداتِ (ScrollView معتمٌ كاملٌ)
    private lateinit var edtServerIp: EditText
    private lateinit var edtServerPort: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var txtTestResult: TextView
    private lateinit var btnSaveSettings: Button
    private lateinit var txtItemName: TextView
    private lateinit var txtItemDetails: TextView
    private lateinit var txtStatusBadge: TextView
    private lateinit var txtResultTop: TextView       // ★ لوحةُ النتيجةِ فوقَ الإطارِ الأخضر
    private var switchVoice: android.widget.CompoundButton? = null   // ★ تفعيلُ النطقِ (CheckBox)
    private var btnInstallVoice: Button? = null        // ★ زرُّ تحميلِ صوتِ TTS العربي (يظهرُ عند الحاجة)
    private var txtScanCount: TextView? = null         // ★ عدّادُ مسحاتِ الجلسة
    private var scanCount = 0                            // عددُ المسحاتِ الناجحةِ هذه الجلسة
    private var tts: TextToSpeech? = null             // ★ محرّكُ النطق
    private var ttsReady = false                      // جاهزٌ للنطق؟
    private var ttsFallbackTried = false              // جرّبنا المحرّكَ الافتراضيَّ بعدَ فشلِ المفضّل؟
    private var ttsArabicOk = false                   // هل النطقُ العربيُّ مدعومٌ على هذا الجهاز؟
    private var ttsArabicMissingData = false          // العربيُّ موجودٌ لكن يحتاجُ تنزيلَ بياناتِ الصوت؟
    private var arabicLocale = Locale("ar")           // أفضلُ صيغةٍ عربيّةٍ مدعومةٍ على الجهاز
    private var voiceOn = false                        // مفعّلٌ من الإعدادات؟ (الافتراضي: رنّة)
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
    // ★ لغةُ البرنامجِ تضبطُ اتجاهَ الواجهةِ (RTL عربي / LTR إنجليزي) على كلِّ شيءٍ حتى القوائمِ والحوارات.
    override fun attachBaseContext(base: Context) {
        val lang = base.getSharedPreferences("POS_SCANNER_CONFIG", Context.MODE_PRIVATE)
            .getString("ui_lang", "ar") ?: "ar"
        val loc = java.util.Locale(lang)
        java.util.Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        // ★ نثبّتُ حجمَ الخطِّ من إعدادِ التطبيقِ (الافتراضي 1.0 قياسي) ونتجاهلُ تكبيرَ النظام
        cfg.fontScale = base.getSharedPreferences("POS_SCANNER_CONFIG", Context.MODE_PRIVATE)
            .getFloat("font_scale", 1.0f)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("POS_SCANNER_CONFIG", Context.MODE_PRIVATE)
        // لا نلمسُ صوتَ الجهازِ إطلاقاً — نستعملُ مستوى صوتٍ داخليّاً في التطبيقِ فقط.
        buildToneGenerator()
        initTts()
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
        layoutSettings = findViewById(R.id.settingsPanel)
        findViewById<Button>(R.id.btnCloseSettings).setOnClickListener {
            closeSettings()
        }
        edtServerIp = findViewById(R.id.edtServerIp)
        edtServerPort = findViewById(R.id.edtServerPort)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        txtTestResult = findViewById(R.id.txtTestResult)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        txtItemName = findViewById(R.id.txtItemName)
        txtItemDetails = findViewById(R.id.txtItemDetails)
        txtStatusBadge = findViewById(R.id.txtStatusBadge)
        // لمسةٌ على شريطِ الحالةِ ("تم الإرسال…") تمسحُ الاسمَ والسعرَ والباركودَ من الشاشة.
        txtStatusBadge.setOnClickListener { clearDisplay() }
        txtResultTop = findViewById(R.id.txtResultTop)
        showTopResult("وجّه الكاميرا نحو الباركود…", "#CC0F172A")
        switchVoice = findViewById(R.id.switchVoice)
        switchVoice?.isChecked = prefs.getBoolean("voice_feedback", false)
        switchVoice?.setOnCheckedChangeListener { _, checked ->
            voiceOn = checked
            prefs.edit().putBoolean("voice_feedback", checked).apply()
            if (checked) speak("النطق الصوتي مفعّل", "Voice feedback on")
        }
        btnInstallVoice = findViewById(R.id.btnInstallVoice)
        btnInstallVoice?.setOnClickListener { openTtsInstall() }
        // ★ اختيارُ لغةِ الصوت (عربي/إنجليزي) — راديو مستقلٌّ عن لغةِ البرنامج
        val rgVoice = findViewById<android.widget.RadioGroup>(R.id.rgVoiceLang)
        rgVoice.check(if ((prefs.getString("voice_lang", "ar") ?: "ar") == "en") R.id.rbVoiceEn else R.id.rbVoiceAr)
        rgVoice.setOnCheckedChangeListener { _, id ->
            val sel = if (id == R.id.rbVoiceEn) "en" else "ar"
            if (sel != (prefs.getString("voice_lang", "ar") ?: "ar")) {
                prefs.edit().putString("voice_lang", sel).apply()
                applyLangUi(true)
                if (sel == "en") speak("English voice", "English voice")
                else if (ttsArabicOk) speak("الصوت العربي", "Arabic voice")
            }
        }
        // ★ اختيارُ لغةِ البرنامج (عربي/إنجليزي) — يعيدُ بناءَ الشاشةِ بالاتجاهِ الصحيح
        val rgUi = findViewById<android.widget.RadioGroup>(R.id.rgUiLang)
        rgUi.check(if ((prefs.getString("ui_lang", "ar") ?: "ar") == "en") R.id.rbUiEn else R.id.rbUiAr)
        rgUi.setOnCheckedChangeListener { _, id ->
            val sel = if (id == R.id.rbUiEn) "en" else "ar"
            if (sel != (prefs.getString("ui_lang", "ar") ?: "ar")) {
                prefs.edit().putString("ui_lang", sel).apply()
                recreate()
            }
        }
        // ★ مستوى صوتِ التنبيه (داخليّ) — زرّا − و + يغيّران النسبةَ ويشغّلان نغمةَ تجربة.
        val txtVolVal = findViewById<TextView>(R.id.txtVolVal)
        fun showVol() { txtVolVal.text = (soundVol() * 100).toInt().toString() + "%" }
        showVol()
        findViewById<Button>(R.id.btnVolMinus).setOnClickListener {
            val p = ((soundVol() * 100).toInt() - 10).coerceAtLeast(5)
            prefs.edit().putFloat("sound_vol", p / 100f).apply(); showVol(); buildToneGenerator(); playToneSuccess()
        }
        findViewById<Button>(R.id.btnVolPlus).setOnClickListener {
            val p = ((soundVol() * 100).toInt() + 10).coerceAtMost(100)
            prefs.edit().putFloat("sound_vol", p / 100f).apply(); showVol(); buildToneGenerator(); playToneSuccess()
        }
        txtScanCount = findViewById(R.id.txtScanCount)
        txtScanCount?.background = roundBg("#CC0F172A", 20f)
        // لمسةٌ على العدّادِ تصفّرُه (بتأكيد).
        txtScanCount?.setOnClickListener { confirmResetCounter() }
        applyLangUi(false)
        styleTopButtons()
        btnTorch.setOnClickListener { toggleTorch() }
        btnSettings.setOnClickListener {
            if (layoutSettings.visibility == View.VISIBLE) closeSettings() else openSettings()
        }
        btnTestConnection.setOnClickListener { testServerConnection() }
        btnSaveSettings.setOnClickListener {
            val ip = edtServerIp.text.toString().trim()
            val port = edtServerPort.text.toString().trim()
            prefs.edit().putString("server_ip", ip).putString("server_port", port).apply()
            closeSettings()
            Toast.makeText(this, L("تم حفظ الإعدادات بنجاح!", "Settings saved!"), Toast.LENGTH_SHORT).show()
            checkServerStatus()
        }
        // زر: أعد الربط بمسح رمز QR — يقفل اللوحة ويوجّه الكاميرا للرمز (تُلتقط تلقائياً)
        findViewById<Button>(R.id.btnScanLink).setOnClickListener { startRelink() }
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
        // ★ زرُّ القائمة (☰) — كلُّ الأوامرِ في مكانٍ واحد
        findViewById<Button>(R.id.btnMenu).apply {
            background = roundBg("#99000000", 26f)
            setOnClickListener { showMainMenu(this) }
        }
        // ★ طبّقْ لغةَ الواجهةِ بعدَ ربطِ كلِّ العناصر
        applyLanguage()
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
        w.bringToFront()
        // نحن الآن داخل الموقع: أخفِ عناصرَ الماسحِ كلَّها — لا سيّما العمودَ العلويَّ المرفوعَ بـ elevation
        //   (وإلّا طفا فوقَ صفحةِ الموقع). العودةُ من زرٍّ داخل الموقعِ أو زرِّ الرجوع.
        findViewById<View>(R.id.headerStack).visibility = View.GONE
        bottomBar.visibility = View.GONE
        btnSite?.visibility = View.GONE
        findViewById<View>(R.id.btnMenu).visibility = View.GONE   // ★ يختفي زرُّ القائمةِ داخلَ الموقع
    }

    /** يعود من الموقع إلى شاشة الماسح. */
    private fun closeSite() {
        val w = web ?: return
        if (scanForSite) stopSiteScan()   // احتياطاً: أوقفْ وضعَ مسح الموقع إن كان مفعّلاً
        w.visibility = View.GONE
        // عُدنا للماسح: أرجِع عناصرَه (فقط الجديدةَ — لا الأيقوناتِ القديمةَ المُستبدَلةَ بالقائمة)
        findViewById<View>(R.id.headerStack).visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        btnSite?.visibility = View.VISIBLE
        findViewById<View>(R.id.btnMenu).visibility = View.VISIBLE   // ★ يرجعُ زرُّ القائمة
        // إصلاحُ الطبقات: نُعيدُ ترتيبَ العناصرِ الثابتةِ للأمامِ لتظهرَ فوراً بلا إغلاقِ التطبيقِ وفتحِه.
        previewView.bringToFront()                       // الكاميرا خلفية
        (findViewById<View>(R.id.scanBox))?.bringToFront() // المستطيلُ الأخضرُ الثابت
        findViewById<View>(R.id.headerStack).bringToFront() // العمودُ العلوي
        bottomBar.bringToFront()                          // شريطُ البياناتِ السفلي
        btnSite?.bringToFront()
        findViewById<View>(R.id.btnMenu).bringToFront()
        val root = w.parent as? android.view.ViewGroup
        root?.requestLayout(); root?.invalidate()          // إجبارُ إعادةِ الرسمِ فوراً
    }

    private fun loadSettings() {
        edtServerIp.setText(prefs.getString("server_ip", "192.168.1.100"))
        edtServerPort.setText(prefs.getString("server_port", "5005"))
        voiceOn = prefs.getBoolean("voice_feedback", false)
    }

    // ★ مستوى الصوتِ الداخليُّ (0..1) — يتحكّمُ به المستخدمُ من الإعدادات بلا مساسٍ بصوتِ الجهاز.
    private fun soundVol(): Float = (prefs.getFloat("sound_vol", 1.0f)).coerceIn(0.05f, 1.0f)

    // ★ يبني مولّدَ النغماتِ بمستوى الصوتِ الحاليِّ (يُعاد بناؤه عند تغييرِ المستوى).
    private fun buildToneGenerator() {
        try { toneGenerator?.release() } catch (e: Exception) {}
        val vol = (soundVol() * 100).toInt().coerceIn(1, 100)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, vol)
        } catch (e: Exception) {
            try { toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, vol) } catch (_: Exception) {}
        }
    }

    // ★ محرّكُ النطق (TTS): نُفضّلُ محرّكَ Google إن وُجد؛ فإن فشلَتْ تهيئتُه نرجعُ للمحرّكِ الافتراضيِّ تلقائياً
    //   (حتى لا يبقى النطقُ صامتاً بسببِ محرّكٍ واحدٍ فاشلِ التهيئة).
    private fun initTts() {
        try {
            val listener = object : TextToSpeech.OnInitListener {
                override fun onInit(status: Int) {
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        detectArabic()
                        tts?.setSpeechRate(1.0f)
                        runOnUiThread { applyLangUi(false) }
                    } else if (!ttsFallbackTried) {
                        // فشلَ المحرّكُ المفضّل ⇒ جرّبِ المحرّكَ الافتراضيَّ للجهاز
                        ttsFallbackTried = true
                        try { tts?.shutdown() } catch (e: Exception) {}
                        tts = TextToSpeech(this@MainActivity, this)
                    }
                }
            }
            val engine = pickTtsEngine()
            tts = if (engine != null) TextToSpeech(this, listener, engine) else TextToSpeech(this, listener)
        } catch (e: Exception) { ttsReady = false }
    }

    // ★ يُفضّلُ محرّكَ Google للنطقِ إن كان مثبّتاً (دعمُه للعربيّةِ أوسع).
    private fun pickTtsEngine(): String? {
        return try {
            val g = "com.google.android.tts"
            packageManager.getPackageInfo(g, 0)
            g
        } catch (e: Exception) { null }
    }

    // ★ كشفٌ دقيقٌ للعربيّة: نُجرّبُ ar / ar-SA / ar-EG ونأخذُ الأفضل.
    //   متوفّرٌ ⇒ نُفعّلُه · يحتاجُ بياناتٍ ⇒ نُظهرُ زرَّ التحميلِ (لا نقولُ «لا يدعم»).
    private fun detectArabic() {
        val locales = listOf(Locale("ar"), Locale("ar", "SA"), Locale("ar", "EG"))
        var best = TextToSpeech.LANG_NOT_SUPPORTED
        for (l in locales) {
            val r = try { tts?.isLanguageAvailable(l) ?: TextToSpeech.LANG_NOT_SUPPORTED }
                    catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
            if (r > best) { best = r; if (r >= TextToSpeech.LANG_AVAILABLE) arabicLocale = l }
        }
        ttsArabicOk = (best >= TextToSpeech.LANG_AVAILABLE)
        ttsArabicMissingData = (best == TextToSpeech.LANG_MISSING_DATA)
        try {
            if (ttsArabicOk) tts?.setLanguage(arabicLocale) else tts?.setLanguage(Locale.ENGLISH)
        } catch (e: Exception) {}
    }

    // ★ خلفيّةٌ دائريّةُ الحواف — لتوحيدِ شكلِ الأزرارِ واللوحات.
    private fun roundBg(hex: String, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.parseColor(hex))
        }

    // ★ توحيدُ شكلِ الأزرارِ العلويّة: زجاجٌ داكنٌ دائريُّ الحوافِّ متناسق.
    private fun styleTopButtons() {
        val glass = "#99000000"
        btnSettings.background = roundBg(glass, 24f)
        btnTorch.background = roundBg(glass, 24f)
        findViewById<View>(R.id.btnRefresh).background = roundBg(glass, 24f)
        btnSite?.background = roundBg("#E01C6FBF", 16f)
        btnInstallVoice?.background = roundBg("#E0B45309", 16f)
    }

    // ★ يضبطُ واجهةَ الصوت: إظهارَ/إخفاءَ زرِّ التحميلِ + رسالةَ «لا يدعم» عند الحاجة.
    //   announce=true ⇒ يُظهرُ الرسالةَ (عند اختيارِ المستخدمِ العربيَّ غيرَ المدعوم).
    private fun applyLangUi(announce: Boolean) {
        val lang = prefs.getString("voice_lang", "ar") ?: "ar"
        // زرُّ إدارةِ الأصواتِ ظاهرٌ دائماً — لإضافةِ/تغييرِ/تحميلِ أيِّ صوتٍ وقتما شاء المستخدم.
        btnInstallVoice?.visibility = View.VISIBLE
        val arabicUnavailable = (lang == "ar" && ttsReady && !ttsArabicOk)
        if (arabicUnavailable && announce) {
            val msg = if (ttsArabicMissingData)
                "⬇ الصوت العربي يحتاج تنزيلاً — افتح «تحميل / تغيير أصوات الجهاز»"
            else
                "⚠️ محرّك النطق الحالي لا يدعم العربي — افتح «تحميل / تغيير أصوات الجهاز» أو ثبّت Google TTS"
            showTopResult(msg, "#B45309")
        }
    }

    // ★ يفتحُ شاشةَ أصواتِ الجهازِ (تحويلُ النصِّ إلى كلام): منها يُضيفُ لغةَ صوتٍ أو يغيّرُ المحرّكَ أو
    //   يعدّلُ السرعة. إن تعذّرتْ نجرّبُ شاشةَ تثبيتِ بياناتِ الصوت، ثمّ نرشدُ يدويّاً.
    private fun openTtsInstall() {
        try {
            val i = Intent("com.android.settings.TTS_SETTINGS")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e2: Exception) {
                Toast.makeText(this, "افتح إعدادات الجهاز ← الإدارة العامة/اللغة ← تحويل النص إلى كلام", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ★ يزيدُ عدّادَ مسحاتِ الجلسة (المسحاتُ الناجحةُ التي وصلتِ النظام).
    private fun bumpScanCount() {
        scanCount++
        runOnUiThread { txtScanCount?.text = L("المسحات: ", "Scans: ") + scanCount }
    }

    // ★ تصفيرُ العدّادِ بتأكيد (لمسةٌ على العدّاد، أو من القائمة).
    private fun confirmResetCounter() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L("تصفير العدّاد", "Reset counter"))
            .setMessage(L("تصفير عدّاد المسحات إلى صفر؟", "Reset the scan counter to zero?"))
            .setPositiveButton(L("تصفير", "Reset")) { _, _ ->
                scanCount = 0
                txtScanCount?.text = L("المسحات: ", "Scans: ") + "0"
            }
            .setNegativeButton(L("إلغاء", "Cancel"), null)
            .show()
    }

    // ★ يمسحُ نتيجةَ الشاشةِ (الاسمَ فوق، والسعرَ والباركودَ تحت) ويعودُ للانتظار.
    private fun clearDisplay() {
        showTopResult(L("وجّه الكاميرا نحو الباركود…", "Aim the camera at a barcode…"), "#CC0F172A")
        txtItemName.text = ""
        txtItemDetails.text = ""
        txtStatusBadge.text = L("جاهز للمسح", "Ready to scan")
        setBadgeStyle("#1E293B", "#38BDF8", "#334155")
    }

    // ★ لغةُ البرنامج: يعيدُ النصَّ العربيَّ أو الإنجليزيَّ حسبَ اختيارِ المستخدم (الافتراضي عربي).
    private fun L(ar: String, en: String): String =
        if ((prefs.getString("ui_lang", "ar") ?: "ar") == "en") en else ar


    // ★ يطبّقُ لغةَ الواجهةِ على كلِّ النصوصِ الثابتةِ + اتجاهِ التخطيط (RTL عربي / LTR إنجليزي).
    private fun applyLanguage() {
        val en = (prefs.getString("ui_lang", "ar") ?: "ar") == "en"
        window.decorView.layoutDirection = if (en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        btnSite?.text = L("🏠 الدخول إلى نظام الأوائل المحاسبي", "🏠 Enter Al-Awael Accounting")
        findViewById<TextView>(R.id.lblSettingsTitle).text = L("⚙️ الإعدادات", "⚙️ Settings")
        findViewById<Button>(R.id.btnCloseSettings).text = L("✕ إغلاق", "✕ Close")
        findViewById<TextView>(R.id.lblConn).text = L("الاتصال بالخادم المحاسبي", "Server connection")
        edtServerIp.hint = L("عنوان IP الكمبيوتر (مثال: 192.168.1.100)", "Computer IP (e.g. 192.168.1.100)")
        edtServerPort.hint = L("البورت (افتراضي: 5005)", "Port (default: 5005)")
        findViewById<Button>(R.id.btnTestConnection).text = L("فحص الاتصال", "Test connection")
        btnSaveSettings.text = L("حفظ الإعدادات", "Save settings")
        findViewById<TextView>(R.id.lblVoiceSection).text = L("الصوت", "Voice")
        switchVoice?.text = L("🔊 تفعيل النطق الصوتي بالنتيجة", "🔊 Enable spoken result")
        findViewById<TextView>(R.id.lblVoiceLang).text = L("لغة الصوت:", "Voice language:")
        findViewById<TextView>(R.id.lblUiLang).text = L("لغة البرنامج:", "App language:")
        findViewById<TextView>(R.id.txtVoiceHint).text = L(
            "عند النجاح يقول اسم الصنف، وعند الفشل «لم يصل إلى النظام».",
            "On success it says the item name; on failure «Not sent to system».")
        findViewById<TextView>(R.id.lblRelinkHint).text = L(
            "أو غيّر الجهاز بمسح رمز الربط (QR) من الكمبيوتر",
            "Or switch device by scanning the link QR from the computer")
        findViewById<Button>(R.id.btnScanLink).text = L("📷 مسح رمز الربط بالكاميرا", "📷 Scan link QR")
        btnInstallVoice?.text = L("🗣 تحميل / تغيير أصوات الجهاز", "🗣 Install / change device voices")
        findViewById<TextView>(R.id.lblSoundVol).text = L("مستوى صوت التنبيه:", "Alert volume:")
        txtScanCount?.text = L("المسحات: ", "Scans: ") + scanCount
    }

    // ★ القائمة (☰): قائمةٌ نظيفةٌ موحّدةُ الاتجاهِ مثلَ تطبيقاتِ الجوال — تشملُ لغةَ الصوتِ ولغةَ البرنامج.
    private fun showMainMenu(anchor: View) {
        val items = arrayOf(
            L("💡 الكشّاف", "💡 Torch"),
            L("🔌 الربط بنظام الأوائل", "🔌 Link to Al-Awael"),
            L("🌐 اللغة والصوت", "🌐 Language & voice"),
            L("🔠 حجم الخط", "🔠 Font size"),
            L("🔄 تحديث الحالة", "🔄 Refresh status"),
            L("🔢 تصفير العدّاد", "🔢 Reset counter"),
            L("ℹ️ عن التطبيق", "ℹ️ About")
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L("القائمة", "Menu"))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleTorch()
                    1 -> openLinkPanel()
                    2 -> openLangPanel()
                    3 -> openFontDialog()
                    4 -> refreshStatus()
                    5 -> confirmResetCounter()
                    6 -> showAbout()
                }
            }
            .show()
    }

    // ★ يفتحُ الإعداداتِ على قسمٍ واحدٍ فقط (الربط أو اللغة) — شاشةٌ منبثقةٌ نظيفةٌ لكلِّ غرض.
    private fun openLinkPanel() { showSettingsSection(true) }
    private fun openLangPanel() { showSettingsSection(false) }
    private fun showSettingsSection(conn: Boolean) {
        findViewById<View>(R.id.secConn).visibility = if (conn) View.VISIBLE else View.GONE
        findViewById<View>(R.id.secLang).visibility = if (conn) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.lblSettingsTitle).text =
            if (conn) L("🔌 الربط بنظام الأوائل", "🔌 Link to Al-Awael")
            else L("🌐 اللغة والصوت", "🌐 Language & voice")
        openSettings()
    }

    // ★ حجمُ الخط: زرّا − و + يغيّران النسبةَ المئويّة، ثمّ تطبيقٌ يُعيدُ البناء.
    private fun openFontDialog() {
        var pct = (prefs.getFloat("font_scale", 1.0f) * 100).toInt()
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER
        row.setPadding(px(20), px(20), px(20), px(20))
        val minus = Button(this); minus.text = "−"; minus.textSize = 22f
        val lbl = TextView(this); lbl.text = "$pct%"; lbl.textSize = 22f
        lbl.setPadding(px(24), 0, px(24), 0); lbl.gravity = android.view.Gravity.CENTER
        val plus = Button(this); plus.text = "+"; plus.textSize = 22f
        minus.setOnClickListener { pct = (pct - 10).coerceAtLeast(50); lbl.text = "$pct%" }
        plus.setOnClickListener { pct = (pct + 10).coerceAtMost(200); lbl.text = "$pct%" }
        row.addView(minus); row.addView(lbl); row.addView(plus)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L("حجم الخط", "Font size"))
            .setView(row)
            .setPositiveButton(L("تطبيق", "Apply")) { _, _ ->
                prefs.edit().putFloat("font_scale", pct / 100f).apply()
                recreate()
            }
            .setNegativeButton(L("إلغاء", "Cancel"), null)
            .show()
    }

    // ★ فتحُ/إغلاقُ الإعدادات: نخفي عمودَ الماسحِ والشريطَ السفليَّ حتى لا يطفوا فوقَ الإعدادات (elevation).
    private fun openSettings() {
        findViewById<View>(R.id.headerStack).visibility = View.GONE
        bottomBar.visibility = View.GONE
        findViewById<View>(R.id.btnMenu).visibility = View.GONE
        layoutSettings.visibility = View.VISIBLE
        layoutSettings.bringToFront()
        txtTestResult.visibility = View.GONE
    }
    private fun closeSettings() {
        layoutSettings.visibility = View.GONE
        findViewById<View>(R.id.headerStack).visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        findViewById<View>(R.id.btnMenu).visibility = View.VISIBLE
    }

    // ★ إعادةُ الربطِ بمسحِ QR (نفسُ زرِّ "مسح رمز الربط" داخلَ الإعدادات).
    private fun startRelink() {
        closeSettings()
        txtResultTop.text = L("📷 وجّه الكاميرا لرمز الربط", "📷 Aim the camera at the link QR")
        txtStatusBadge.text = L("بانتظار رمز الربط…", "Waiting for link QR…")
        setBadgeStyle("#1E293B", "#38BDF8", "#334155")
        Toast.makeText(this, L("وجّه الكاميرا نحو رمز الربط", "Aim at the link QR"), Toast.LENGTH_LONG).show()
    }

    // ★ عن التطبيق: الاسمُ والإصدار.
    private fun showAbout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L("عن التطبيق", "About"))
            .setMessage(L("ماسح باركود الأوائل\nالإصدار: 1.0", "Al-Awael Barcode Scanner\nVersion: 1.0"))
            .setPositiveButton(L("حسناً", "OK"), null)
            .show()
    }

    // ★ نطقٌ: يتبعُ لغةَ الصوتِ المختارة (عربي/إنجليزي) والصيغةَ العربيّةَ المدعومةَ فعلاً على الجهاز.
    //   لو اختِيرَ العربيُّ وهو غيرُ متاحٍ ⇒ يرجعُ للإنجليزيّ. لا يعملُ إلا إن فُعّلَ الخيار.
    private fun speak(ar: String, en: String) {
        if (!voiceOn || !ttsReady) return
        val lang = prefs.getString("voice_lang", "ar") ?: "ar"
        val say: String
        val loc: Locale
        if (lang == "en") { say = en; loc = Locale.ENGLISH }
        else if (ttsArabicOk) { say = ar; loc = arabicLocale }
        else { say = en; loc = Locale.ENGLISH }   // عربيٌّ مطلوبٌ لكنّه غيرُ متاحٍ ⇒ سقوطٌ آمن
        try {
            tts?.setLanguage(loc)
            // نُوجّهُ النطقَ لقناةِ الإنذارِ (المرفوعةِ للأقصى مثلَ الرنّة) فلا يضيعُ الصوتُ لو كانتْ قناةُ الوسائطِ منخفضة
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, soundVol())
            tts?.speak(say, TextToSpeech.QUEUE_FLUSH, params, "scan_" + System.currentTimeMillis())
        } catch (e: Exception) {}
    }

    // ★ يعرضُ ردَّ الخادمِ فوقَ الإطارِ الأخضر — ويبقى ظاهراً (لا يختفي) حتى المسحةِ التالية.
    private fun showTopResult(text: String, bgHex: String) {
        runOnUiThread {
            txtResultTop.text = text
            txtResultTop.background = roundBg(bgHex, 18f)
            txtResultTop.visibility = View.VISIBLE
        }
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
                closeSettings()
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
                showTopResult(L("🔴 لست مقترناً — امسح رمز الربط", "🔴 Not linked — scan the link QR"), "#B91C1C")
                speak("لست مقترنا، امسح رمز الربط", "Not linked, scan the link code")
                txtItemName.text = ""
                txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                txtStatusBadge.text = L("❌ افتح القائمة ← إعادة الربط، وامسح QR من الكمبيوتر", "❌ Open menu → Re-link, scan QR from the computer")
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
                    showTopResult(L("🔴 لم يصل إلى النظام — أعِد المسح", "🔴 Not sent — scan again"), "#B91C1C")
                    speak("لم يصل إلى النظام، أعد المسح", "Not sent, scan again")
                    txtItemName.text = ""
                    txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                    txtStatusBadge.text = L("❌ لم يُرسَل — انقطاع الاتصال بالخادم", "❌ Not sent — no connection to server")
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
                            bumpScanCount()
                            // الاسمُ فوقَ الإطارِ الأخضر (كما طلبت)، والسعرُ والباركودُ في الأسفلِ فقط — بلا تكرارٍ للاسم.
                            showTopResult("✅ $itemName", "#15803D")
                            speak(itemName, "Received")
                            txtItemName.text = ""
                            txtItemDetails.text = if (itemPrice.isNotEmpty())
                                L("السعر: ", "Price: ") + "$itemPrice ₪  ·  " + L("الباركود: ", "Barcode: ") + code
                                else L("الباركود: ", "Barcode: ") + code
                            txtStatusBadge.text = L("✅ تم الإرسال والإضافة للفاتورة", "✅ Sent & added to invoice")
                            setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                        } else if (!isFound || response.code == 404) {
                            playToneWarning()
                            vibrateWarning()
                            showTopResult(L("⚠️ صنف غير معرّف", "⚠️ Unknown item"), "#B45309")
                            speak("صنف غير معرّف", "Unknown item")
                            txtItemName.text = ""
                            txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                            txtStatusBadge.text = L("لا يوجد صنف بهذا الباركود في النظام", "No item with this barcode")
                            setBadgeStyle("#78350F", "#F59E0B", "#D97706")
                        } else {
                            playToneSuccess()
                            vibrateSuccess()
                            bumpScanCount()
                            showTopResult(L("✅ تم الاستلام", "✅ Received"), "#15803D")
                            speak("تم الاستلام", "Received")
                            txtItemName.text = ""
                            txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                            txtStatusBadge.text = L("✅ تم النقل بنجاح", "✅ Transferred")
                            setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                        }
                    }
                } catch (e: Exception) {
                    // الردُّ وصلَ لكن تعذّرَ تحليلُه. لا نَكذِبُ بالنجاح:
                    //   نجاحٌ فقط إن كانتِ الاستجابةُ ناجحةً فعلاً (2xx)؛ وإلا نحفظُ في الانتظار.
                    if (response.isSuccessful) {
                        runOnUiThread {
                            playToneSuccess(); vibrateSuccess()
                            bumpScanCount()
                            showTopResult(L("✅ تم الاستلام", "✅ Received"), "#15803D")
                            speak("تم الاستلام", "Received")
                            txtItemName.text = ""
                            txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                            txtStatusBadge.text = L("✅ تم الاستلام بنجاح", "✅ Received")
                            setBadgeStyle("#14532D", "#4ADE80", "#22C55E")
                        }
                    } else {
                        // لا حفظَ محلياً: فشلٌ صريحٌ ⇒ يُعيدُ الكاشيرُ المسح
                        runOnUiThread {
                            playToneError(); vibrateError()
                            showTopResult(L("🔴 لم يصل إلى النظام — أعِد المسح", "🔴 Not sent — scan again"), "#B91C1C")
                            speak("لم يصل إلى النظام، أعد المسح", "Not sent, scan again")
                            txtItemName.text = ""
                            txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                            txtStatusBadge.text = L("❌ لم يُرسَل — أعد المسح", "❌ Not sent — scan again") + " (${response.code})"
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
    // بعدَ العودةِ للتطبيق (مثلاً بعد تثبيتِ صوتِ عربيّ) نُعيدُ كشفَ العربيّةِ ونحدّثُ الواجهة.
    override fun onResume() {
        super.onResume()
        if (ttsReady) { detectArabic(); applyLangUi(false) }
    }
    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
    }
}
