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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
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
    // 🐞 v1.14 — أثناء انتظار نتيجة البصمة لتأكيد دخول QR: الكاميرا تبقى تعمل
    //   (بلا إيقافٍ)، فإن ابتعد المستخدمُ بالجوّال قليلاً (وهو يُعيد وضعَ إصبعه
    //   على حسّاس البصمة) قد تلتقط أيَّ باركودٍ آخر قريبٍ فوراً (كودٌ مختلفٌ
    //   يتجاوز التبريدَ الطبيعيّ) — فتظهر شاشةُ «صنفٌ غير معرّف» رغم أنّ الأمر
    //   لا علاقة له بالدخول إطلاقاً. نُجمّد استقبال أيّ مسحٍ آخر من لحظة قراءة
    //   رمز الدخول حتى تنتهي محاولةُ الدخول هذه (نجاحاً أو فشلاً أو إلغاءً).
    private var loginFlowBusy: Boolean = false
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var isServerConnected = false
    private var toneGenerator: ToneGenerator? = null
    // الموقع (jawwal) داخل التطبيق
    private var web: android.webkit.WebView? = null
    private var btnSite: Button? = null
    private var siteLoaded = false
    // 🐞 v1.19 — آخرُ عنوانٍ (ip:port) حُمِّل فعلياً داخل الـWebView. لازمٌ لاكتشافِ
    //   أيّ تغييرٍ فى العنوان بعد أوّل تحميل (انظر reloadSiteIfAddressChanged).
    private var loadedServerAddr: String? = null
    // 🐞 v1.22 — مهلةُ المسحِ التلقائيّة الجارية حالياً (إن وُجدت) — انظر scheduleAutoClear.
    private var resultClearRunnable: Runnable? = null
    // 🐞 v1.22 — هل انكشف الـWebView فعلياً (بعد onPageFinished)؟ يمنع كشفاً مزدوجاً
    //   ويُتيح مهلةَ أمانٍ لو تعطّل onPageFinished — انظر showSiteView/revealSiteView.
    private var _siteRevealed = false
    private var _siteRevealFallback: Runnable? = null
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
    // ═══════════ 📱 v1.23 — الربط الواحد بالمفتاح (خطة-ربط-الجوال) + الشاشة الرئيسيّة ═══════════
    //   الشاشة الحاليّة: home · scanner · enroll (مسح رمز الربط) · pclogin (مسح رمز دخول الكمبيوتر)
    private var mode = "home"
    private var homeView: View? = null
    private var homeName: TextView? = null
    private var homeCompany: TextView? = null
    private var homeDot: View? = null
    private var homeConn: TextView? = null
    private var homeNote: TextView? = null
    private var homeTiles: LinearLayout? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraWanted = false                     // لا كاميرا إلا في الماسح/الجرد/الربط/دخول الكمبيوتر
    // التذكرة (بعد فتح المفتاح بالبصمة) في ذاكرة العمليّة فقط — تبقى عبر recreate (تغيير اللغة/الخط/الخروج من الجرد)
    //   فلا تُطلب بصمةٌ جديدة لذلك، وتزول بإغلاق التطبيق.
    private var mdTicket: String?
        get() = sTicket
        set(v) { sTicket = v }
    private var mdTicketExp: Long
        get() = sTicketExp
        set(v) { sTicketExp = v }
    private var unlockBusy = false
    private var lastStatusFetch = 0L
    private val apiClient: OkHttpClient by lazy {
        httpClient.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()
    }
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
        buildHome()
        // 📱 v1.23 — الكاميرا لا تعمل عند الفتح: تبدأ فقط داخل الماسح/الجرد/الربط/دخول الكمبيوتر.
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
        // 🎙️ v1.12 — إذنُ الميكروفونِ للبحثِ الصوتيِّ داخلَ الموقع. طلبٌ منفصلٌ
        //   برمزٍ مختلفٍ (1002) حتى لا يتأثّرَ منطقُ تشغيلِ الكاميرا أعلاه بترتيبِ النتائج.
        //   اختياريٌّ تماماً: رفضُه لا يمنعُ التطبيقَ من العمل، فقط يعطّلُ البحثَ الصوتيّ.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
        }
        startHeartbeat()
        if ((prefs.getString("stk_active_sid", "") ?: "").isNotBlank()) {
            restoreActiveStocktake()   // 🧮 استئنافُ جلسةِ الجردِ إن كانت مفتوحةً قبلَ الإغلاق (يعملُ دونَ اتصال)
        } else {
            showHome()
            // فتحُ التطبيق = بصمةٌ واحدة تفتح المفتاح ⇒ تذكرةٌ + حالةُ الصلاحيّات (إن كان الجوال مربوطاً)
            if (isLinked() && mdStatus() != "revoked" && validTicket() == null) window.decorView.post { unlockDevice(L("افتح تطبيق الأوائل", "Unlock Al-Awael")) { } }
        }
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

    // بعد منحِ إذنِ الكاميرا أوّلَ مرّة: شغّلِ الكاميرا فوراً (بلا حاجةٍ لإغلاقِ التطبيقِ وفتحِه)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (cameraWanted) startCamera()
        }
    }
    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        dotConnectionStatus = findViewById(R.id.dotConnectionStatus)
        txtConnectionStatus = findViewById(R.id.txtConnectionStatus)
        btnTorch = findViewById(R.id.btnTorch)
        // 📱 v1.23 — «تحديث الحالة» صار تلقائيّاً؛ هذا الزرّ داخل الماسح صار «نمط النطق».
        findViewById<ImageButton>(R.id.btnRefresh).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            contentDescription = "نمط النطق"
            setOnClickListener { chooseSpeakMode() }
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
            reloadSiteIfAddressChanged()
            closeSettings()
            Toast.makeText(this, L("تم حفظ الإعدادات بنجاح!", "Settings saved!"), Toast.LENGTH_SHORT).show()
            checkServerStatus()
        }
        // 📱 v1.23 — الربط لم يعد من الإعدادات: يتمّ مرّةً واحدة من الشاشة الرئيسيّة برمزٍ من شاشة المستخدمين.
        findViewById<View>(R.id.btnScanLink).visibility = View.GONE
        findViewById<View>(R.id.lblRelinkHint).visibility = View.GONE
        unifySettingsPanel()
        setDotColor("#EF4444")
        setBadgeStyle("#1E293B", "#38BDF8", "#334155")

        // زر الانتقال إلى الموقع
        web = findViewById(R.id.web)
        btnSite = findViewById(R.id.btnSite)
        btnSite?.visibility = View.GONE   // الدخول للنظام صار مربّعاً في الشاشة الرئيسيّة
        // جسرٌ بين الموقع (jawwal) والتطبيق
        web?.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun backToScanner() { runOnUiThread { closeSite() } }   // يعود للشاشة الرئيسيّة
            // يطلبه زرُّ الباركود في الموقع: يُظهر مستطيلَ الكاميرا فوق الموقع لخانةٍ محدّدة
            @android.webkit.JavascriptInterface
            fun scanToField(fieldId: String) { runOnUiThread { startSiteScan(fieldId) } }
            // يناديه الموقعُ بعد نجاحِ الصنف: أغلقِ الكاميرا (المسحُ اكتمل بنجاح)
            @android.webkit.JavascriptInterface
            fun closeScan() { runOnUiThread { if (scanForSite) { playToneSuccess(); stopSiteScan() } } }

            /**
             * 📤 v1.6 — ورقةُ مشاركةِ أندرويد الحقيقيّة.
             *
             * لماذا: `navigator.share` **غيرُ موجودٍ في WebView إطلاقاً** — لا في
             * أندرويد ولا في غيرِه. فزرُّ «مشاركة» في الموقعِ كان يفشلُ دائماً على
             * الجوّالِ مهما أصلحنا الواجهة. الحلُّ أن يُمرّرَ الموقعُ الملفَّ إلينا
             * فنفتحَ نحن ورقةَ النظام (واتساب · تيليجرام · جهاتُ الاتّصال).
             *
             * @param b64  محتوى الملفِّ بترميز Base64 (بلا بادئةِ data:)
             * @param name اسمُ الملفِّ كما يظهرُ للمستقبِل
             * @param mime نوعُه (application/pdf غالباً)
             */
            @android.webkit.JavascriptInterface
            fun shareBase64(b64: String, name: String, mime: String) {
                runOnUiThread { shareFileFromBase64(b64, name, mime) }
            }

            /** يسألُه الموقعُ ليعرفَ أنّ الجسرَ موجودٌ وصالحٌ للمشاركة. */
            @android.webkit.JavascriptInterface
            fun canShareFiles(): Boolean = true

            /**
             * 🖨️ v1.7 — الطباعةُ الحقيقيّةُ من الجوّال.
             *
             * السبب: `window.print()` **لا وجودَ له في WebView** — لا يطبعُ ولا
             * يرمي خطأً، فالضغطةُ تذهبُ بلا أثرٍ ولا رسالة. وهو بالضبطُ ما رأيتَه.
             * الصحيحُ أن نُسلّمَ المستندَ لخدمةِ الطباعةِ في أندرويد (PrintManager)
             * فتفتحُ معاينةَ النظامِ ومنها الطابعةُ أو «حفظ كـPDF».
             */
            @android.webkit.JavascriptInterface
            fun printHtml(html: String, name: String) {
                runOnUiThread { printDocument(html, if (name.isBlank()) "مستند" else name) }
            }

            /**
             * 🖨️ v1.11 — الطباعةُ بطريقِ الـPDF، لا بـPrintManager.
             *
             * جرّبنا خدمةَ الطباعةِ مرّتين فرفضَها الجهازُ برسالةِ
             * «Can print only from an activity» مهما صحّحنا السياق. فتوقّفنا عن
             * مصارعةِ واجهةٍ لا تنصاع، وسلكنا طريقاً **مضموناً ومُجرَّباً عندنا**:
             * الخادمُ يُنتجُ الـPDF (نفسُه الذي يخرجُ على الحاسوبِ حرفيّاً)، ونحن
             * نفتحُه بعارضِ الجهاز — ومن العارضِ زرُّ الطباعةِ والمشاركةِ والحفظ.
             * أقصرُ طريقٍ للنتيجةِ التي يريدُها المستخدم، لا للواجهةِ التي أردناها.
             */
            @android.webkit.JavascriptInterface
            fun openPdf(b64: String, name: String) {
                runOnUiThread { openPdfFromBase64(b64, name) }
            }

            /** 📤 مشاركةُ نصٍّ (لا ملفّ) — لأزرارِ «مشاركة» النصّيّةِ في صفحةِ الجوّال. */
            @android.webkit.JavascriptInterface
            fun shareText(text: String, title: String) {
                runOnUiThread {
                    try {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                        }
                        startActivity(Intent.createChooser(i, L("إرسال عبر", "Send via"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        toastMsg(L("تعذّرت المشاركة", "Share failed"))
                    }
                }
            }

            /** 📱 v1.23 — هل يستطيع هذا الجوال الدخول للنظام بالبصمة؟ (مربوط + «الدخول للنظام» مفعّلة) */
            @android.webkit.JavascriptInterface
            fun hasBiometricDevice(): Boolean = bridgeHasBiometricDevice()

            /** زرّ «الدخول بالبصمة» في شاشة الدخول: بصمة ⇒ جلسة المستخدم الأساسيّ.
             *  النتيجة عبر window.onBiometricLoginResult(ok, ...) — نفس العقد القديم. */
            @android.webkit.JavascriptInterface
            fun loginWithBiometric() {
                runOnUiThread { bridgeLoginWithBiometric() }
            }

            /** انتهت الجلسة (401) أثناء العمل: أعد الدخول وحدك ثم نادِ window.onAwaelReauth(ok). */
            @android.webkit.JavascriptInterface
            fun reauth() {
                runOnUiThread { bridgeReauth() }
            }
        }, "AndroidApp")
        // 📱 v1.23 — لا قائمة (☰): هذا الزرّ داخل الماسح صار «الرئيسيّة».
        findViewById<Button>(R.id.btnMenu).apply {
            background = roundBg("#99000000", 26f)
            text = "🏠"
            contentDescription = L("الرئيسيّة", "Home")
            setOnClickListener { showHome() }
        }
        // ★ طبّقْ لغةَ الواجهةِ بعدَ ربطِ كلِّ العناصر
        applyLanguage()
    }

    /** يُظهر الكاميرا (الماسح السريع) ملءَ الشاشة فوق الموقع — قراءةٌ سهلةٌ قويّة. */
    private fun startSiteScan(fieldId: String) {
        scanForSite = true
        scanSiteField = fieldId
        lastScannedCode = null; lastScanTime = 0L; isFrameClear = true
        startCamera()
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
        stopCamera()
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

    // ═══════════════════════════════════════════════════════════════
    // 📎 v1.8 — رفعُ المرفقات من الجوّال
    //
    //   سببُ العطب: عنصرُ <input type="file"> في صفحةِ الويب لا يفتحُ شيئاً
    //   داخلَ WebView **ما لم يُنفَّذ `onShowFileChooser`**. لا خطأَ ولا رسالة:
    //   تضغطُ «إضافة مرفق» فلا يحدثُ شيء — وهو ثالثُ عطبٍ من عائلةٍ واحدة
    //   (الطباعةُ والمشاركةُ والمرفقات): WebView ليس متصفّحاً كاملاً.
    //
    //   نفتحُ نحن مُنتقيَ النظامِ (معرضٌ · ملفّاتٌ · كاميرا) ونُعيدُ النتيجةَ
    //   إلى الصفحةِ كما يفعلُ المتصفّح.
    // ═══════════════════════════════════════════════════════════════
    private var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = filePathCallback
            filePathCallback = null
            cb?.onReceiveValue(
                android.webkit.WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    /**
     * 🖨️ v1.11 — يكتبُ الـPDF في ذاكرةِ التطبيقِ ويفتحُه بعارضِ الجهاز.
     *
     * نفسُ مزوّدِ الملفّاتِ المستعملِ في المشاركة (وقد ثبتَ عملُه عندك)، فلا بنيةَ
     * جديدةَ ولا أذوناتٍ إضافيّة. وإن لم يوجدْ عارضُ PDF على الجهازِ فتحنا ورقةَ
     * المشاركةِ بدلَه — فلا تصلُ إلى طريقٍ مسدود.
     */
    private fun openPdfFromBase64(b64: String, name: String) {
        try {
            val dir = java.io.File(cacheDir, "share")
            if (!dir.exists()) dir.mkdirs()
            val safe = (if (name.isBlank()) "document.pdf" else name)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .let { if (it.lowercase().endsWith(".pdf")) it else "$it.pdf" }
            val f = java.io.File(dir, safe)
            java.io.FileOutputStream(f).use {
                it.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(view)
                toastMsg(L("افتحْ قائمةَ العارضِ ثمّ «طباعة»",
                           "Open the viewer menu then Print"))
            } catch (e: android.content.ActivityNotFoundException) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, L("فتح بواسطة", "Open with"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            val v = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }
                    catch (x: Exception) { "?" }
            toastMsg(L("تعذّر فتحُ الملفّ [v", "Open failed [v") + v + "]: " + (e.message ?: ""))
        }
    }

    /** يبقى حيّاً حتى تنتهي الطباعة: WebView محلّيٌّ يُجمَعُ قبلَ أن يُطبَع. */
    private var printWeb: android.webkit.WebView? = null

    /**
     * 🖨️ v1.7 — يطبعُ مستنداً عبرَ خدمةِ الطباعةِ في أندرويد.
     *
     * نُحمّلُ الوثيقةَ في WebView **غيرِ مرئيّ** بعنوانِ الخادمِ الأساسيّ، فتُحلّ
     * روابطُ الخطوطِ والأنماطِ النسبيّة (/static/…) كما في الصفحةِ تماماً —
     * فيخرجُ المطبوعُ مطابقاً لما تراه لا نصّاً عارياً.
     * ثمّ يُسلَّمُ إلى PrintManager فتفتحُ معاينةُ النظام: طابعةٌ أو حفظٌ PDF.
     */
    private fun printDocument(html: String, name: String) {
        try {
            // 🔧 v1.9 — «Can print only from an activity».
            //
            //   رسالةُ أندرويد الحرفيّة، وسببُها دقيق: `PrintManager` يرفضُ العملَ
            //   إن لم يكن سياقُه **نشاطاً** (Activity). وطلبُ الخدمةِ من داخلِ كائنٍ
            //   مجهولٍ (object : WebViewClient) كان يُمرّرُ سياقاً ملفوفاً لا النشاطَ
            //   نفسَه على بعضِ الأجهزة — فيفشلُ الطبعُ بهذه الرسالةِ بالضبط.
            //   العلاجُ صريح: `this@MainActivity` لا غير.
            //
            //   وأضفتُ إلحاقَ الإطارِ بشجرةِ الشاشةِ بمقاسِ ١×١ شفّاف: بعضُ الأجهزة
            //   (سامسونج منها) لا تُتمّ الطبعَ من إطارٍ غيرِ مُلحَقٍ بنافذة.
            //   يُنزَعُ بعدَ تسليمِ المستندِ للنظام فلا يبقى أثر.
            val act = this@MainActivity
            val w = android.webkit.WebView(act)
            w.settings.javaScriptEnabled = false
            w.alpha = 0f
            val root = act.window.decorView as android.view.ViewGroup
            root.addView(w, android.view.ViewGroup.LayoutParams(1, 1))

            fun cleanup() {
                try { root.removeView(w) } catch (e: Exception) {}
                printWeb = null
            }

            w.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    val adapter = (view ?: return).createPrintDocumentAdapter(name)
                    val attrs = android.print.PrintAttributes.Builder().build()

                    // 🔁 v1.10 — سياقان لا واحد.
                    //   «Can print only from an activity» تعني أنّ الخدمةَ بُنيت
                    //   بسياقٍ ليس نشاطاً. النشاطُ عندنا هو `act`، لكنّ بعضَ
                    //   الأجهزةِ تُرجعُ خدمةً مبنيّةً بسياقٍ ملفوف. فنُجرّبُ
                    //   سياقَ الإطارِ نفسِه بديلاً قبلَ أن نعلنَ الفشل.
                    val contexts = ArrayList<android.content.Context>()
                    contexts.add(act)
                    (view.context as? android.app.Activity)?.let { if (it !== act) contexts.add(it) }
                    var done = false
                    var lastErr: String? = null
                    for (ctx in contexts) {
                        try {
                            val pm = ctx.getSystemService(Context.PRINT_SERVICE)
                                        as android.print.PrintManager
                            pm.print(name, adapter, attrs)
                            done = true
                            break
                        } catch (e: Exception) {
                            lastErr = e.message
                        }
                    }
                    if (done) {
                        // لا نحذفُ الإطارَ فوراً: النظامُ يقرأُ منه أثناءَ المعاينة.
                        w.postDelayed({ cleanup() }, 60000)
                    } else {
                        // 🏷️ الرسالةُ تحملُ رقمَ النسخة: أيُّ لقطةِ شاشةٍ تُعرّفُ نفسَها،
                        //   فلا نضيعُ في «هل بنى النسخةَ الجديدةَ أم القديمة؟».
                        val v = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                        } catch (e: Exception) { "?" }
                        toastMsg(L("تعذّرت الطباعة [v", "Print failed [v") + v + "]: " + (lastErr ?: ""))
                        cleanup()
                    }
                }
            }
            printWeb = w
            w.loadDataWithBaseURL(getServerUrl(), html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            toastMsg(L("تعذّرت الطباعة: ", "Print failed: ") + (e.message ?: ""))
        }
    }

    /** رسالةٌ قصيرةٌ — اختصارٌ يُستعملُ في مسارَي المشاركةِ والتنزيل. */
    private fun toastMsg(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    /**
     * 📤 v1.6 — يكتبُ الملفَّ في ذاكرةِ التطبيقِ الخاصّةِ ثمّ يفتحُ ورقةَ المشاركة.
     *
     * لماذا FileProvider ولا نمرّرُ المسارَ مباشرة: منذ أندرويد ٧ يرفضُ النظامُ
     * تمريرَ `file://` إلى تطبيقٍ آخرَ ويرمي FileUriExposedException. فالمزوّدُ
     * يمنحُ واتساب (أو غيرَه) إذنَ قراءةٍ **مؤقّتاً ولهذا الملفِّ وحدَه**.
     *
     * والملفّاتُ في `cache/share` تُنظّفُ قبلَ كلِّ مشاركةٍ فلا تتراكمُ نُسخٌ قديمة.
     */
    private fun shareFileFromBase64(b64: String, name: String, mime: String) {
        try {
            val dir = java.io.File(cacheDir, "share")
            if (!dir.exists()) dir.mkdirs()
            dir.listFiles()?.forEach { runCatching { it.delete() } }

            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.pdf" }
            val f = java.io.File(dir, safe)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            java.io.FileOutputStream(f).use { it.write(bytes) }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (mime.isBlank()) "application/pdf" else mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, safe)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, L("إرسال عبر", "Send via"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toastMsg(L("تعذّرت المشاركة: ", "Share failed: ") + (e.message ?: ""))
        }
    }

    /**
     * ⬇️ v1.6 — تنزيلُ ملفٍّ من داخلِ الموقع.
     *
     * حالتان مختلفتان تماماً:
     *   • `blob:` — ملفٌّ **مصنوعٌ داخلَ الصفحةِ نفسِها** لا يعرفُه النظام، فمديرُ
     *     التنزيلاتِ لا يستطيعُ جلبَه. نطلبُ من الصفحةِ أن تُمرّرَه عبرَ الجسرِ
     *     (نفسُ مسارِ المشاركة) بدلَ أن يضيعَ الضغطُ بلا أثر.
     *   • رابطٌ عاديّ — نُسلّمُه لمديرِ تنزيلاتِ أندرويد ليُكملَه في الخلفيّة.
     */
    private fun downloadOrOpen(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            if (url.startsWith("blob:")) {
                toastMsg(L("استعمل زرّ «مشاركة» لإرسال الملفّ",
                           "Use the Share button to send the file"))
                return
            }
            val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                setTitle(name)
                setMimeType(mimeType)
                setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, name)
                addRequestHeader("Cookie",
                    android.webkit.CookieManager.getInstance().getCookie(url) ?: "")
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
            toastMsg(L("جارٍ التنزيل: ", "Downloading: ") + name)
        } catch (e: Exception) {
            toastMsg(L("تعذّر التنزيل: ", "Download failed: ") + (e.message ?: ""))
        }
    }



    /** يتحقّق فعلياً من اتصال الخادم قبل الدخول للموقع، ثم يفتحه فقط لو ردّ فعلاً.
     *  🐞 v1.20 — كان `openSite()` القديم يفتح الموقعَ مباشرةً بلا أيّ تحقّق، فيظهرُ
     *  خطأُ متصفّحٍ خامٌ (ERR_CONNECTION_REFUSED) بدل رسالةِ التطبيقِ الواضحة لو الخادم
     *  مش شغّال أو الشبكة غيرُ سليمة. الآن: تحقّقٌ حقيقيٌّ أوّلاً — الدخولُ فقط بعد ردٍّ. */
    private fun openSite() {
        val w = web ?: return
        val testUrl = "${getServerUrl()}/api/scan"
        val request = Request.Builder().url(testUrl).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateConnectionUi(false)
                runOnUiThread {
                    playToneError(); vibrateError()
                    toastMsg(L("🔴 لا يوجد اتصال بالخادم — تأكّد من الشبكة والبرنامج قبل فتح الموقع",
                               "🔴 No connection to the server — check the network and program before opening the site"))
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val connected = response.code in 200..499
                updateConnectionUi(connected)
                if (connected) {
                    runOnUiThread { showSiteView(w) }
                } else {
                    runOnUiThread {
                        playToneWarning(); vibrateWarning()
                        toastMsg(L("⚠️ الخادم ردّ بخطأ (${response.code}) — تأكّد أن البرنامج يعمل على الكمبيوتر",
                                   "⚠️ Server error (${response.code}) — make sure the program is running"))
                    }
                }
            }
        })
    }

    /** يعرض واجهة الموقع فعلياً بعد التأكّد من الاتصال (كان هذا جسمَ openSite القديم). */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showSiteView(w: android.webkit.WebView) {
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

            // 🔗 v1.6 — الروابطُ الخارجيّةُ تخرجُ إلى النظام، لا تُفتحُ داخلَ الموقع.
            //   كان WebViewClient فارغاً، فأيُّ wa.me أو mailto أو tel يحاولُ
            //   التحميلَ **داخلَ** الإطارِ فيظهرُ خطأُ صفحةٍ أو شاشةٌ بيضاء.
            //   القاعدةُ البسيطة: ما كان من خادمِنا يبقى داخلاً، وما سواه يخرج.
            w.webViewClient = object : android.webkit.WebViewClient() {
                private fun handle(url: String?): Boolean {
                    val u = url ?: return false
                    val server = getServerUrl()
                    val internal = u.startsWith(server) ||
                        u.startsWith("file:") || u.startsWith("data:") ||
                        u.startsWith("javascript:") || u.startsWith("about:")
                    if (internal) return false
                    return try {
                        val i = if (u.startsWith("intent:"))
                            Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
                        else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i); true
                    } catch (e: Exception) {
                        toastMsg("لا يوجد تطبيقٌ يفتحُ هذا الرابط"); true
                    }
                }
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?, req: android.webkit.WebResourceRequest?
                ): Boolean = handle(req?.url?.toString())

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?, url: String?
                ): Boolean = handle(url)

                // 🐞 v1.22 — لا نكشف الـWebView (وننتقل فعلياً من شاشة الماسح) إلا بعد أن
                //   تنتهي الصفحةُ فعلاً من التحميل. قبل هذا: كان الانتقالُ يحدث فوراً مع
                //   loadUrl، فيظهر إطارٌ أسودُ/فارغٌ لثوانٍ (خصوصاً على شبكةٍ بطيئة أو
                //   مقيّدة) قبل ظهور محتوى الصفحة — بلاغ المستخدم: «فتح شاشة سوداء،
                //   رجعت لقيتها اتصلت ثم راحت للموقع». الفحصُ السابق (openSite) يتأكّد
                //   فقط من ردّ الخادم على /api/scan؛ لا يضمن أن الصفحةَ نفسَها (jawwal.html
                //   بكل أصولها) ستُحمَّل بسرعة، فالانتظارُ الحقيقيُّ يجب أن يمتدّ لهذه اللحظة.
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!_siteRevealed) revealSiteView(w)
                }
            }

            // 📎 v1.8 — مُنتقي الملفّات: بدونه لا يفتحُ زرُّ «إضافة مرفق» شيئاً.
            w.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onShowFileChooser(
                    view: android.webkit.WebView?,
                    cb: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)   // ألغِ طلباً سابقاً معلّقاً
                    filePathCallback = cb
                    return try {
                        val intent = params?.createIntent()
                            ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                        fileChooserLauncher.launch(
                            Intent.createChooser(intent, L("اختر ملفّاً", "Choose a file")))
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        toastMsg(L("تعذّر فتحُ منتقي الملفّات", "Could not open file picker"))
                        false
                    }
                }

                /**
                 * 🎙️ v1.12 — البحثُ الصوتيُّ داخلَ الموقع (jawwal) يستخدمُ
                 * `navigator.mediaDevices.getUserMedia({audio:true})` من الـJavaScript.
                 * WebView يرفضُ الميكروفونَ ما لم نمنحْه صراحةً هنا — تماماً كملفِّ
                 * الاختيار أعلاه. بلا هذا، طلبُ المتصفّحِ يُرفَضُ صامتاً.
                 */
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    val req = request ?: return
                    runOnUiThread {
                        val wanted = req.resources.filter {
                            it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        }.toTypedArray()
                        if (wanted.isNotEmpty() && ActivityCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            req.grant(wanted)
                        } else {
                            req.deny()
                        }
                    }
                }
            }

            // ⬇️ v1.6 — التنزيل: WebView لا يُنزّلُ شيئاً بنفسِه.
            //   بلا هذا المستمع، زرُّ PDF على الجوّالِ لا يفعلُ شيئاً — بصمتٍ تامّ.
            //   نستقبلُ نحن الرابطَ ونُسلّمُه لمديرِ تنزيلاتِ أندرويد.
            w.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                downloadOrOpen(url, contentDisposition, mimeType)
            }

            // ⏳ نبقى على شاشة الماسح ونُظهر حالةً واضحة — الانتقالُ الفعليّ فى onPageFinished
            //   أعلاه (أو مهلة الأمان تحت لو تعطّل onPageFinished لأيّ سبب).
            //   نُلغي أيّ مهلةَ مسحٍ تلقائيّةٍ معلَّقة من مسحةٍ سابقة كي لا تمسح رسالة التحميل.
            resultClearRunnable?.let { heartbeatHandler.removeCallbacks(it); resultClearRunnable = null }
            _siteRevealed = false
            txtItemName.text = ""
            showTopResult(L("⏳ جارٍ تحميل الموقع…", "⏳ Loading the site…"), "#CC0F172A")
            txtItemDetails.text = ""
            txtStatusBadge.text = L("انتظر قليلاً…", "Please wait…")
            setBadgeStyle("#1E293B", "#38BDF8", "#334155")

            w.loadUrl("${getServerUrl()}/static/m/jawwal.html")
            siteLoaded = true
            loadedServerAddr = getServerUrl()

            // 🛟 مهلةُ أمانٍ: لو تأخّر تحميلُ الصفحةِ فعلاً (أو لم يُطلَق onPageFinished
            //   لأيّ سببٍ نادر)، لا نُبقي المستخدمَ عالقاً للأبد على "جارٍ التحميل".
            _siteRevealFallback?.let { heartbeatHandler.removeCallbacks(it) }
            val fb = Runnable { if (!_siteRevealed) revealSiteView(w) }
            _siteRevealFallback = fb
            heartbeatHandler.postDelayed(fb, 10000)
            return
        }
        revealSiteView(w)
    }

    /** يكشف واجهةَ الموقعِ فعلياً (بعد تأكّد التحميل أو مهلة الأمان) ويُخفي شاشةَ الماسح. */
    private fun revealSiteView(w: android.webkit.WebView) {
        if (_siteRevealed) return
        _siteRevealed = true
        _siteRevealFallback?.let { heartbeatHandler.removeCallbacks(it); _siteRevealFallback = null }
        homeView?.visibility = View.GONE
        stopCamera()
        mode = "site"
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
        // 🐞 v1.22 — لازمٌ نُصفّر _siteRevealed هنا: هى فقط تمنعُ الكشفَ المزدوجَ *أثناء*
        //   فتحةٍ واحدة (onPageFinished قد يتبعه إطلاقُ مهلةِ الأمان لولا هذا الحارس) —
        //   لا يجب أن تمنع الكشفَ فى المرّة القادمة (الموقعُ محمَّلٌ مسبقاً siteLoaded=true
        //   فتذهب showSiteView مباشرةً إلى revealSiteView التي كانت سترفضُ الكشفَ لولا
        //   هذا التصفير — عطلٌ كان سيُخفي الموقعَ للأبد بعد أوّل إغلاق).
        _siteRevealed = false
        w.visibility = View.GONE
        showHome()   // 📱 v1.23 — العودة من الموقع إلى الشاشة الرئيسيّة (لا إلى الكاميرا)
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

    /** 🐞 v1.22 — نتيجةُ المسحِ (اسمُ الصنف/السعر/رسالةُ الرفض) كانت تبقى ظاهرةً على
     *  الشاشةِ للأبد حتى المسحةِ التالية — لو تأخّر الكاشيرُ فى المسحةِ التالية، تفضلُ
     *  الشاشةُ عالقةً على صنفٍ قديم (بلاغ المستخدم: نفسُ الصورة/النتيجة إلى ما لا نهاية).
     *  الآن: بعد مهلةٍ قصيرةٍ (٤ ثوانٍ) من عرض أيّ نتيجةٍ نهائيّة، تُمسح تلقائياً وتعودُ
     *  الشاشةُ لحالةِ الانتظار — إلا لو وصلت مسحةٌ جديدةٌ قبلها (عندها تُلغى المهلةُ
     *  القديمةُ فوراً فى بداية onBarcodeDetected، وتُستبدَل بمهلةٍ جديدةٍ لنتيجةِ
     *  المسحةِ الجديدة عند عرضها). */
    private fun scheduleAutoClear(delayMs: Long = 4000) {
        resultClearRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        val r = Runnable { clearDisplay() }
        resultClearRunnable = r
        heartbeatHandler.postDelayed(r, delayMs)
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
        setScannerViews(false)
        homeView?.visibility = View.GONE
        layoutSettings.visibility = View.VISIBLE
        layoutSettings.bringToFront()
        txtTestResult.visibility = View.GONE
    }
    private fun closeSettings() {
        layoutSettings.visibility = View.GONE
        showHome()
    }

    /** 📱 v1.23 — شاشة إعداداتٍ واحدة داكنة: الصوت (تشغيل/نمط/لغة/مستوى) · لغة التطبيق · حجم الخط · الاتصال اليدويّ. */
    private fun unifySettingsPanel() {
        val secConn = findViewById<View>(R.id.secConn)
        val secLang = findViewById<LinearLayout>(R.id.secLang)
        secConn.visibility = View.VISIBLE
        secLang.visibility = View.VISIBLE
        // الاتصال اليدويّ في آخر الشاشة (نادر الاستعمال — الربط يضبط العنوان وحده)
        (secConn.parent as? android.view.ViewGroup)?.let { p -> p.removeView(secConn); p.addView(secConn) }
        fun extraBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label; setTextColor(Color.WHITE); isAllCaps = false
            background = roundBg("#334155", 12f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }
        secLang.addView(extraBtn(L("🗣️ نمط النطق (كامل / الكلمة الأولى / صفير)", "🗣️ Speech mode")) { chooseSpeakMode() })
        secLang.addView(extraBtn(L("🔠 حجم الخط", "🔠 Font size")) { openFontDialog() })
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
            .setMessage(L("تطبيق الأوائل للجوال\nالإصدار: $v", "Al-Awael Mobile\nVersion: $v") +
                (if (isLinked()) "\n\n" + L("مربوط باسم: ", "Linked to: ") + (prefs.getString("md_full_name", "") ?: "") +
                    "\n" + L("رقم الجوال في النظام: #", "Device #") + deviceIdStr() else ""))
            .setPositiveButton(L("حسناً", "OK"), null)
            // إعادة الربط (نادرة): الإدارة تنشئ رمزاً جديداً من شاشة المستخدمين ← أجهزة الجوال
            .setNeutralButton(L("ربط من جديد", "Re-link")) { _, _ -> startEnroll() }
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

    /** 🐞 v1.19 — أيّ تغييرٍ فعليٍّ فى عنوان الخادم (ip/port) لازمٌ يُعيد تحميلَ
     *   الموقعِ داخل الـWebView فوراً. قبل هذا: siteLoaded كان يبقى true للأبد طولَ
     *   عمرِ العمليّة، فالموقعُ يفضلُ واقفاً على أوّل عنوانٍ حُمِّل بيه — أيّ إعادةِ ربطٍ
     *   (قديمة أو حديثة أو تعديلٍ يدويّ من الإعدادات) لا تُغيّر شيئاً فى الواجهةِ الفعليّة
     *   إلا بعد إغلاق التطبيق بالكامل (force stop) وإعادة فتحه. هذا هو السببُ الحقيقيُّ
     *   وراء ERR_CONNECTION_REFUSED المستمرّ رغم تغييرِ الربط — لا علاقةَ له بطريقةِ
     *   الربط (قديم/حديث) نفسِها. نستدعي هذه الدالة عقب كلّ حفظٍ لعنوان الخادم.
     */
    private fun reloadSiteIfAddressChanged() {
        val addr = getServerUrl()
        if (loadedServerAddr == null) { loadedServerAddr = addr; return }
        if (loadedServerAddr == addr) return
        loadedServerAddr = addr
        // 🐞 v1.20 — لا نُحمّل الرابطَ الجديد هنا مباشرةً (كان قد يُحمَّل قبل التأكّد
        //   الفعليّ من نجاح الاتصال، فيُخزَّن فى الـWebView خطأٌ خامٌ لا يزول إلا
        //   بتغييرٍ آخر). بدل ذلك: نُصفّر `siteLoaded` فقط — فيمرّ الفتحُ القادمُ
        //   عبر `openSite()` بتحقّقِ الاتصالِ الحقيقيِّ ثم تحميلٍ نظيفٍ فعلاً.
        if (siteLoaded) siteLoaded = false
    }

    // ═══════════════════════════════════════════════════════════════
    // 📱 v1.23 — الربط الواحد بالمفتاح (خطة-ربط-الجوال.md)
    //
    //   • الربط مرّةً واحدة: الإدارة تعرض رمزاً لمرّةٍ واحدة (دقيقتان) من شاشة المستخدمين ← أجهزة الجوال.
    //   • الجوال يولّد زوج مفاتيح داخل شريحة الأمان (Android Keystore)؛ الخاصّ لا يخرج أبداً،
    //     ومقفولٌ بالبصمة نفسها (كلّ توقيعٍ يحتاج بصمة) — لا بوّابةٌ شكليّة ولا رمزٌ يمرّ على الشبكة.
    //   • كلّ استخدام: الخادم يعطي «سؤالاً» جديداً ← البصمة توقّعه ← الخادم يتحقّق ويعطي تذكرةً قصيرة.
    //   • أربع صلاحيّات يتحكّم بها المالك/الأدمن: الدخول للنظام · قارئ الباركود · الجرد · الدخول للكمبيوتر.
    // ═══════════════════════════════════════════════════════════════
    private val KEY_ALIAS = "awael_device_key_v1"

    private fun isLinked(): Boolean = deviceIdStr().isNotBlank()
    private fun deviceIdStr(): String = prefs.getString("md_device_id", "") ?: ""
    private fun mdStatus(): String = prefs.getString("md_status", "") ?: ""

    private fun validTicket(): String? {
        val t = mdTicket ?: return null
        return if (System.currentTimeMillis() < mdTicketExp) t else { mdTicket = null; null }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun jsq(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")

    private fun biometricAvailable(): Boolean {
        val bm = BiometricManager.from(this)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /** يولّد مفتاح هذا الجوال داخل شريحة الأمان (يُستبدل أيّ مفتاحٍ سابق) ويعيد العامّ (X.509، base64). */
    private fun generateDeviceKey(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        val b = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)   // بصمةٌ لكلّ توقيع
        } else {
            @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)   // -1 = بصمةٌ لكلّ توقيع
        }
        // إضافة بصمةٍ جديدة على الجوال تُبطل المفتاح (لا يدخل بها أحدٌ أضاف إصبعه لاحقاً)
        if (Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(true)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(b.build())
        val kp = kpg.generateKeyPair()
        return android.util.Base64.encodeToString(kp.public.encoded, android.util.Base64.NO_WRAP)
    }

    /** يطلب البصمة ويوقّع [message] بمفتاح الجوال. done(sig, err): sig=null&err=null ⇒ ألغى المستخدم. */
    private fun signWithBiometric(message: String, reason: String, done: (String?, String?) -> Unit) {
        val sig: Signature
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val pk = ks.getKey(KEY_ALIAS, null) as? PrivateKey
            if (pk == null) { done(null, "no_key"); return }
            sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(pk)
        } catch (e: KeyPermanentlyInvalidatedException) {
            done(null, "key_invalidated"); return
        } catch (e: Exception) {
            done(null, "key_error: " + (e.message ?: "")); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val s = result.cryptoObject?.signature ?: sig
                        s.update(message.toByteArray(Charsets.UTF_8))
                        done(android.util.Base64.encodeToString(s.sign(), android.util.Base64.NO_WRAP), null)
                    } catch (e: Exception) {
                        done(null, "sign_error: " + (e.message ?: ""))
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED) done(null, null)
                    else done(null, "bio: $errString")
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(L("تأكيد بالبصمة", "Confirm with fingerprint"))
            .setSubtitle(reason)
            .setNegativeButtonText(L("إلغاء", "Cancel"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        try {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
        } catch (e: Exception) {
            done(null, "bio: " + (e.message ?: ""))
        }
    }

    /** رسالةٌ مفهومة لأخطاء المفتاح/البصمة (null = ألغى المستخدم ⇒ لا رسالة). */
    private fun explainSignError(err: String?) {
        if (err == null) return
        val msg = when {
            err == "no_key" -> L("مفتاح هذا الجوال غير موجود — اطلب من الإدارة رمز ربطٍ جديد",
                                 "This phone's key is missing — ask the admin for a new link code")
            err == "key_invalidated" -> {
                clearLink(L("تغيّرت البصمات المسجّلة على الجوال", "Fingerprints on the phone changed"))
                L("تغيّرت البصمات المسجّلة على الجوال — لأمانك أُلغي الربط. اطلب من الإدارة ربطاً جديداً",
                  "Phone fingerprints changed — the link was cancelled for safety. Ask the admin to link again")
            }
            err.startsWith("bio:") -> L("تعذّرت البصمة: ", "Fingerprint failed: ") + err.removePrefix("bio:").trim()
            else -> L("تعذّر استعمال مفتاح الجوال: ", "Phone key error: ") + err
        }
        playToneError(); vibrateError(); toastMsg(msg)
    }

    // ─────────────── الطلبات ───────────────
    /** طلبٌ JSON للخادم. cb(code, json, headers) على الخيط الرئيسيّ؛ code = -1 عند انقطاع الشبكة. */
    private fun api(method: String, path: String, body: JSONObject?, ticket: String?,
                    cb: (Int, JSONObject, Headers?) -> Unit) {
        try {
            val b = Request.Builder().url(getServerUrl() + path)
            if (!ticket.isNullOrBlank()) b.header("X-Device-Ticket", ticket)
            if (method == "GET") b.get()
            else b.post((body ?: JSONObject()).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            apiClient.newCall(b.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { cb(-1, JSONObject(), null) }
                }
                override fun onResponse(call: Call, response: Response) {
                    val txt = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
                    val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
                    val code = response.code
                    val h = response.headers
                    response.close()
                    runOnUiThread { cb(code, j, h) }
                }
            })
        } catch (e: Exception) {
            runOnUiThread { cb(-1, JSONObject(), null) }
        }
    }

    private fun netErrorText(): String =
        L("🔴 لا يوجد اتصال بالخادم — تأكّد أن الجوال والكمبيوتر على نفس الشبكة والبرنامج يعمل",
          "🔴 No connection — check the network and that the program is running")

    /** يحفظ ما يرسله الخادم عن الجوال (الاسم، الشركة، الحالة، الصلاحيّات، التذكرة). */
    private fun applyDeviceInfo(j: JSONObject) {
        val e = prefs.edit()
        if (j.has("full_name")) e.putString("md_full_name", j.optString("full_name"))
        if (j.has("username")) e.putString("md_username", j.optString("username"))
        if (j.has("company")) e.putString("md_company", j.optString("company"))
        if (j.has("status")) e.putString("md_status", j.optString("status"))
        e.putString("md_until", j.optString("until", ""))
        j.optJSONObject("caps")?.let { e.putString("md_caps", it.toString()) }
        e.remove("md_reason")
        e.apply()
        val t = j.optString("ticket", "")
        if (t.isNotBlank() && t != "null") {
            mdTicket = t
            mdTicketExp = System.currentTimeMillis() + (j.optLong("expires_in", 43200L) - 60L) * 1000L
        }
        renderHome()
    }

    /** الإدارة سحبت الجوال (أو سُحب تلقائيّاً): نبقي رقمه للعرض فقط ونُظهر «ربط الجوال». */
    private fun markRevoked(reason: String) {
        mdTicket = null
        prefs.edit().putString("md_status", "revoked").putString("md_reason", reason).apply()
        renderHome()
    }

    private fun clearLink(reason: String) {
        mdTicket = null
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {}
        prefs.edit().putString("md_status", "revoked").putString("md_reason", reason).apply()
        renderHome()
    }

    /** ردٌّ مرفوض من طلبٍ بالتذكرة: يحدّث الحالة المحليّة ليطابق الخادم. يُرجع true إن عالجه. */
    private fun handleDeviceDenial(code: Int, j: JSONObject): Boolean {
        val c = j.optString("code", "")
        runOnUiThread {
            when (c) {
                "ticket_invalid" -> mdTicket = null
                "device_revoked" -> markRevoked(j.optString("reason", j.optString("error", "")))
                "device_suspended", "not_allowed" -> fetchStatus(true)
            }
        }
        return c.isNotBlank() && code in 400..499
    }

    /** فتح المفتاح بالبصمة ⇒ تذكرةٌ + الحالة. then(ok) على الخيط الرئيسيّ. */
    private fun unlockDevice(reason: String, then: (Boolean) -> Unit) {
        val did = deviceIdStr().toIntOrNull()
        if (did == null || mdStatus() == "revoked") { renderHome(); then(false); return }
        if (unlockBusy) { then(false); return }
        unlockBusy = true
        api("POST", "/api/mobile/challenge", JSONObject().put("purpose", "unlock").put("device_id", did), null) { code, j, _ ->
            if (code == -1) { unlockBusy = false; updateConnectionUi(false); toastMsg(netErrorText()); then(false); return@api }
            val nonce = j.optString("nonce", "")
            if (code != 200 || nonce.isBlank()) {
                unlockBusy = false
                if (j.optString("code") == "device_revoked") markRevoked(j.optString("error", ""))
                toastMsg(j.optString("error", L("تعذّر فتح التطبيق", "Unlock failed")))
                then(false); return@api
            }
            signWithBiometric("awael|unlock|$did|$nonce", reason) { sig, err ->
                if (sig == null) { unlockBusy = false; explainSignError(err); renderHome(); then(false); return@signWithBiometric }
                api("POST", "/api/mobile/unlock",
                    JSONObject().put("device_id", did).put("nonce", nonce).put("signature", sig), null) { c2, j2, _ ->
                    unlockBusy = false
                    when {
                        c2 == -1 -> { toastMsg(netErrorText()); then(false) }
                        c2 == 200 -> {
                            updateConnectionUi(true)
                            applyDeviceInfo(j2)
                            if (j2.optString("status") == "suspended")
                                toastMsg(L("هذا الجوال موقوف مؤقّتاً حتى ", "This phone is suspended until ") + j2.optString("until") +
                                         L(" — راجع الإدارة", " — contact the admin"))
                            then(validTicket() != null)
                        }
                        else -> {
                            if (j2.optString("code") == "device_revoked") markRevoked(j2.optString("reason", j2.optString("error", "")))
                            playToneError(); vibrateError()
                            toastMsg(j2.optString("error", L("تعذّر فتح التطبيق", "Unlock failed")))
                            then(false)
                        }
                    }
                }
            }
        }
    }

    /** ينفّذ [action] بتذكرةٍ صالحة (يطلب البصمة فقط إن لم توجد). */
    private fun withTicket(reason: String, action: (String) -> Unit) {
        val t = validTicket()
        if (t != null) { action(t); return }
        unlockDevice(reason) { ok -> val t2 = validTicket(); if (ok && t2 != null) action(t2) }
    }

    /** تحديث الحالة والصلاحيّات بلا بصمة (بالتذكرة إن وُجدت) — عند العودة للرئيسيّة. */
    private fun fetchStatus(force: Boolean) {
        val t = validTicket() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastStatusFetch < 5000) return
        lastStatusFetch = now
        api("GET", "/api/mobile/status", null, t) { code, j, _ ->
            when {
                code == 200 -> { updateConnectionUi(true); applyDeviceInfo(j) }
                code == -1 -> updateConnectionUi(false)
                else -> handleDeviceDenial(code, j)
            }
        }
    }

    // ─────────────── الصلاحيّات ───────────────
    private fun capInfo(cap: String): JSONObject? =
        try { JSONObject(prefs.getString("md_caps", "{}") ?: "{}").optJSONObject(cap) } catch (e: Exception) { null }

    private fun capAllowed(cap: String): Boolean = capInfo(cap)?.optBoolean("allowed", false) ?: false

    /** سبب المنع للعرض، أو null إن كانت الصلاحيّة متاحة. */
    private fun capBlockReason(cap: String): String? {
        if (!isLinked() || mdStatus() == "revoked") return L("الجوال غير مربوط — اضغط «ربط الجوال»", "Phone not linked")
        if (mdStatus() == "suspended")
            return L("هذا الجوال موقوف مؤقّتاً حتى ", "This phone is suspended until ") + (prefs.getString("md_until", "") ?: "") +
                   L(" — راجع الإدارة", " — contact the admin")
        val ci = capInfo(cap)
        if (ci == null) return null   // لم تصل الحالة بعد (بلا شبكة) — الخادم يحسم عند التنفيذ
        if (ci.optBoolean("allowed", false)) return null
        return if (ci.optString("state") == "until")
            L("موقوفة حتى ", "Suspended until ") + ci.optString("until") + L(" — راجع الإدارة", " — contact the admin")
        else L("غير مفعّلة لهذا الجوال — راجع الإدارة", "Not enabled for this phone — contact the admin")
    }

    // ─────────────── الشاشة الرئيسيّة ───────────────
    private fun setScannerViews(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        previewView.visibility = v
        findViewById<View>(R.id.scanBox).visibility = v
        findViewById<View>(R.id.headerStack).visibility = v
        bottomBar.visibility = v
        findViewById<View>(R.id.btnMenu).visibility = v
        btnTorch.visibility = v
        findViewById<View>(R.id.btnRefresh).visibility = v
        btnSettings.visibility = View.GONE
        btnSite?.visibility = View.GONE
    }

    private fun showHome() {
        if (stkActive) return
        if (scanForSite) stopSiteScan()
        mode = "home"
        loginFlowBusy = false
        stopCamera()
        setScannerViews(false)
        layoutSettings.visibility = View.GONE
        web?.let { if (it.visibility == View.VISIBLE) { it.visibility = View.GONE; _siteRevealed = false } }
        val h = homeView ?: return
        h.visibility = View.VISIBLE
        h.bringToFront()
        renderHome()
        fetchStatus(false)
    }

    /** شاشة الكاميرا (الماسح/الربط/دخول الكمبيوتر) — نفس تصميم الماسح القديم. */
    private fun showCameraScreen(newMode: String, hint: String) {
        mode = newMode
        homeView?.visibility = View.GONE
        layoutSettings.visibility = View.GONE
        setScannerViews(true)
        val scanner = newMode == "scanner"
        txtScanCount?.visibility = if (scanner) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnRefresh).visibility = if (scanner) View.VISIBLE else View.GONE   // نمط النطق
        lastScannedCode = null; lastScanTime = 0L; isFrameClear = true
        resultClearRunnable?.let { heartbeatHandler.removeCallbacks(it); resultClearRunnable = null }
        showTopResult(hint, "#CC0F172A")
        txtItemName.text = ""
        txtItemDetails.text = ""
        txtStatusBadge.text = if (scanner) L("جاهز للمسح", "Ready to scan") else L("بانتظار الرمز…", "Waiting for the code…")
        setBadgeStyle("#1E293B", "#38BDF8", "#334155")
        previewView.bringToFront()
        findViewById<View>(R.id.scanBox).bringToFront()
        findViewById<View>(R.id.headerStack).bringToFront()
        bottomBar.bringToFront()
        findViewById<View>(R.id.btnMenu).bringToFront()
        btnTorch.bringToFront()
        findViewById<View>(R.id.btnRefresh).bringToFront()
        startCamera()
    }

    private fun buildHome() {
        val root = previewView.parent as android.view.ViewGroup
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B1220"))
        scroll.isFillViewport = true
        scroll.elevation = dp(30).toFloat()   // فوق عناصر الماسح المرفوعة (elevation) مهما كان ترتيبها
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = android.view.Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(18), dp(22) + statusBarH(), dp(18), dp(18))

        val logo = ImageView(this)
        logo.setImageResource(R.mipmap.ic_launcher)
        logo.layoutParams = LinearLayout.LayoutParams(dp(78), dp(78))
        col.addView(logo)
        val title = TextView(this)
        title.text = L("نظام الأوائل", "Al-Awael")
        title.setTextColor(Color.WHITE); title.textSize = 20f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.gravity = android.view.Gravity.CENTER
        title.setPadding(0, dp(8), 0, dp(14))
        col.addView(title)

        // بطاقة الحالة: الاسم الكامل · الشركة · نقطة الاتصال
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = roundBg("#1E293B", dp(16).toFloat())
        card.layoutParams = LinearLayout.LayoutParams(-1, -2)
        val name = TextView(this)
        name.setTextColor(Color.WHITE); name.textSize = 17f
        name.setTypeface(name.typeface, android.graphics.Typeface.BOLD)
        homeName = name
        val comp = TextView(this)
        comp.setTextColor(Color.parseColor("#94A3B8")); comp.textSize = 13f
        comp.setPadding(0, dp(2), 0, dp(6))
        homeCompany = comp
        val connRow = LinearLayout(this)
        connRow.orientation = LinearLayout.HORIZONTAL
        connRow.gravity = android.view.Gravity.CENTER_VERTICAL
        val dot = View(this)
        dot.layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(6) }
        homeDot = dot
        val conn = TextView(this)
        conn.textSize = 12f
        homeConn = conn
        connRow.addView(dot); connRow.addView(conn)
        val note = TextView(this)
        note.textSize = 13f
        note.setPadding(0, dp(8), 0, 0)
        note.visibility = View.GONE
        homeNote = note
        card.addView(name); card.addView(comp); card.addView(connRow); card.addView(note)
        col.addView(card)

        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.VERTICAL
        tiles.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        homeTiles = tiles
        col.addView(tiles)

        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        col.addView(spacer)

        // أزرار صغيرة: الإعدادات · عن التطبيق
        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = android.view.Gravity.CENTER
        foot.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        fun small(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 13f; isAllCaps = false
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundBg("#1E293B", dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener { onClick() }
        }
        foot.addView(small(L("⚙️ الإعدادات", "⚙️ Settings")) { openSettings() })
        foot.addView(small(L("ℹ️ عن التطبيق", "ℹ️ About")) { showAbout() })
        col.addView(foot)

        scroll.addView(col)
        root.addView(scroll, android.view.ViewGroup.LayoutParams(-1, -1))
        homeView = scroll
        updateHomeConnection()
        renderHome()
    }

    private fun updateHomeConnection() {
        val dot = homeDot ?: return
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (isServerConnected) "#22C55E" else "#EF4444"))
        }
        homeConn?.text = if (isServerConnected) L("متصل بالنظام", "Connected") else L("غير متصل بالخادم", "Not connected")
        homeConn?.setTextColor(Color.parseColor(if (isServerConnected) "#4ADE80" else "#F87171"))
    }

    private fun renderHome() {
        val tiles = homeTiles ?: return
        val linked = isLinked() && mdStatus() != "revoked"
        homeName?.text = if (linked) (prefs.getString("md_full_name", "") ?: "").ifBlank { prefs.getString("md_username", "") ?: "" }
                         else L("الجوال غير مربوط", "Phone not linked")
        homeCompany?.text = if (linked) (prefs.getString("md_company", "") ?: "") else
            L("الإدارة تعرض رمز الربط من: المستخدمين ← أجهزة الجوال", "Admin shows the link code from Users → Mobile devices")
        val note: String? = when {
            isLinked() && mdStatus() == "revoked" ->
                L("⛔ سُحب ربط هذا الجوال", "⛔ This phone's link was revoked") +
                    ((prefs.getString("md_reason", "") ?: "").let { if (it.isNotBlank()) " — $it" else "" }) +
                    L("\nاطلب من الإدارة رمز ربطٍ جديد", "\nAsk the admin for a new link code")
            linked && mdStatus() == "suspended" ->
                L("⏸ موقوف مؤقّتاً حتى ", "⏸ Suspended until ") + (prefs.getString("md_until", "") ?: "") + L(" — راجع الإدارة", " — contact the admin")
            linked && validTicket() == null -> L("🔒 مقفل — اضغط أيّ مربّعٍ للفتح بالبصمة", "🔒 Locked — tap a tile to unlock")
            else -> null
        }
        homeNote?.let { n ->
            if (note == null) n.visibility = View.GONE
            else {
                n.visibility = View.VISIBLE; n.text = note
                n.setTextColor(Color.parseColor(if (note.startsWith("⛔")) "#F87171" else if (note.startsWith("⏸")) "#FBBF24" else "#93C5FD"))
            }
        }
        updateHomeConnection()
        tiles.removeAllViews()
        if (!linked) {
            tiles.addView(tileRow(listOf(Triple("link", "🔗", L("ربط الجوال", "Link phone")))))
            return
        }
        tiles.addView(tileRow(listOf(
            Triple("mobile_login", "🏠", L("الدخول للنظام", "Open the system")),
            Triple("scanner", "📷", L("قارئ الباركود", "Barcode scanner")))))
        tiles.addView(tileRow(listOf(
            Triple("stocktake", "🧮", L("الجرد", "Stocktake")),
            Triple("pc_login", "🖥️", L("الدخول للكمبيوتر", "Computer login")))))
    }

    private fun tileRow(items: List<Triple<String, String, String>>): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(-1, -2)
        for (it in items) row.addView(buildTile(it.first, it.second, it.third))
        return row
    }

    private fun buildTile(cap: String, icon: String, label: String): View {
        val blocked = if (cap == "link") null else capBlockReason(cap)
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = android.view.Gravity.CENTER
        t.setPadding(dp(8), dp(16), dp(8), dp(14))
        t.background = roundBg(if (blocked == null) (if (cap == "link") "#0369A1" else "#1E293B") else "#111827", dp(16).toFloat())
        t.layoutParams = LinearLayout.LayoutParams(0, dp(118), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
        val ic = TextView(this)
        ic.text = if (blocked == null) icon else "🔒"
        ic.textSize = 30f
        ic.gravity = android.view.Gravity.CENTER
        val lb = TextView(this)
        lb.text = label
        lb.textSize = 14f
        lb.gravity = android.view.Gravity.CENTER
        lb.setTypeface(lb.typeface, android.graphics.Typeface.BOLD)
        lb.setTextColor(Color.parseColor(if (blocked == null) "#F8FAFC" else "#64748B"))
        lb.setPadding(0, dp(6), 0, 0)
        t.addView(ic); t.addView(lb)
        val until = capInfo(cap)?.optString("state") == "until"
        if (blocked != null && until) {
            val sub = TextView(this)
            sub.text = L("حتى ", "until ") + (capInfo(cap)?.optString("until") ?: "")
            sub.textSize = 11f; sub.gravity = android.view.Gravity.CENTER
            sub.setTextColor(Color.parseColor("#FBBF24"))
            t.addView(sub)
        }
        t.setOnClickListener { onTile(cap) }
        return t
    }

    private fun onTile(cap: String) {
        if (cap == "link") { startEnroll(); return }
        val why = capBlockReason(cap)
        if (why != null) { playToneWarning(); toastMsg(why); return }
        when (cap) {
            "mobile_login" -> withTicket(L("الدخول للنظام", "Open the system")) { t -> openSystemWithTicket(t, true) }
            "scanner" -> withTicket(L("قارئ الباركود", "Barcode scanner")) { _ ->
                if (capBlockReason("scanner") == null)
                    showCameraScreen("scanner", L("وجّه الكاميرا نحو الباركود…", "Aim the camera at a barcode…"))
                else toastMsg(capBlockReason("scanner") ?: "")
            }
            "stocktake" -> withTicket(L("الجرد", "Stocktake")) { t -> openStocktakeList(t) }
            "pc_login" -> startPcLogin()
        }
    }

    // ─────────────── الربط (مرّةً واحدة) ───────────────
    private fun startEnroll() {
        if (!biometricAvailable()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L("سجّل بصمتك أوّلاً", "Enroll a fingerprint first"))
                .setMessage(L("الربط يقفل مفتاح الجوال ببصمتك. افتح إعدادات الجوال ← الأمان ← البصمة وسجّل بصمةً، ثم ارجع واضغط «ربط الجوال».",
                              "Linking locks the phone key with your fingerprint. Open phone Settings → Security → Fingerprint, add one, then come back."))
                .setPositiveButton(L("حسناً", "OK"), null)
                .show()
            return
        }
        showCameraScreen("enroll", L("📷 امسح رمز الربط من الكمبيوتر\n(المستخدمين ← أجهزة الجوال ← ربط جوال جديد)",
                                     "📷 Scan the link code from the computer\n(Users → Mobile devices → Link new phone)"))
    }

    private fun handleEnrollQR(code: String) {
        val uri = try { android.net.Uri.parse(code) } catch (e: Exception) { null }
        val token = uri?.getQueryParameter("t") ?: ""
        val ip = uri?.getQueryParameter("ip") ?: ""
        val port = uri?.getQueryParameter("port") ?: "5005"
        if (token.isBlank()) { playToneWarning(); showTopResult(L("⚠️ رمز ربط غير صالح", "⚠️ Invalid link code"), "#B45309"); return }
        loginFlowBusy = true
        if (ip.isNotBlank()) {
            prefs.edit().putString("server_ip", ip).putString("server_port", port).apply()
            reloadSiteIfAddressChanged()
            edtServerIp.setText(ip); edtServerPort.setText(port)
        }
        vibrateSuccess()
        showTopResult(L("⏳ جارٍ الربط…", "⏳ Linking…"), "#CC0F172A")
        api("POST", "/api/mobile/challenge", JSONObject().put("purpose", "enroll").put("token", token), null) { c, j, _ ->
            val nonce = j.optString("nonce", "")
            if (c != 200 || nonce.isBlank()) {
                loginFlowBusy = false
                playToneError(); vibrateError()
                toastMsg(if (c == -1) netErrorText() else j.optString("error", L("رمز الربط غير صالح", "Invalid link code")))
                showHome(); return@api
            }
            val who = j.optString("full_name", "") + (j.optString("company", "").let { if (it.isNotBlank()) " — $it" else "" })
            val pub = try { generateDeviceKey() } catch (e: Exception) {
                loginFlowBusy = false
                playToneError(); vibrateError()
                toastMsg(L("تعذّر إنشاء مفتاح الجوال: ", "Could not create the phone key: ") + (e.message ?: ""))
                showHome(); return@api
            }
            signWithBiometric("awael|enroll|${sha256Hex(token)}|$nonce", L("ربط هذا الجوال باسم: ", "Link this phone to: ") + who) { sig, err ->
                if (sig == null) { loginFlowBusy = false; explainSignError(err); showHome(); return@signWithBiometric }
                val label = (Build.MANUFACTURER + " " + Build.MODEL).trim()
                api("POST", "/api/mobile/enroll", JSONObject().put("token", token).put("nonce", nonce)
                    .put("public_key", pub).put("signature", sig).put("device_label", label), null) { c2, j2, _ ->
                    loginFlowBusy = false
                    if (c2 == 200 && j2.optInt("device_id", 0) > 0) {
                        prefs.edit().putString("md_device_id", j2.optInt("device_id").toString()).apply()
                        applyDeviceInfo(j2)
                        updateConnectionUi(true)
                        playToneSuccess(); vibrateSuccess()
                        toastMsg(L("✅ رُبط الجوال باسم ", "✅ Phone linked to ") + j2.optString("full_name"))
                    } else {
                        playToneError(); vibrateError()
                        toastMsg(if (c2 == -1) netErrorText() else j2.optString("error", L("تعذّر الربط", "Link failed")))
                    }
                    showHome()
                }
            }
        }
    }

    // ─────────────── الدخول للنظام على الجوال ───────────────
    /** يفتح جلسة المستخدم الأساسيّ بالتذكرة ويزرعها في الـWebView. cb(ok, username, fullName, error) */
    private fun startMobileSession(ticket: String, retried: Boolean, cb: (Boolean, String, String, String) -> Unit) {
        api("POST", "/api/mobile/session", null, ticket) { code, j, headers ->
            if (code == 200 && j.optBoolean("success", false)) {
                try {
                    val cm = android.webkit.CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    val srv = getServerUrl()
                    for (c in headers?.values("Set-Cookie") ?: emptyList()) cm.setCookie(srv, c)
                    cm.flush()
                } catch (e: Exception) {}
                val u = j.optJSONObject("user")
                cb(true, u?.optString("username", "") ?: "", u?.optString("full_name", "") ?: "", "")
                return@api
            }
            if (code == -1) { cb(false, "", "", "network"); return@api }
            handleDeviceDenial(code, j)
            if (j.optString("code") == "ticket_invalid" && !retried) {
                // الخادم أُعيد تشغيله (التذاكر في ذاكرته): بصمةٌ واحدة ثم نكمل
                unlockDevice(L("إعادة الدخول", "Sign in again")) { ok ->
                    val t2 = validTicket()
                    if (ok && t2 != null) startMobileSession(t2, true, cb) else cb(false, "", "", "biometric_failed")
                }
                return@api
            }
            cb(false, "", "", j.optString("error", "server"))
        }
    }

    private fun openSystemWithTicket(ticket: String, fromHome: Boolean) {
        startMobileSession(ticket, false) { ok, _, _, err ->
            if (ok) {
                siteLoaded = false   // تحميلٌ نظيف بالجلسة الجديدة
                openSite()
            } else if (fromHome && err != "biometric_failed") {
                playToneError(); vibrateError()
                toastMsg(if (err == "network") netErrorText() else err)
            }
        }
    }

    private fun bridgeHasBiometricDevice(): Boolean =
        isLinked() && mdStatus() != "revoked" && capAllowed("mobile_login")

    /** زرّ «الدخول بالبصمة» في شاشة دخول الموقع — بصمةٌ صريحة دائماً (المستخدم ضغطها للتوّ). */
    private fun bridgeLoginWithBiometric() {
        fun result(js: String) { web?.evaluateJavascript("window.onBiometricLoginResult && window.onBiometricLoginResult($js)", null) }
        if (!bridgeHasBiometricDevice()) { result("false,'no_device'"); return }
        unlockDevice(L("الدخول للنظام بالبصمة", "Log in with fingerprint")) { ok ->
            val t = validTicket()
            if (!ok || t == null) { result("false,'biometric_failed'"); return@unlockDevice }
            startMobileSession(t, true) { ok2, uname, full, err ->
                if (ok2) { playToneSuccess(); vibrateSuccess(); result("true,'${jsq(uname)}','${jsq(full)}'") }
                else { playToneError(); vibrateError(); result("false,'server','${jsq(err)}'") }
            }
        }
    }

    /** انتهت الجلسة أثناء العمل (401): أعِد الدخول وحدك — بالتذكرة إن كانت صالحة (بلا بصمة)، وإلا بصمةٌ واحدة. */
    private fun bridgeReauth() {
        fun done(ok: Boolean) { web?.evaluateJavascript("window.onAwaelReauth && window.onAwaelReauth(" + (if (ok) "true" else "false") + ")", null) }
        if (!bridgeHasBiometricDevice()) { done(false); return }
        val t = validTicket()
        if (t != null) { startMobileSession(t, false) { ok, _, _, _ -> done(ok) }; return }
        unlockDevice(L("انتهت الجلسة — أعد الدخول بالبصمة", "Session ended — sign in with fingerprint")) { ok ->
            val t2 = validTicket()
            if (!ok || t2 == null) { done(false); return@unlockDevice }
            startMobileSession(t2, true) { ok2, _, _, _ -> done(ok2) }
        }
    }

    // ─────────────── الجرد: الجلسات المفتوحة تصل مباشرةً (بلا مسح رمز) ───────────────
    private fun openStocktakeList(ticket: String) {
        api("GET", "/api/mobile/stocktake/sessions", null, ticket) { code, j, _ ->
            if (code != 200) {
                if (code == -1) { toastMsg(netErrorText()); return@api }
                handleDeviceDenial(code, j)
                if (j.optString("code") == "ticket_invalid") {
                    unlockDevice(L("الجرد", "Stocktake")) { ok -> val t2 = validTicket(); if (ok && t2 != null) openStocktakeList(t2) }
                } else { playToneError(); toastMsg(j.optString("error", L("تعذّر جلب جلسات الجرد", "Could not load sessions"))) }
                return@api
            }
            val arr = j.optJSONArray("sessions") ?: JSONArray()
            val counter = j.optString("counter", "")
            if (arr.length() == 0) {
                playToneWarning()
                toastMsg(L("لا توجد جلسة جرد مفتوحة — افتح جلسةً من الكمبيوتر (المخزون ← الجرد)",
                           "No open stocktake session — open one on the computer"))
                return@api
            }
            fun enter(o: JSONObject) {
                val place = o.optString("place", "").ifBlank { o.optString("warehouse_name", "") }
                enterStocktakeSession(o.optInt("id").toString(), o.optString("code", ""), place, counter,
                    o.optInt("retain_days", 30), true)
            }
            if (arr.length() == 1) { enter(arr.getJSONObject(0)); return@api }
            val labels = Array(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                "#" + o.optInt("id") + "  " + o.optString("place", "").ifBlank { o.optString("warehouse_name", "") }
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L("اختر جلسة الجرد", "Choose a stocktake session"))
                .setItems(labels) { _, which -> enter(arr.getJSONObject(which)) }
                .setNegativeButton(L("إلغاء", "Cancel"), null)
                .show()
        }
    }

    // ─────────────── الدخول للكمبيوتر (رمز شاشة الدخول) ───────────────
    private fun startPcLogin() {
        if (!isLinked()) { startEnroll(); return }
        showCameraScreen("pclogin", L("📷 امسح رمز الدخول الظاهر على شاشة الكمبيوتر", "📷 Scan the login code on the computer screen"))
    }

    private fun handlePcLoginQR(code: String) {
        val uri = try { android.net.Uri.parse(code) } catch (e: Exception) { null }
        val qr = uri?.getQueryParameter("token") ?: ""
        val did = deviceIdStr().toIntOrNull()
        if (qr.isBlank() || did == null) { playToneWarning(); showTopResult(L("⚠️ رمز دخول غير صالح", "⚠️ Invalid login code"), "#B45309"); return }
        val ip = uri?.getQueryParameter("ip") ?: ""
        if (ip.isNotBlank()) {
            val port = uri?.getQueryParameter("port") ?: "5005"
            prefs.edit().putString("server_ip", ip).putString("server_port", port).apply()
            reloadSiteIfAddressChanged()
            edtServerIp.setText(ip); edtServerPort.setText(port)
        }
        loginFlowBusy = true
        vibrateSuccess()
        showTopResult(L("⏳ جارٍ التأكيد…", "⏳ Confirming…"), "#CC0F172A")
        api("POST", "/api/mobile/challenge", JSONObject().put("purpose", "pc_login").put("device_id", did), null) { c, j, _ ->
            val nonce = j.optString("nonce", "")
            if (c != 200 || nonce.isBlank()) {
                loginFlowBusy = false
                if (j.optString("code") == "device_revoked") markRevoked(j.optString("error", ""))
                playToneError(); vibrateError()
                toastMsg(if (c == -1) netErrorText() else j.optString("error", L("تعذّر التأكيد", "Could not confirm")))
                showHome(); return@api
            }
            signWithBiometric("awael|pc_login|$did|$nonce|$qr", L("تأكيد الدخول على الكمبيوتر", "Confirm login on the computer")) { sig, err ->
                if (sig == null) { loginFlowBusy = false; explainSignError(err); showHome(); return@signWithBiometric }
                api("POST", "/api/mobile/pc-login", JSONObject().put("device_id", did).put("nonce", nonce)
                    .put("qr_token", qr).put("signature", sig), null) { c2, j2, _ ->
                    loginFlowBusy = false
                    if (c2 == 200 && j2.optBoolean("success", false)) {
                        playToneSuccess(); vibrateSuccess()
                        toastMsg(L("✅ تمّ الدخول على الكمبيوتر", "✅ Logged in on the computer"))
                    } else {
                        if (j2.optString("code") == "device_revoked") markRevoked(j2.optString("error", ""))
                        else if (c2 == 403) fetchStatus(true)
                        playToneError(); vibrateError()
                        toastMsg(if (c2 == -1) netErrorText() else j2.optString("error", L("تعذّر التأكيد", "Could not confirm")))
                    }
                    showHome()
                }
            }
        }
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
            updateHomeConnection()
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
        cameraWanted = true
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            this.cameraProvider = cameraProvider
            if (!cameraWanted) return@addListener   // أُغلقت الشاشة قبل أن تجهز الكاميرا
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
    /** 📱 v1.23 — الكاميرا تُطفأ خارج الماسح/الجرد/الربط/دخول الكمبيوتر (بطاريّة + خصوصيّة). */
    private fun stopCamera() {
        cameraWanted = false
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        camera = null
        isTorchOn = false
        try { btnTorch.setImageResource(android.R.drawable.ic_menu_day) } catch (e: Exception) {}
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
    private fun onBarcodeDetected(code: String) {
        // 🐞 v1.22 — مسحةٌ جديدةٌ وصلت: ألغِ أيّ مهلةَ مسحٍ تلقائيٍّ معلَّقة من نتيجةِ
        //   المسحةِ السابقة، فورًا (قبل أيّ فرع)، كي لا تُمسَح نتيجةُ هذه المسحةِ الجديدة
        //   وسط انتظار الردّ من الخادم.
        resultClearRunnable?.let { heartbeatHandler.removeCallbacks(it); resultClearRunnable = null }
        // 📱 v1.23 — رمز ربط الجوال (من شاشة المستخدمين ← أجهزة الجوال)
        if (code.startsWith("awael://enroll")) {
            if (mode == "enroll" && !loginFlowBusy) handleEnrollQR(code)
            else if (mode != "enroll") runOnUiThread { playToneWarning()
                toastMsg(L("هذا رمز ربط جوال — افتحه من الشاشة الرئيسيّة ← «ربط الجوال» أو «عن التطبيق» ← «ربط من جديد»",
                           "This is a phone link code — use Home → Link phone")) }
            return
        }
        // الرموز القديمة (ربط الماسح / رمز جلسة الجرد) أُلغيت: صارت صلاحيّاتٍ على الجوال المربوط.
        if (code.startsWith("awael://link") || code.startsWith("awael://stocktake")) {
            runOnUiThread { playToneWarning(); vibrateWarning()
                toastMsg(L("رمزٌ من الطريقة القديمة — الربط الآن مرّةً واحدة من شاشة المستخدمين ← أجهزة الجوال، والجرد من مربّع «الجرد» في الشاشة الرئيسيّة",
                           "Old-style code — link once from Users → Mobile devices; stocktake is on the Home screen")) }
            return
        }
        // 🐞 v1.14 — محاولةٌ جاريةٌ تنتظر البصمة/الخادم: تجاهلْ أيَّ مسحٍ آخر حتى تنتهي.
        if (loginFlowBusy) return
        // 🔐 رمز شاشة الدخول في الكمبيوتر
        if (code.startsWith("awael://login")) {
            if (mode == "pclogin") handlePcLoginQR(code)
            else runOnUiThread { playToneWarning()
                toastMsg(L("للدخول للكمبيوتر: الشاشة الرئيسيّة ← «الدخول للكمبيوتر» ثم امسح الرمز",
                           "To log in on the computer: Home → Computer login")) }
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
        // لا شيء يُرسَل للكمبيوتر إلا من شاشة «قارئ الباركود» (الرمز والربط لهما شاشاتهما).
        if (mode != "scanner") {
            if (mode == "enroll" || mode == "pclogin") runOnUiThread { playToneWarning()
                showTopResult(if (mode == "enroll") L("⚠️ هذا ليس رمز ربط — امسح الرمز من شاشة المستخدمين", "⚠️ Not a link code")
                              else L("⚠️ هذا ليس رمز دخول — امسح الرمز من شاشة دخول الكمبيوتر", "⚠️ Not a login code"), "#B45309") }
            return
        }
        // 📱 v1.23 — المسح يحمل تذكرة الجوال المربوط (بلا sid ولا مفتاحِ جلسة): الخادم يعرف مَن ولأيّ كمبيوتر.
        val ticket = validTicket()
        if (ticket == null) {
            runOnUiThread {
                playToneWarning()
                showTopResult(L("🔒 افتح بالبصمة ثم أعد المسح", "🔒 Unlock with fingerprint, then scan again"), "#B45309")
                unlockDevice(L("قارئ الباركود", "Barcode scanner")) { ok ->
                    if (ok) showTopResult(L("✅ جاهز — أعد المسح", "✅ Ready — scan again"), "#15803D")
                }
            }
            return
        }
        val targetUrl = "${getServerUrl()}/api/scan"
        val jsonPayload = JSONObject().apply { put("barcode", code) }
        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(targetUrl).header("X-Device-Ticket", ticket).post(requestBody).build()
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
                    scheduleAutoClear()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                try {
                    val resJson = JSONObject(responseBody)
                    // 🐞 v10.510 — كانت isFound تفترض true افتراضياً لو ردّ الخطأ (403 مفتاح
                    //   جلسة غير صالح، 400 باركود فارغ...) ما فيهوش حقل "found" أصلاً، فيقع
                    //   فى الفرع الأخير («تم النقل بنجاح») رغم أن الخادم رفض الطلب فعلياً —
                    //   بالضبط ما بلَّغه المستخدم: «يقول تم النقل بنجاح ولكن لا ينقل». نفحص
                    //   نجاح الاستجابة (2xx) أوّلاً ونعرض رسالة الخادم الحقيقية عند الرفض.
                    if (!response.isSuccessful) {
                        val errMsg = resJson.optString("error", resJson.optString("message", "خطأ من الخادم (${response.code})"))
                        handleDeviceDenial(response.code, resJson)   // تذكرة منتهية/جوال مسحوب/صلاحيّة موقوفة
                        runOnUiThread {
                            playToneError(); vibrateError()
                            showTopResult(L("🔴 رُفض: $errMsg", "🔴 Rejected: $errMsg"), "#B91C1C")
                            speak("تعذّر الإرسال", "Send failed")
                            txtItemName.text = ""
                            txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                            txtStatusBadge.text = "❌ $errMsg"
                            setBadgeStyle("#7F1D1D", "#EF4444", "#DC2626")
                            scheduleAutoClear()
                        }
                        return
                    }
                    val isFound = resJson.optBoolean("found", true)
                    val itemName = resJson.optString("item_name", resJson.optString("name", "صنف: $code"))
                    val itemPrice = resJson.optString("price", "")
                    runOnUiThread {
                        if (isFound) {
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
                            scheduleAutoClear()
                        } else {
                            // 🐞 v10.510 — كان هنا فرعٌ ثالثٌ ميت («تم النقل بنجاح») لا يُصَل إليه أبداً
                            //   الآن بعد أن صار isFound المفتاحَ الوحيدَ هنا (الاستجابةُ ناجحةٌ مضمونةً
                            //   من الفحص أعلاه) — أُزيل، فلا التباس فى القراءة لاحقاً.
                            playToneWarning()
                            vibrateWarning()
                            showTopResult(L("⚠️ صنف غير معرّف", "⚠️ Unknown item"), "#B45309")
                            speak("صنف غير معرّف", "Unknown item")
                            txtItemName.text = ""
                            txtItemDetails.text = L("الباركود: ", "Barcode: ") + code
                            txtStatusBadge.text = L("لا يوجد صنف بهذا الباركود في النظام", "No item with this barcode")
                            setBadgeStyle("#78350F", "#F59E0B", "#D97706")
                            scheduleAutoClear()
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
                            scheduleAutoClear()
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
                            scheduleAutoClear()
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
        homeView?.visibility = View.GONE
        mode = "stocktake"
        startCamera()
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
        Toast.makeText(this, L("خرجتَ من وضعِ الجرد — بياناتُك محفوظة. للعودة: الشاشة الرئيسيّة ← الجرد", "Left stocktake — data saved. To return: Home → Stocktake"), Toast.LENGTH_LONG).show()
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
        if (layoutSettings.visibility == View.VISIBLE) { closeSettings(); return }
        if (mode != "home") { showHome(); return }
        super.onBackPressed()
    }
    // بعدَ العودةِ للتطبيق (مثلاً بعد تثبيتِ صوتِ عربيّ) نُعيدُ كشفَ العربيّةِ ونحدّثُ الواجهة.
    override fun onResume() {
        super.onResume()
        if (ttsReady) { detectArabic(); applyLangUi(false) }
        if (mode == "home") fetchStatus(false)   // تحديث الحالة تلقائيّاً عند العودة للتطبيق
    }
    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
    }

    companion object {
        @Volatile private var sTicket: String? = null
        @Volatile private var sTicketExp: Long = 0L
    }
}
