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
    // ═══════════ 🧮 الجردُ الجماعيّ (عملٌ دونَ اتصالٍ + مزامنة) ═══════════
    private var stkActive = false
    private var stkSessionId = ""
    private var stkCode = ""
    private var stkName = ""
    private var stkCounter = ""
    private var stkRetain = 30
    private var stkDeviceId = ""                          // معرّفُ هذا الجوّالِ (ثابتٌ عبرَ التشغيلات)
    private val stkLines = ArrayList<JSONObject>()        // {uid,product_id,barcode,qty,ts,name,synced,deleted}
    private val stkNameMap = HashMap<String, String>()    // باركود → اسمُ الصنف (لعرضِ الاسمِ دونَ اتصال)
    private val stkIdMap = HashMap<String, String>()      // باركود → معرّفُ الصنف
    private var stkOverlay: View? = null
    private var stkListLayout: LinearLayout? = null
    private var stkPendingText: TextView? = null
    private var stkTitleText: TextView? = null
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
        // معرّفُ الجوّالِ الثابت (يُميّزُ جهازَ كلِّ جاردٍ عندَ التجميع)
        stkDeviceId = prefs.getString("stk_device_id", null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("stk_device_id", it).apply() }
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
        restoreActiveStocktake()   // 🧮 استئنافُ جلسةِ الجردِ إن كانت مفتوحةً قبلَ الإغلاق (يعملُ دونَ اتصال)
    }

    // 🧮 يستأنفُ جلسةَ الجردِ المحفوظةَ محلياً (بعدَ إغلاقِ التطبيقِ أو انقطاعِ الشبكة) — بلا حاجةٍ لإعادةِ الربط.
    private fun restoreActiveStocktake() {
        val asid = prefs.getString("stk_active_sid", "") ?: ""
        if (asid.isBlank()) return
        enterStocktakeSession(asid,
            prefs.getString("stk_s_code_$asid", "") ?: "",
            prefs.getString("stk_s_name_$asid", "") ?: "",
            prefs.getString("stk_s_counter_$asid", "") ?: "",
            prefs.getInt("stk_s_retain_$asid", 30), false)
    }

    // يفتحُ جلسةَ جردٍ (من QR أو القائمةِ أو الاستئناف): يحفظُها ويعرضُ شاشتَها.
    private fun enterStocktakeSession(sid: String, code: String, name: String, counter: String, retain: Int, sound: Boolean) {
        stkSessionId = sid; stkCode = code; stkName = name; stkCounter = counter; stkRetain = retain
        stkActive = true
        prefs.edit()
            .putString("stk_active_sid", sid)   // للاستئنافِ التلقائيّ بعدَ الإغلاق (يُمسَحُ عند «خروج»)
            .putString("stk_last_sid", sid)     // لزرِّ «الجرد» في القائمةِ (يبقى بعدَ الخروج)
            .putString("stk_s_code_$sid", code)
            .putString("stk_s_name_$sid", name)
            .putString("stk_s_counter_$sid", counter)
            .putInt("stk_s_retain_$sid", retain)
            .apply()
        loadStkLines(sid)
        if (sound) runOnUiThread { playToneSuccess(); vibrateSuccess() }
        window.decorView.post { showStocktakeUI(); renderStkList() }
        downloadCatalog()
    }

    // يستأنفُ آخرَ جلسةٍ من زرِّ القائمة (بعدَ الخروج) — بلا إعادةِ مسحِ QR.
    private fun resumeLastStocktake() {
        if (stkActive) return
        val lsid = prefs.getString("stk_last_sid", "") ?: ""
        if (lsid.isBlank()) {
            Toast.makeText(this, L("لا توجد جلسة جرد سابقة — امسح رمز جلسة من الكمبيوتر.", "No previous stocktake — scan a session QR."), Toast.LENGTH_LONG).show()
            return
        }
        enterStocktakeSession(lsid,
            prefs.getString("stk_s_code_$lsid", "") ?: "",
            prefs.getString("stk_s_name_$lsid", "") ?: "",
            prefs.getString("stk_s_counter_$lsid", "") ?: "",
            prefs.getInt("stk_s_retain_$lsid", 30), true)
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
            L("🧮 جلسة الجرد", "🧮 Stocktake session"),
            L("💡 الكشّاف", "💡 Torch"),
            L("🔌 الربط بنظام الأوائل", "🔌 Link to Al-Awael"),
            L("🌐 اللغة والصوت", "🌐 Language & voice"),
            L("🗣️ نمط النطق", "🗣️ Speech mode"),
            L("🔠 حجم الخط", "🔠 Font size"),
            L("🔄 تحديث الحالة", "🔄 Refresh status"),
            L("🔢 تصفير العدّاد", "🔢 Reset counter"),
            L("ℹ️ عن التطبيق", "ℹ️ About")
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L("القائمة", "Menu"))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> resumeLastStocktake()
                    1 -> toggleTorch()
                    2 -> openLinkPanel()
                    3 -> openLangPanel()
                    4 -> chooseSpeakMode()
                    5 -> openFontDialog()
                    6 -> refreshStatus()
                    7 -> confirmResetCounter()
                    8 -> showAbout()
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

    // ★ عن التطبيق: الاسمُ والإصدارُ الحقيقيُّ (يُقرأُ من رقمِ البناءِ لا ثابتاً — فلا يخدعُ المستخدم).
    private fun showAbout() {
        val v = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            (pi.versionName ?: "") + " (" +
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toString()
                 else @Suppress("DEPRECATION") pi.versionCode.toString()) + ")"
        } catch (e: Exception) { "?" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L("عن التطبيق", "About"))
            .setMessage(L("ماسح باركود الأوائل\nالإصدار: $v", "Al-Awael Barcode Scanner\nVersion: $v"))
            .setPositiveButton(L("حسناً", "OK"), null)
            .show()
    }

    // ★ نطقٌ: يتبعُ لغةَ الصوتِ المختارة (عربي/إنجليزي) والصيغةَ العربيّةَ المدعومةَ فعلاً على الجهاز.
    //   لو اختِيرَ العربيُّ وهو غيرُ متاحٍ ⇒ يرجعُ للإنجليزيّ. لا يعملُ إلا إن فُعّلَ الخيار.
    private fun speak(ar: String, en: String) {
        if (!voiceOn || !ttsReady) return
        if ((prefs.getString("speak_mode", "first") ?: "first") == "beep") return   // صفيرٌ فقط ⇒ لا نطقَ للرسائل
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

    // ★ نطقُ اسمِ الصنفِ حسبَ الوضعِ المختار: نطقٌ كامل / الكلمة الأولى (سرعة) / صفيرٌ فقط.
    //   يعيدُ true إن نطقَ فعلاً (فيستغني النداءُ عن الصفير)، false إن لم ينطقْ (صفيرٌ/معطّل).
    private fun speakItem(ar: String, en: String): Boolean {
        if (!voiceOn || !ttsReady) return false
        val mode = prefs.getString("speak_mode", "first") ?: "first"
        if (mode == "beep") return false
        val lang = prefs.getString("voice_lang", "ar") ?: "ar"
        var say: String; val loc: Locale
        if (lang == "en") { say = en; loc = Locale.ENGLISH }
        else if (ttsArabicOk) { say = ar; loc = arabicLocale }
        else { say = en; loc = Locale.ENGLISH }
        if (mode == "first") { say = say.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: say }
        return try {
            tts?.setLanguage(loc)
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, soundVol())
            tts?.speak(say, TextToSpeech.QUEUE_FLUSH, params, "item_" + System.currentTimeMillis())
            true
        } catch (e: Exception) { false }
    }

    // ★ اختيارُ نمطِ النطق (المتّفقُ عليه): نطقٌ كامل / الكلمة الأولى / صفيرٌ فقط — يحكمُ البيعَ والجردَ معاً.
    private fun chooseSpeakMode() {
        val modes = arrayOf("full", "first", "beep")
        val labels = arrayOf(
            L("🗣️ نطق الاسم كاملاً", "🗣️ Speak full name"),
            L("⚡ الكلمة الأولى فقط (أسرع)", "⚡ First word only (faster)"),
            L("🔔 صفيرٌ فقط بلا نطق", "🔔 Beep only (no speech)")
        )
        val cur = prefs.getString("speak_mode", "first") ?: "first"
        val sel = modes.indexOf(cur).let { if (it < 0) 1 else it }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L("نمط النطق", "Speech mode"))
            .setSingleChoiceItems(labels, sel) { d, which ->
                prefs.edit().putString("speak_mode", modes[which]).apply()
                if (modes[which] != "beep") speakItem(L("تمام", "Okay"), "Okay")
                d.dismiss()
            }
            .setNegativeButton(L("إغلاق", "Close"), null)
            .show()
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
                // 🧮 مزامنةٌ تلقائيّةٌ للجرد كلَّ نبضةٍ إن كان هناك معلّق (تعملُ فورَ عودةِ الشبكة)
                if (stkActive) trySyncStocktake(false)
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
                                val hasLeftAndReturned = (isFrameClear && (now - lastScanTime > 600))
                                val isCooldownPassed = (now - lastScanTime > 1500)
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
        // 🧮 رمزُ جلسةِ جردٍ (QR من شاشة الجرد في الكمبيوتر)؟ ادخلْ وضعَ الجرد.
        if (code.startsWith("awael://stocktake")) {
            handleStocktakeQR(code)
            return
        }
        // 🧮 نحن داخلَ وضعِ الجرد؟ الباركودُ يُضافُ للقائمةِ المحلّيّةِ (لا يُرسَلُ للكاشير).
        if (stkActive) {
            onStocktakeScan(code)
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
                            speakItem(itemName, "Received")   // يتبعُ نمطَ النطق (كامل/كلمة أولى/صفير)
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
    // ═══════════════════════════════════════════════════════════════════
    // 🧮 الجردُ الجماعيّ — استقبالُ الجلسةِ، العدُّ دونَ اتصالٍ، ثمّ المزامنة
    // ═══════════════════════════════════════════════════════════════════

    /** يفكّكُ رمزَ جلسةِ الجرد ويدخلُ وضعَ العدّ. الصيغة:
     *  awael://stocktake?ip=&port=&session=&code=&wh=&retain=&counter=&start=&name= */
    private fun handleStocktakeQR(code: String) {
        try {
            val uri = android.net.Uri.parse(code)
            val ip = uri.getQueryParameter("ip") ?: ""
            val port = uri.getQueryParameter("port") ?: "5005"
            val sid = uri.getQueryParameter("session") ?: ""
            val ccode = uri.getQueryParameter("code") ?: ""
            val counter = uri.getQueryParameter("counter") ?: ""
            val name = uri.getQueryParameter("name") ?: ""
            val retain = (uri.getQueryParameter("retain") ?: "30").toIntOrNull() ?: 30
            if (sid.isBlank()) {
                runOnUiThread { playToneWarning(); vibrateWarning()
                    showTopResult(L("⚠️ رمز جرد غير صالح", "⚠️ Invalid stocktake code"), "#D97706") }
                return
            }
            if (ip.isNotBlank()) {
                prefs.edit().putString("server_ip", ip).putString("server_port", port).apply()
                edtServerIp.setText(ip); edtServerPort.setText(port)
            }
            enterStocktakeSession(sid, ccode, name, counter, retain, true)
        } catch (e: Exception) {
            runOnUiThread { playToneError(); vibrateError()
                showTopResult(L("🔴 تعذّر قراءة رمز الجرد", "🔴 Could not read stocktake code"), "#B91C1C") }
        }
    }

    /** يُنزّلُ فهرسَ الأصنافِ (باركود → اسم/معرّف) مرّةً ويخزّنُه محلياً — ليعملَ الاسمُ دونَ اتصال. */
    private fun downloadCatalog() {
        // فهرسُ الأصنافِ عبرَ نقطةِ الجردِ المُصادَقةِ برمزِ الجلسة (لا تحتاجُ دخولاً)
        val url = "${getServerUrl()}/api/stocktake/catalog?session=" +
            java.net.URLEncoder.encode(stkSessionId, "UTF-8") + "&code=" +
            java.net.URLEncoder.encode(stkCode, "UTF-8")
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* دونَ اتصال: نُبقي الفهرسَ المخزّنَ سابقاً */ }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: return
                    val arr = parseProductsArray(body)
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val nm = p.optString("name", p.optString("name_ar", ""))
                        val pid = if (p.has("id")) p.optString("id", "") else ""
                        // كلُّ مفاتيحِ الصنف: مصفوفةُ barcodes من الخادم + احتياطيّاً barcode/sku/code
                        val keys = ArrayList<String>()
                        p.optJSONArray("barcodes")?.let { a -> for (i in 0 until a.length()) { val k = a.optString(i, ""); if (k.isNotBlank()) keys.add(k) } }
                        for (extra in listOf(p.optString("barcode", ""), p.optString("sku", ""), p.optString("code", ""))) {
                            if (extra.isNotBlank() && !keys.contains(extra)) keys.add(extra)
                        }
                        for (bc in keys) { stkNameMap[bc] = nm; stkIdMap[bc] = pid }
                    }
                    val save = JSONObject()
                    save.put("names", JSONObject(stkNameMap as Map<*, *>))
                    save.put("ids", JSONObject(stkIdMap as Map<*, *>))
                    prefs.edit().putString("stk_catalog", save.toString()).apply()
                    runOnUiThread { renderStkList() }
                } catch (e: Exception) {}
            }
        })
    }

    private fun parseProductsArray(body: String): JSONArray {
        return try {
            val t = body.trim()
            if (t.startsWith("[")) JSONArray(t)
            else { val o = JSONObject(t); o.optJSONArray("data") ?: o.optJSONArray("products") ?: JSONArray() }
        } catch (e: Exception) { JSONArray() }
    }

    private fun restoreCatalog() {
        try {
            val s = prefs.getString("stk_catalog", null) ?: return
            val o = JSONObject(s)
            o.optJSONObject("names")?.let { n -> val k = n.keys(); while (k.hasNext()) { val key = k.next(); stkNameMap[key] = n.optString(key) } }
            o.optJSONObject("ids")?.let { m -> val k = m.keys(); while (k.hasNext()) { val key = k.next(); stkIdMap[key] = m.optString(key) } }
        } catch (e: Exception) {}
    }

    /** مسحةٌ داخلَ وضعِ الجرد: تُضيفُ سطراً جديداً أو تزيدُ كميّةَ سطرٍ موجودٍ (+1)، وتُخزَّنُ فوراً. */
    private fun onStocktakeScan(code: String) {
        val name = stkNameMap[code] ?: ""
        val pid = stkIdMap[code] ?: ""
        var line: JSONObject? = null
        for (l in stkLines) { if (l.optString("barcode") == code && l.optInt("deleted", 0) == 0) { line = l; break } }
        if (line == null) {
            val nl = JSONObject().apply {
                put("uid", stkDeviceId + "-" + System.currentTimeMillis())
                put("product_id", pid); put("barcode", code)
                put("qty", 1.0); put("name", name)
                put("ts", nowTs()); put("synced", false); put("deleted", 0)
            }
            stkLines.add(0, nl)
        } else {
            line.put("qty", line.optDouble("qty", 0.0) + 1.0)
            line.put("ts", nowTs()); line.put("synced", false)
        }
        saveStkLines()
        val spokenName = name
        runOnUiThread {
            vibrateSuccess()
            // يتبعُ نمطَ النطقِ المختار (كامل/كلمة أولى/صفير)؛ إن لم ينطقْ ⇒ صفير.
            val spoke = if (spokenName.isNotBlank()) speakItem(spokenName, spokenName) else false
            if (!spoke) playToneSuccess()
            renderStkList()
        }
        trySyncStocktake()   // مزامنةٌ صامتةٌ إن كنّا متصلين
    }

    /** 🔊 ينطقُ الكلمةَ الأولى من اسمِ الصنفِ فقط (سرعةٌ بلا تداخل — المتّفقُ عليه). */
    private fun speakFirstWordStk(name: String) {
        if (!ttsReady) { playToneSuccess(); return }
        val first = name.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
        if (first.isNullOrBlank()) { playToneSuccess(); return }
        try {
            val loc = if (ttsArabicOk) arabicLocale else Locale.getDefault()
            tts?.setLanguage(loc)
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            tts?.speak(first, TextToSpeech.QUEUE_FLUSH, params, "stk_" + System.currentTimeMillis())
        } catch (e: Exception) { playToneSuccess() }
    }

    private fun nowTs(): String {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())
        } catch (e: Exception) { System.currentTimeMillis().toString() }
    }

    // ارتفاعُ شريطِ حالةِ النظام (لِيَقيَ الهيدرَ من التداخلِ مع أيقوناتِ الجوّال).
    private fun statusBarH(): Int {
        return try {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
        } catch (e: Exception) { dp(24) }
    }

    private fun stkKey(): String = "stk_lines_" + stkSessionId
    private fun saveStkLines() {
        try {
            val arr = JSONArray(); for (l in stkLines) arr.put(l)
            prefs.edit().putString(stkKey(), arr.toString()).apply()
        } catch (e: Exception) {}
    }
    private fun loadStkLines(sid: String) {
        stkLines.clear()
        restoreCatalog()
        try {
            val s = prefs.getString("stk_lines_" + sid, null) ?: return
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) { arr.optJSONObject(i)?.let { stkLines.add(it) } }
        } catch (e: Exception) {}
    }

    /** يدفعُ السطورَ غيرَ المُزامَنةِ للخادمِ (idempotent عبرَ uid). manual=true ⇒ رسائلُ نجاحٍ/فشلٍ واضحة. */
    private fun trySyncStocktake(manual: Boolean = false) {
        if (stkSessionId.isBlank()) return
        val pending = ArrayList<JSONObject>()
        for (l in stkLines) { if (!l.optBoolean("synced", false)) pending.add(l) }
        if (pending.isEmpty()) {
            runOnUiThread { updateStkPending(); if (manual) Toast.makeText(this, L("لا شيءَ للمزامنة — الكلُّ محفوظٌ في النظام ✅", "Nothing to sync — all saved ✅"), Toast.LENGTH_SHORT).show() }
            return
        }
        val payload = JSONObject()
        val sidInt = stkSessionId.toIntOrNull()
        if (sidInt != null) payload.put("session_id", sidInt) else payload.put("session_id", stkSessionId)
        payload.put("code", stkCode)          // 🔑 مصادقةٌ برمزِ الجلسة (بدلَ الدخول)
        payload.put("device", stkDeviceId)
        payload.put("counter", stkCounter)
        val larr = JSONArray()
        for (l in pending) {
            larr.put(JSONObject().apply {
                put("uid", l.optString("uid"))
                put("product_id", l.optString("product_id"))
                put("barcode", l.optString("barcode"))
                put("qty", l.optDouble("qty", 0.0))
                put("ts", l.optString("ts"))
                put("deleted", l.optInt("deleted", 0))
            })
        }
        payload.put("lines", larr)
        val n = pending.size
        val rb = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url("${getServerUrl()}/api/stocktake/push").post(rb).build()
        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    updateStkPending()
                    if (manual) Toast.makeText(this@MainActivity, L("تعذّرتِ المزامنة — لا اتصالَ بالخادم. ستُعادُ تلقائيّاً عند عودةِ الشبكة.", "Sync failed — no server connection. Will retry automatically."), Toast.LENGTH_LONG).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                val bodyStr = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
                if (ok) {
                    for (l in pending) l.put("synced", true)
                    val it = stkLines.iterator()
                    while (it.hasNext()) { val l = it.next(); if (l.optInt("deleted", 0) == 1 && l.optBoolean("synced", false)) it.remove() }
                    saveStkLines()
                }
                runOnUiThread {
                    renderStkList(); updateStkPending()
                    if (ok) {
                        if (manual) Toast.makeText(this@MainActivity, L("تمّتِ المزامنة ✅ — أُرسِل $n سطراً للنظام", "Synced ✅ — $n rows sent"), Toast.LENGTH_SHORT).show()
                    } else {
                        // 403 = رمزُ جلسةٍ خطأ · 409 = الجلسةُ مقفلة · غيرُها = خطأُ خادم
                        val msg = when (response.code) {
                            403 -> L("فشلتِ المزامنة — رمزُ الجلسةِ غيرُ صالح. أعِد مسحَ QR الجلسة.", "Sync failed — invalid session code. Rescan the session QR.")
                            409 -> L("الجلسةُ مقفلةٌ في النظام — لا تقبلُ مسحاتٍ جديدة.", "Session is closed — no new scans accepted.")
                            else -> L("تعذّرتِ المزامنة (خطأ ${response.code}). ستُعادُ تلقائيّاً.", "Sync failed (${response.code}). Will retry.")
                        }
                        if (manual || response.code == 403 || response.code == 409)
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    // ── واجهةُ الجرد (تُبنى برمجياً — الكاميرا تبقى ظاهرةً وسطاً للتصويب، والقائمةُ أسفلَها) ──
    private fun showStocktakeUI() {
        stkOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        previewView.visibility = View.VISIBLE; previewView.bringToFront()
        try { findViewById<View>(R.id.scanBox)?.visibility = View.GONE } catch (e: Exception) {}
        bottomBar.visibility = View.GONE
        btnSite?.visibility = View.GONE
        btnSettings.visibility = View.GONE
        btnTorch.visibility = View.GONE
        try { findViewById<View>(R.id.btnRefresh).visibility = View.GONE } catch (e: Exception) {}
        try { findViewById<View>(R.id.btnMenu).visibility = View.GONE } catch (e: Exception) {}
        layoutPendingQueue.visibility = View.GONE

        val root = android.widget.LinearLayout(this)
        root.orientation = android.widget.LinearLayout.VERTICAL
        root.setBackgroundColor(Color.TRANSPARENT)
        root.layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)

        val header = android.widget.LinearLayout(this)
        header.orientation = android.widget.LinearLayout.HORIZONTAL
        // حشوٌ علويٌّ بمقدارِ شريطِ الحالةِ حتى لا تتداخلَ أيقوناتُ النظامِ مع الهيدر
        header.setPadding(dp(14), dp(10) + statusBarH(), dp(10), dp(10))
        header.setBackgroundColor(Color.parseColor("#00695C"))
        header.gravity = android.view.Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = stkHeaderText()
        title.setTextColor(Color.WHITE); title.textSize = 15f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        stkTitleText = title
        val torchB = Button(this)
        torchB.text = "💡"; torchB.textSize = 16f
        torchB.setBackgroundColor(Color.parseColor("#004D40")); torchB.setTextColor(Color.WHITE)
        torchB.layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(46))
        torchB.setOnClickListener { toggleTorch() }
        val exit = Button(this)
        exit.text = L("خروج", "Exit"); exit.setTextColor(Color.WHITE)
        exit.setBackgroundColor(Color.parseColor("#B71C1C"))
        exit.layoutParams = android.widget.LinearLayout.LayoutParams(-2, dp(46))
        exit.setOnClickListener { exitStocktake() }
        header.addView(title); header.addView(torchB); header.addView(exit)
        root.addView(header)

        val bar = android.widget.LinearLayout(this)
        bar.orientation = android.widget.LinearLayout.HORIZONTAL
        bar.setPadding(dp(14), dp(8), dp(10), dp(8))
        bar.setBackgroundColor(Color.parseColor("#111827"))
        bar.gravity = android.view.Gravity.CENTER_VERTICAL
        val pend = TextView(this)
        pend.setTextColor(Color.parseColor("#93C5FD")); pend.textSize = 13f
        pend.layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        stkPendingText = pend
        val sync = Button(this)
        sync.text = L("🔄 مزامنة", "🔄 Sync"); sync.setTextColor(Color.WHITE)
        sync.setBackgroundColor(Color.parseColor("#0284C7"))
        sync.layoutParams = android.widget.LinearLayout.LayoutParams(-2, dp(46))
        sync.setOnClickListener { Toast.makeText(this, L("جارٍ المزامنة…", "Syncing…"), Toast.LENGTH_SHORT).show(); trySyncStocktake(true) }
        bar.addView(pend); bar.addView(sync)
        root.addView(bar)

        val mid = android.widget.FrameLayout(this)
        mid.layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
        val frame = View(this)
        val fp = android.widget.FrameLayout.LayoutParams(dp(260), dp(150))
        fp.gravity = android.view.Gravity.CENTER
        frame.layoutParams = fp
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat()
            setStroke(dp(3), Color.parseColor("#26D07C")); setColor(Color.TRANSPARENT)
        }
        val hint = TextView(this)
        hint.text = L("وجّهِ الكاميرا للباركود — يُضافُ ويُخزَّنُ ولو دونَ اتصال · اضغطِ الكميّةَ لتعديلها",
                      "Aim at a barcode — it's added and saved even offline · tap the qty to edit")
        hint.setTextColor(Color.WHITE); hint.textSize = 11f
        hint.gravity = android.view.Gravity.CENTER
        hint.setPadding(dp(16), dp(8), dp(16), dp(8))
        hint.setBackgroundColor(Color.parseColor("#99000000"))
        val hp = android.widget.FrameLayout.LayoutParams(-1, -2)
        hp.gravity = android.view.Gravity.BOTTOM
        hint.layoutParams = hp
        mid.addView(frame); mid.addView(hint)
        root.addView(mid)

        val panel = android.widget.LinearLayout(this)
        panel.orientation = android.widget.LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.parseColor("#0B1220"))
        panel.layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1.35f)
        val scroll = android.widget.ScrollView(this)
        scroll.layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
        val list = android.widget.LinearLayout(this)
        list.orientation = android.widget.LinearLayout.VERTICAL
        list.setPadding(dp(8), dp(6), dp(8), dp(24))
        stkListLayout = list
        scroll.addView(list)
        panel.addView(scroll)
        root.addView(panel)

        (window.decorView as android.view.ViewGroup).addView(root)
        stkOverlay = root
        updateStkPending()
    }

    private fun stkHeaderText(): String {
        val place = if (stkName.isNotBlank()) stkName else (L("جلسة #", "Session #") + stkSessionId)
        val who = if (stkCounter.isNotBlank()) "  •  $stkCounter" else ""
        return "🧮 " + L("جرد: ", "Stocktake: ") + place + who
    }

    private fun fmtQty(q: Double): String = if (q == Math.floor(q)) q.toLong().toString() else q.toString()

    private fun renderStkList() {
        val list = stkListLayout ?: return
        list.removeAllViews()
        var total = 0.0
        var shown = 0
        for (l in stkLines) {
            if (l.optInt("deleted", 0) == 1) continue
            shown++
            total += l.optDouble("qty", 0.0)
            list.addView(buildStkRow(l))
        }
        if (shown == 0) {
            val empty = TextView(this)
            empty.text = L("لا مسحاتٍ بعد — ابدأ بتوجيهِ الكاميرا لأوّلِ صنف.", "No scans yet — aim at the first item.")
            empty.setTextColor(Color.parseColor("#6B7280")); empty.textSize = 13f
            empty.setPadding(dp(12), dp(24), dp(12), dp(24))
            empty.gravity = android.view.Gravity.CENTER
            list.addView(empty)
        }
        stkTitleText?.text = stkHeaderText()
        updateStkPending(shown, total)
    }

    private fun buildStkRow(l: JSONObject): View {
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(8), dp(10), dp(8))
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(if (l.optBoolean("synced", false)) "#132033" else "#1E293B"))
            setStroke(dp(1), Color.parseColor("#334155"))
        }
        val rlp = android.widget.LinearLayout.LayoutParams(-1, -2); rlp.setMargins(dp(4), dp(4), dp(4), dp(4)); row.layoutParams = rlp

        val del = Button(this)
        del.text = "🗑"; del.textSize = 15f
        del.setBackgroundColor(Color.parseColor("#7F1D1D")); del.setTextColor(Color.WHITE)
        del.layoutParams = android.widget.LinearLayout.LayoutParams(dp(46), dp(46))
        del.setOnClickListener {
            l.put("deleted", 1); l.put("synced", false); l.put("ts", nowTs())
            saveStkLines(); renderStkList(); trySyncStocktake()
        }
        val minus = Button(this)
        minus.text = "−"; minus.textSize = 18f
        minus.setBackgroundColor(Color.parseColor("#334155")); minus.setTextColor(Color.WHITE)
        minus.layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(46))
        minus.setOnClickListener {
            val q = l.optDouble("qty", 0.0) - 1.0
            l.put("qty", if (q < 0) 0.0 else q); l.put("synced", false); l.put("ts", nowTs())
            saveStkLines(); renderStkList(); trySyncStocktake()
        }
        val qty = TextView(this)
        qty.text = fmtQty(l.optDouble("qty", 0.0))
        qty.setTextColor(Color.parseColor("#4ADE80")); qty.textSize = 18f
        qty.gravity = android.view.Gravity.CENTER
        qty.setTypeface(qty.typeface, android.graphics.Typeface.BOLD)
        qty.layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(46))
        qty.setOnClickListener { editQtyDialog(l) }
        val plus = Button(this)
        plus.text = "+"; plus.textSize = 18f
        plus.setBackgroundColor(Color.parseColor("#065F46")); plus.setTextColor(Color.WHITE)
        plus.layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(46))
        plus.setOnClickListener {
            l.put("qty", l.optDouble("qty", 0.0) + 1.0); l.put("synced", false); l.put("ts", nowTs())
            saveStkLines(); renderStkList(); trySyncStocktake()
        }
        val info = android.widget.LinearLayout(this)
        info.orientation = android.widget.LinearLayout.VERTICAL
        info.setPadding(dp(10), 0, dp(4), 0)
        info.layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        val nm = TextView(this)
        val nmv = l.optString("name", "")
        nm.text = if (nmv.isNotBlank()) nmv else L("صنف غير معرّف", "Unknown item")
        nm.setTextColor(if (nmv.isNotBlank()) Color.WHITE else Color.parseColor("#F59E0B")); nm.textSize = 14f
        nm.setTypeface(nm.typeface, android.graphics.Typeface.BOLD)
        val bc = TextView(this)
        bc.text = l.optString("barcode", "")
        bc.setTextColor(Color.parseColor("#94A3B8")); bc.textSize = 11f
        info.addView(nm); info.addView(bc)

        // الترتيب: الاسم/الباركود ثمّ − الكمية + في طرف، وزرُّ الحذفِ في الطرفِ المقابلِ بعيداً عن «−» (تفادي الحذفِ الخطأ)
        del.layoutParams = android.widget.LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(8), 0, 0, 0) }
        row.addView(minus); row.addView(qty); row.addView(plus); row.addView(info); row.addView(del)
        return row
    }

    private fun editQtyDialog(l: JSONObject) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(fmtQty(l.optDouble("qty", 0.0)))
        input.setSelectAllOnFocus(true)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("الكمية — ", "Quantity — ") + (l.optString("name", "").ifBlank { l.optString("barcode", "") }))
            .setView(input)
            .setPositiveButton(L("حفظ", "Save")) { _, _ ->
                val q = input.text.toString().trim().toDoubleOrNull() ?: l.optDouble("qty", 0.0)
                l.put("qty", if (q < 0) 0.0 else q); l.put("synced", false); l.put("ts", nowTs())
                saveStkLines(); renderStkList(); trySyncStocktake()
            }
            .setNegativeButton(L("إلغاء", "Cancel"), null)
            .show()
    }

    private fun updateStkPending(shown: Int = -1, total: Double = -1.0) {
        var pend = 0
        for (l in stkLines) { if (!l.optBoolean("synced", false)) pend++ }
        val cntTxt = if (shown >= 0) "  •  " + L("الأصناف: ", "Items: ") + shown + "  •  " + L("المجموع: ", "Total: ") + fmtQty(if (total < 0) 0.0 else total) else ""
        stkPendingText?.text = if (pend > 0) "⏳ " + L("بانتظار المزامنة: ", "Pending sync: ") + pend + cntTxt else "✅ " + L("الكلُّ مُزامَن", "All synced") + cntTxt
    }

    private fun exitStocktake() {
        trySyncStocktake()   // محاولةٌ أخيرةٌ للمزامنةِ قبلَ الخروج (البياناتُ محفوظةٌ محلياً على أيِّ حال)
        stkActive = false
        stkOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        stkOverlay = null; stkListLayout = null; stkPendingText = null; stkTitleText = null
        // امسحْ علامةَ الاستئنافِ التلقائيِّ فقط (نُبقي stk_last_sid ليعودَ من زرِّ القائمة).
        prefs.edit().remove("stk_active_sid").apply()
        Toast.makeText(this, L("خرجتَ من وضعِ الجرد — بياناتُك محفوظة. للعودة: القائمة ← جلسة الجرد", "Left stocktake — data saved. To return: Menu → Stocktake"), Toast.LENGTH_LONG).show()
        // إعادةُ بناءِ الشاشةِ نظيفةً (تُعيدُ تصميمَ الماسحِ الأصليَّ تماماً بلا أزرارٍ قديمةٍ عالقة)
        window.decorView.post { recreate() }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    // زرُّ الرجوع: إن كان الموقع مفتوحاً تنقّل داخله ثم عُد للماسح
    override fun onBackPressed() {
        // إن كنّا في وضعِ الجرد ⇒ اخرجْ منه أوّلاً (البياناتُ محفوظةٌ محلياً)
        if (stkActive) { exitStocktake(); return }
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
