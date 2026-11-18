package com.locatego.driver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * LocateGoAccessibilityService
 * محرك الرصد الخارق والنقر الفوري بدون أي تأخير (Zero-Delay Ultra-Fast Auto-Accept Engine).
 *
 * المواصفات الفنية للسرعة القصوى:
 * 1. NotificationTimeout = 0ms: إلغاء كلي لأي تأخير أو كبح على مستوى نظام تشغيل أندرويد.
 * 2. In-Memory Volatile Settings: قراءة إعدادات المسافة والقبول من الذاكرة الحية (0ns) دون الوصول للقرص.
 * 3. Single-Pass O(N) Scan: فحص الشاشة واستخراج مسافة العميل واصطياد زر القبول في دورة مسح واحدة فائقة السرعة.
 * 4. Zero-Delay Hardware Tap: تنفيذ النقر المزدوج (ACTION_CLICK + Gesture Tap 1ms) في أجزاء من الثانية فور ظهور العنصر.
 * 5. معيار القبول الصارم: مسافة العميل / الوجهة <= الحد الأقصى (مثلاً 2 كم) مع فتح مسافة المطعم دون أي قيد.
 * 6. إلغاء أي تأخيرات أو Timeouts: تحويل جميع عمليات التوثيق، الصوت، والاهتزاز إلى مسارات خلفية غير معطلة (Dispatchers.IO).
 */
class LocateGoAccessibilityService : AccessibilityService() {

    companion object {
        // مصفوفة حزم تطبيقات التوصيل المستهدفة
        val TARGET_PACKAGES = setOf(
            "sa.lg.android.locate",
            "sa.lg.android.locatcc",
            "sa.lg.android.locati",
            "sa.lg.android.locatm",
            "sa.lg.android.locatg",
            "sa.lg.android.locatf",
            "Sa.lg.android.locate",
            "Sa.lg.android.locati",
            "Sa.lg.android.locatf"
        )

        fun isTargetPackage(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            val p = pkg.trim().lowercase()
            // 🛡️ استبعاد قطعي وشامل لتطبيق واتساب وواتساب الأعمال وتطبيقات الدردشة: ممنوع مراقبتها أو السحب أو النقر عليها
            if (p.contains("whatsapp") || p.contains("com.whatsapp") || p == "com.whatsapp.w4b" || p.contains("telegram")) return false
            // استثناء نظام أندرويد ولوحات المفاتيح وتطبيقنا المساعد
            if (p == "com.locatego.driver" || p.contains("android.systemui") || p.contains("inputmethod") || p.contains("launcher")) return false
            return TARGET_PACKAGES.any { p.contains(it.lowercase()) } ||
                    p.contains("locate") ||
                    p.contains("locat") ||
                    p.contains("jahez") ||
                    p.contains("saned") ||
                    p.contains("hunger") ||
                    p.contains("mrsool") ||
                    p.contains("toyou") ||
                    p.contains("ninja") ||
                    p.contains("chefz") ||
                    p.contains("driver") ||
                    p.contains("courier") ||
                    p.contains("delivery") ||
                    p.contains("captain") ||
                    p.contains("rider") ||
                    p.contains("order") ||
                    p.contains("shgardi") ||
                    p.contains("barq") ||
                    p.contains("keta") ||
                    p.contains("careem") ||
                    p.contains("lugmety")
        }

        // قائمة الكلمات والنصوص الواجب تجاهلها
        private val IGNORED_NAV_TEXTS = setOf(
            "الرئيسية", "حسابي", "الملف الشخصي", "الإعدادات", "المحفظة", "الدعم", "مساعدة",
            "المساعدة", "تسجيل الخروج", "تسجيل خروج", "خروج", "الأرشيف", "السجل", "الإشعارات",
            "شروط الاستخدام", "سياسة الخصوصية", "حول التطبيق", "تحديث التطبيق", "تقييم",
            "المظهر", "اللغة", "الوضع الليلي", "حفظ", "إغلاق", "رجوع", "تخطي", "موافق",
            "إلغاء", "تأكيد الهاتف", "رمز التحقق", "التفاصيل", "عرض التفاصيل", "التالي", "السابق",
            "home", "profile", "settings", "wallet", "support", "help", "logout", "history",
            "notifications", "about", "close", "back", "cancel", "menu", "next", "skip"
        )

        // كلمات أشرطة وأزرار القبول المستهدفة بالكامل (أشرطة السحب + الأزرار المباشرة)
        private val ACCEPT_SLIDER_KEYWORDS = listOf(
            "اسحب لقبول الطلب", "اسحب للموافقة", "اسحب لتأكيد الطلب", "اسحب لتأكيد", "اسحب للقبول",
            "اسحب للموافقه", "اسحب للتاكيد", "اسحب", "سحب للقبول", "سحب لقبول الطلب", "سحب للموافقة",
            "slide to accept", "swipe to accept", "slide_to_accept", "swipe_to_accept", "drag to accept"
        )

        // كلمات أزرار القبول المباشرة (Instant Accept Buttons)
        private val ACCEPT_DIRECT_BUTTON_KEYWORDS = listOf(
            "قبول الطلب", "قبول", "موافقة", "الموافقة", "تأكيد الطلب", "تأكيد", "استلام الطلب",
            "استلام", "موافق", "وافق", "إسناد", "accept order", "accept", "confirm order", "confirm", "take order"
        )

        // جميع كلمات القبول المدمجة للرصد السريع
        private val ALL_ACCEPT_KEYWORDS = ACCEPT_SLIDER_KEYWORDS + ACCEPT_DIRECT_BUTTON_KEYWORDS

        // كلمات وأزرار القبول لاستبعادها عند استخراج اسم المتجر
        private val ACCEPT_BUTTON_KEYWORDS = ALL_ACCEPT_KEYWORDS

        // معرّفات عناصر أشرطة وأزرار القبول المحتملة في الواجهات البرمجية
        private val ACCEPT_VIEW_IDS = listOf(
            "slide_to_accept", "swipe_to_accept", "slider", "swipe_btn", "swipe_view", "drag_to_accept",
            "btn_swipe", "swipe_button", "slider_button", "swipe_layout", "order_swipe",
            "btn_accept", "accept", "accept_order", "btnaccept", "btn_confirm",
            "order_accept", "take_order", "button_accept", "action_accept", "btn_take",
            "accept_layout", "button_order_accept", "accept_btn"
        )

        // تخزين الإعدادات في الذاكرة الحية المشتركة (0ns) لتحديثها لحظياً فور الحفظ من LocateGoNativeBridge
        @Volatile var liveIsVipLicensed: Boolean = false
        @Volatile var liveMaxAllowedKm: Double = 3.0
        @Volatile var liveMaxPickupKm: Double = 1.5
        @Volatile var liveMinPayoutSar: Double = 0.0
        @Volatile var liveAutoAccept: Boolean = true
        @Volatile var liveSoundAlerts: Boolean = true
        @Volatile var liveSwipeSpeedMs: Long = 60L
        @Volatile var liveTapIntervalMs: Long = 10L
        @Volatile var liveEnableCustomInterval: Boolean = false
        @Volatile var liveOrderCardLanguage: String = "ar"

        fun syncFromPreferences(context: Context) {
            try {
                val prefs = context.getSharedPreferences("locate_go_prefs", Context.MODE_PRIVATE)
                liveIsVipLicensed = prefs.getBoolean("is_vip_licensed", false)
                liveMaxAllowedKm = prefs.getFloat("max_distance_km", 3.0f).toDouble()
                liveMaxPickupKm = prefs.getFloat("max_pickup_distance_km", 1.5f).toDouble()
                liveMinPayoutSar = prefs.getFloat("min_payout_sar", 0.0f).toDouble()
                liveAutoAccept = prefs.getBoolean("auto_accept", true)
                liveSoundAlerts = prefs.getBoolean("sound_alerts", true)
                liveSwipeSpeedMs = prefs.getInt("swipe_speed_ms", 60).toLong().coerceIn(10L, 500L)
                liveTapIntervalMs = prefs.getInt("tap_interval_ms", 10).toLong()
                liveEnableCustomInterval = prefs.getBoolean("enable_custom_interval", false)
                liveOrderCardLanguage = prefs.getString("order_card_language", "ar") ?: "ar"
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var renderClient: RenderApiClient
    private var sharedPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var isBatchGestureRunning: Boolean = false
    @Volatile private var pendingRescanAfterBatch: Boolean = false
    @Volatile private var pendingRescanPackage: String = ""
    private val packageLearnedRtl = ConcurrentHashMap<String, Boolean>()

    // تعابير نمطية مستهدفة بدقة عالية تدعم صيغتي: "المتجر 2.0 كم" وأيضاً "2.0 كم المتجر"
    private val pickupDistancePattern = Pattern.compile(
        "(?:(?:مسافة\\s*(?:المتجر|المطعم|الاستلام)|المتجر\\s*يبعد|المطعم\\s*يبعد|مسافة\\s*الاستلام|الاستلام|المطعم|المتجر|pickup|store)\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)|(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)\\s*[:]?\\s*(?:المتجر|المطعم|الاستلام|pickup|store))",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val deliveryDistancePattern = Pattern.compile(
        "(?:(?:مسافة\\s*(?:العميل|التوصيل|الوجهة)|العميل\\s*يبعد|مسافة\\s*التوصيل|التوصيل|العميل|الوجهة|delivery|dropoff|customer)\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)|(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)\\s*[:]?\\s*(?:العميل|التوصيل|الوجهة|delivery|dropoff|customer))",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val generalDistancePattern = Pattern.compile(
        "(?:المسافة\\s*الإجمالية|إجمالي\\s*المسافة|المسافة\\s*الكلية|المسافة|يبعد|تبعد|distance|total)?\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val payoutPattern = Pattern.compile(
        "(?:الأجر|المبلغ|السعر|قيمة\\s*التوصيل|الربح|الأرباح|fee|sar|payout)?\\s*[:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:ر\\.س|ريال|sar|SAR|ر\\.\\s*س)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val orderIdPattern = Pattern.compile(
        "(?:رقم\\s*الطلب|الطلب\\s*رقم|طلب\\s*#|Order\\s*#?|ID\\s*[:#]?|#)\\s*([A-Za-z0-9\\-_]{3,15})",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val districtPattern = Pattern.compile(
        "(?:التوصيل\\s*إلى|الوجهة|العميل|حي|district)?\\s*[:]?\\s*(حي\\s+[\\u0600-\\u06FF]+(?:\\s+[\\u0600-\\u06FF]+)?)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private var currentForegroundPackage = ""
    private val processedOrdersCache = ConcurrentHashMap<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        renderClient = RenderApiClient(this)

        // 1. تهيئة خدمة Accessibility بأقصى سرعة ممكنة وبدون أي تأخير (Zero Throttling)
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.notificationTimeout = 0L // إلغاء أي مؤقت تأخير بين إشعارات الأحداث نهائياً
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info

        // 2. تفعيل ذاكرة الإعدادات الفورية
        initLiveSettingsCache()

        Log.i("LocateGoService", "⚡⚡ ULTRA ZERO-DELAY Auto-Accept Engine ACTIVATED (Timeout=0ms • Single-Pass O(N) Scanner • Dual RTL/LTR Swipe & Tap).")
    }

    private fun initLiveSettingsCache() {
        syncFromPreferences(this)
        val prefs = getSharedPreferences("locate_go_prefs", Context.MODE_PRIVATE)

        sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "is_vip_licensed" -> liveIsVipLicensed = sp.getBoolean("is_vip_licensed", false)
                "max_distance_km" -> liveMaxAllowedKm = sp.getFloat("max_distance_km", 3.0f).toDouble()
                "max_pickup_distance_km" -> liveMaxPickupKm = sp.getFloat("max_pickup_distance_km", 1.5f).toDouble()
                "min_payout_sar" -> liveMinPayoutSar = sp.getFloat("min_payout_sar", 0.0f).toDouble()
                "auto_accept" -> liveAutoAccept = sp.getBoolean("auto_accept", true)
                "sound_alerts" -> liveSoundAlerts = sp.getBoolean("sound_alerts", true)
                "swipe_speed_ms" -> liveSwipeSpeedMs = sp.getInt("swipe_speed_ms", 60).toLong().coerceIn(10L, 500L)
                "tap_interval_ms" -> liveTapIntervalMs = sp.getInt("tap_interval_ms", 10).toLong()
                "enable_custom_interval" -> liveEnableCustomInterval = sp.getBoolean("enable_custom_interval", false)
                "order_card_language" -> liveOrderCardLanguage = sp.getString("order_card_language", "ar") ?: "ar"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // فحص صارم وسريع لحالة ترخيص VIP من الذاكرة الحية في 0 نانو ثانية دون إبطاء الواجهة
        if (!liveIsVipLicensed) return

        // فحص سريع: إذا كان القبول التلقائي مغلقاً في الذاكرة الحية نخرج فوراً
        if (!liveAutoAccept) return

        val packageName: String = event.packageName?.toString() ?: ""
        val lowerPkg = packageName.trim().lowercase()

        // 🛡️ حظر قطعي لتطبيق واتساب: ممنوع منعاً باتاً مراقبة شاشة الواتساب أو فحص نصوصها أو السحب أو النقر عليها نهائياً
        if (lowerPkg.contains("whatsapp") || lowerPkg == "com.whatsapp" || lowerPkg == "com.whatsapp.w4b" || lowerPkg.contains("telegram")) {
            return
        }

        if (!isTargetPackage(packageName)) {
            return
        }

        currentForegroundPackage = packageName

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val root = rootInActiveWindow ?: event.source ?: findPackageWindowRoot(packageName) ?: return
        inspectScreenForOrderOffer(root, packageName)
    }

    private fun findPackageWindowRoot(targetPackage: String): AccessibilityNodeInfo? {
        return try {
            val allWindows = windows ?: return null
            for (w in allWindows) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString() ?: ""
                if (pkg.equals(targetPackage, ignoreCase = true) || isTargetPackage(pkg)) {
                    return r
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private data class AcceptCandidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val isSlider: Boolean
    )

    /**
     * نتيجة المسح الفوري الأحادي للشاشة
     */
    private data class FastScanResult(
        val texts: MutableList<String> = mutableListOf(),
        val positionedTexts: MutableList<Pair<Int, String>> = mutableListOf(),
        val acceptCandidates: MutableList<AcceptCandidate> = mutableListOf(),
        var directDeliveryKm: Double? = null,
        var directPickupKm: Double? = null,
        var directPayoutSar: Double? = null
    )

    private data class EvaluatedOrderCard(
        val candidate: AcceptCandidate?,
        val dedupKey: String,
        val isMatching: Boolean,
        val rejectReason: String?,
        val storeName: String,
        val customerDistrict: String?,
        val orderId: String?,
        val payoutSar: Double,
        val evalDeliveryKm: Double?,
        val evalPickupKm: Double?,
        val targetEvaluationDistanceKm: Double
    )

    private fun evaluateTextsAgainstRules(
        texts: List<String>,
        directDeliveryKm: Double?,
        directPickupKm: Double?,
        directPayoutSar: Double?,
        candidate: AcceptCandidate?
    ): EvaluatedOrderCard {
        val joinedContent = texts.joinToString(" ")
        val maxAllowedDelivery = liveMaxAllowedKm
        val maxAllowedPickup = liveMaxPickupKm

        val allDists = extractAllDistancesFromTexts(texts)

        var deliveryDistKm: Double? = directDeliveryKm
        var pickupDistKm: Double? = directPickupKm

        if (allDists.size >= 2) {
            val sortedDists = allDists.sorted()
            if (pickupDistKm == null) {
                pickupDistKm = sortedDists.first()
            }
            if (deliveryDistKm == null) {
                deliveryDistKm = if (sortedDists.size > 2 && sortedDists.last() > (sortedDists[0] + sortedDists[1]) * 0.9) {
                    sortedDists[sortedDists.size - 2]
                } else {
                    sortedDists.last()
                }
            }
        } else if (allDists.size == 1) {
            val singleDist = allDists[0]
            val hasStoreContext = joinedContent.contains("متجر") || joinedContent.contains("مطعم") || joinedContent.contains("موقعك")
            val hasCustomerContext = joinedContent.contains("عميل") || joinedContent.contains("زبون") || joinedContent.contains("توصيل") || joinedContent.contains("وجهة")

            if (hasCustomerContext && !hasStoreContext) {
                deliveryDistKm = singleDist
            } else if (hasStoreContext && !hasCustomerContext) {
                pickupDistKm = singleDist
            } else {
                deliveryDistKm = singleDist
            }
        }

        val targetEvaluationDistanceKm = deliveryDistKm ?: pickupDistKm ?: allDists.firstOrNull() ?: 1.0

        var payoutSar = directPayoutSar ?: 18.0
        if (directPayoutSar == null) {
            val payoutMatcher = payoutPattern.matcher(joinedContent)
            if (payoutMatcher.find()) {
                payoutMatcher.group(1)?.replace(',', '.')?.toDoubleOrNull()?.let { payoutSar = it }
            }
        }

        var extractedOrderId: String? = null
        val idMatcher = orderIdPattern.matcher(joinedContent)
        if (idMatcher.find()) {
            extractedOrderId = idMatcher.group(1)
        }

        val storeName = extractStoreName(texts)
        val customerDistrict = extractCustomerDistrict(joinedContent, texts)
        val yBucket = (candidate?.bounds?.centerY() ?: 0) / 35
        val dedupKey = "${extractedOrderId ?: ""}|y$yBucket|$targetEvaluationDistanceKm|$pickupDistKm|$storeName|${joinedContent.hashCode()}"

        val hasAnyDistance = allDists.isNotEmpty() || deliveryDistKm != null || pickupDistKm != null

        val evalDeliveryKm = deliveryDistKm ?: allDists.maxOrNull()
        val evalPickupKm = pickupDistKm ?: (if (allDists.size >= 2) allDists.minOrNull() else null)

        val isDeliveryWithinLimit = evalDeliveryKm != null && evalDeliveryKm <= maxAllowedDelivery
        val isPickupWithinLimit = evalPickupKm == null || evalPickupKm <= maxAllowedPickup
        val isOrderMatching = hasAnyDistance && isDeliveryWithinLimit && isPickupWithinLimit

        val rejectReason = if (isOrderMatching) null else when {
            !hasAnyDistance -> "لم يتم العثور على مسافة رقمية واضحة في العرض"
            !isDeliveryWithinLimit -> "مسافة العميل ($evalDeliveryKm كم) تتجاوز الحد الأقصى ($maxAllowedDelivery كم)"
            !isPickupWithinLimit -> "مسافة المطعم ($evalPickupKm كم) تتجاوز الحد الأقصى ($maxAllowedPickup كم)"
            else -> "معطل أو خارج النطاق المحدد"
        }

        return EvaluatedOrderCard(
            candidate = candidate,
            dedupKey = dedupKey,
            isMatching = isOrderMatching,
            rejectReason = rejectReason,
            storeName = storeName,
            customerDistrict = customerDistrict,
            orderId = extractedOrderId,
            payoutSar = payoutSar,
            evalDeliveryKm = evalDeliveryKm,
            evalPickupKm = evalPickupKm,
            targetEvaluationDistanceKm = targetEvaluationDistanceKm
        )
    }

    /**
     * عزل حاوية كل بطاقة طلب على حدة عند وجود أكثر من طلب في الشاشة
     */
    private fun findIsolatedOrderCardRoot(
        candidate: AcceptCandidate,
        allCandidates: List<AcceptCandidate>,
        displayHeight: Int
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = candidate.node.parent
        var bestCardRoot: AccessibilityNodeInfo? = null
        val maxCardHeight = (displayHeight * 0.75f).toInt()

        while (current != null) {
            val className = current.className?.toString()?.lowercase() ?: ""
            if (className.contains("recyclerview") ||
                className.contains("listview") ||
                className.contains("scrollview") ||
                className.contains("gridview") ||
                className.contains("viewpager")
            ) {
                break
            }

            val cBounds = Rect()
            current.getBoundsInScreen(cBounds)
            if (cBounds.height() > maxCardHeight) {
                break
            }

            val containsOtherCandidate = allCandidates.any { other ->
                other !== candidate && cBounds.contains(other.bounds.centerX(), other.bounds.centerY())
            }
            if (containsOtherCandidate) {
                break
            }

            if (cBounds.height() >= candidate.bounds.height() + 24) {
                bestCardRoot = current
            }
            current = current.parent
        }
        return bestCardRoot
    }

    /**
     * فحص الشاشة بدقة فائقة: يدعم قبول طلب واحد أو عدة طلبات متزامنة بنفس الشاشة أو متتالية بنفس المسافات
     */
    private fun inspectScreenForOrderOffer(root: AccessibilityNodeInfo, packageName: String) {
        if (isBatchGestureRunning) {
            pendingRescanAfterBatch = true
            pendingRescanPackage = packageName
            return
        }

        val displayHeight = resources.displayMetrics.heightPixels
        val scanResult = FastScanResult()

        // دورة مسح واحدة تجمع النصوص وترصد جميع أزرار/أشرطة القبول في نفس اللحظة
        fastSinglePassTraversal(root, scanResult, displayHeight)

        // في حال كانت نافذة الطلب المنبثقة (Dialog/Overlay) داخل نافذة تطبيق أخرى
        if (scanResult.acceptCandidates.isEmpty()) {
            val altWindowRoot = findPackageWindowRoot(packageName)
            if (altWindowRoot != null && altWindowRoot != root) {
                val altScan = FastScanResult()
                fastSinglePassTraversal(altWindowRoot, altScan, displayHeight)
                if (altScan.acceptCandidates.isNotEmpty()) {
                    scanResult.texts.clear()
                    scanResult.texts.addAll(altScan.texts)
                    scanResult.positionedTexts.clear()
                    scanResult.positionedTexts.addAll(altScan.positionedTexts)
                    scanResult.acceptCandidates.addAll(altScan.acceptCandidates)
                    scanResult.directDeliveryKm = altScan.directDeliveryKm
                    scanResult.directPickupKm = altScan.directPickupKm
                    scanResult.directPayoutSar = altScan.directPayoutSar
                }
            }
        }

        if (scanResult.texts.isEmpty()) return

        val lowerPkg = packageName.lowercase()
        val resolvedAppName = when {
            lowerPkg.contains("locatcc") -> "Locate CC"
            lowerPkg.contains("locati") -> "Locate I"
            lowerPkg.contains("locatm") -> "Locate M"
            lowerPkg.contains("locatg") -> "Locate G"
            lowerPkg.contains("locatf") -> "Locate F"
            lowerPkg.contains("locate") -> "Locate Go"
            lowerPkg.contains("jahez") -> "جاهز"
            lowerPkg.contains("hunger") -> "هنقرستيشن"
            lowerPkg.contains("toyou") -> "تو يو"
            lowerPkg.contains("mrsool") -> "مرسول"
            lowerPkg.contains("ninja") -> "نينجا"
            lowerPkg.contains("chefz") -> "ذا شفز"
            lowerPkg.contains("saned") -> "ساند"
            lowerPkg.contains("shgardi") -> "شقردي"
            lowerPkg.contains("barq") -> "برق"
            else -> "تطبيق المندوب"
        }

        val candidates = scanResult.acceptCandidates.sortedBy { it.bounds.centerY() }
        val evaluatedCards = mutableListOf<EvaluatedOrderCard>()

        if (candidates.size > 1) {
            // حالة وجود أكثر من طلب في نفس الشاشة: تقييم كل بطاقة طلب بشكل مستقل تماماً
            for ((idx, cand) in candidates.withIndex()) {
                val cardRoot = findIsolatedOrderCardRoot(cand, candidates, displayHeight)
                if (cardRoot != null) {
                    val cardScan = FastScanResult()
                    fastSinglePassTraversal(cardRoot, cardScan, displayHeight, collectButtons = false)
                    val cardTexts = if (cardScan.texts.isNotEmpty()) cardScan.texts else scanResult.texts
                    evaluatedCards.add(
                        evaluateTextsAgainstRules(
                            texts = cardTexts,
                            directDeliveryKm = cardScan.directDeliveryKm,
                            directPickupKm = cardScan.directPickupKm,
                            directPayoutSar = cardScan.directPayoutSar,
                            candidate = cand
                        )
                    )
                } else {
                    // عزل مكاني عمودي (Y-Band) في حال كانت الواجهة مسطحة (Compose/Flutter) بدون حاوية أب منفصلة
                    val prevMidY = if (idx > 0) (candidates[idx - 1].bounds.centerY() + cand.bounds.centerY()) / 2 else 0
                    val nextMidY = if (idx < candidates.lastIndex) (cand.bounds.centerY() + candidates[idx + 1].bounds.centerY()) / 2 else displayHeight
                    val slicedTexts = scanResult.positionedTexts
                        .filter { (y, _) -> y in prevMidY..nextMidY }
                        .map { it.second }
                    val cardTexts = if (slicedTexts.isNotEmpty()) slicedTexts else scanResult.texts
                    evaluatedCards.add(
                        evaluateTextsAgainstRules(
                            texts = cardTexts,
                            directDeliveryKm = null,
                            directPickupKm = null,
                            directPayoutSar = null,
                            candidate = cand
                        )
                    )
                }
            }
        } else {
            // طلب واحد في الشاشة
            evaluatedCards.add(
                evaluateTextsAgainstRules(
                    texts = scanResult.texts,
                    directDeliveryKm = scanResult.directDeliveryKm,
                    directPickupKm = scanResult.directPickupKm,
                    directPayoutSar = scanResult.directPayoutSar,
                    candidate = candidates.firstOrNull()
                )
            )
        }

        val now = System.currentTimeMillis()
        val matchingBatch = evaluatedCards.filter { card ->
            card.isMatching && processedOrdersCache[card.dedupKey]?.let { now - it < 55 } != true
        }

        val skippedList = evaluatedCards.filter { card ->
            !card.isMatching && processedOrdersCache[card.dedupKey]?.let { now - it < 120 } != true
        }

        for (skipped in skippedList) {
            processedOrdersCache[skipped.dedupKey] = now
            Log.w("LocateGoService", "🚫 ORDER SKIPPED: ${skipped.rejectReason}")
        }
        if (skippedList.isNotEmpty() && matchingBatch.isEmpty()) {
            notifyDriverRejectedAsync()
            val firstSkip = skippedList.first()
            serviceScope.launch {
                try {
                    renderClient.evaluateOrder(
                        appName = resolvedAppName,
                        storeName = firstSkip.storeName,
                        distanceKm = firstSkip.evalDeliveryKm ?: firstSkip.targetEvaluationDistanceKm,
                        payoutSar = firstSkip.payoutSar,
                        orderId = firstSkip.orderId,
                        customerDistrict = firstSkip.customerDistrict,
                        driverLat = LocationTrackingService.currentLatitude,
                        driverLng = LocationTrackingService.currentLongitude,
                        pickupDistanceKm = firstSkip.evalPickupKm,
                        deliveryDistanceKm = firstSkip.evalDeliveryKm
                    )
                } catch (_: Exception) {}
            }
        }

        if (matchingBatch.isEmpty()) return

        // حجز الدفعة وتنفيذ قبول جميع الطلبات المطابقة بالتتابع بدون إلغاء أي إيماءة
        isBatchGestureRunning = true
        for (card in matchingBatch) {
            processedOrdersCache[card.dedupKey] = now
        }

        serviceScope.launch {
            try {
                for ((index, card) in matchingBatch.withIndex()) {
                    if (!liveIsVipLicensed || !liveAutoAccept) break

                    val cand = card.candidate
                    val accepted = if (cand != null) {
                        performAcceptSuspended(
                            node = cand.node,
                            isSlider = cand.isSlider,
                            allowOppositeFallback = true,
                            isFollowUpInBatch = (index > 0)
                        )
                    } else {
                        executeFallbackAccept(root)
                    }

                    Log.i(
                        "LocateGoService",
                        "⚡⚡ AUTO-ACCEPT [${index + 1}/${matchingBatch.size}] [Triggered=$accepted] [Speed=${liveSwipeSpeedMs}ms] App='$resolvedAppName' Store='${card.storeName}' | Pickup: ${card.evalPickupKm ?: "N/A"} km | Delivery: ${card.evalDeliveryKm ?: card.targetEvaluationDistanceKm} km"
                    )

                    notifyDriverAcceptedAsync()

                    launch {
                        try {
                            renderClient.evaluateOrder(
                                appName = resolvedAppName,
                                storeName = card.storeName,
                                distanceKm = card.evalDeliveryKm ?: card.targetEvaluationDistanceKm,
                                payoutSar = card.payoutSar,
                                orderId = card.orderId,
                                customerDistrict = card.customerDistrict,
                                driverLat = LocationTrackingService.currentLatitude,
                                driverLng = LocationTrackingService.currentLongitude,
                                pickupDistanceKm = card.evalPickupKm,
                                deliveryDistanceKm = card.evalDeliveryKm
                            )
                        } catch (_: Exception) {}
                    }

                    if (index < matchingBatch.lastIndex) {
                        delay(12L)
                    }
                }
            } finally {
                isBatchGestureRunning = false
            }

            // فحص فوري بعد انتهاء الدفعة إذا وصلت طلبات أثناء السحب أو وُجدت طلبات متعددة
            if (pendingRescanAfterBatch || matchingBatch.size > 1) {
                val rescanPkg = pendingRescanPackage.ifBlank { packageName }
                pendingRescanAfterBatch = false
                delay(15L)
                try {
                    val freshRoot = rootInActiveWindow ?: findPackageWindowRoot(rescanPkg)
                    if (freshRoot != null && liveIsVipLicensed && liveAutoAccept) {
                        inspectScreenForOrderOffer(freshRoot, rescanPkg)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * مسح شجرة العناصر في دورة أحادية فائقة الخفة:
     * - رصد واصطياد جميع أشرطة القبول بالسحب أو أزرار القبول المباشرة فوراً (مع حظر أزرار الرفض Reject والإلغاء وأيقونات GPS).
     * - استخراج نصوص المسافات والأسعار والمتاجر.
     */
    private fun fastSinglePassTraversal(
        node: AccessibilityNodeInfo?,
        result: FastScanResult,
        displayHeight: Int,
        collectButtons: Boolean = true
    ) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val rawContent = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> null
        }

        // 1. فحص ورصد جميع أزرار القبول أو أشرطة السحب في الشاشة (وليس الأول فقط!)
        if (collectButtons) {
            val isAcceptId = ACCEPT_VIEW_IDS.any { viewId.endsWith(it) || viewId.contains(it) }
            val isAcceptKeyword = rawContent != null && ALL_ACCEPT_KEYWORDS.any { rawContent.contains(it, ignoreCase = true) }

            if (isAcceptId || isAcceptKeyword) {
                val lowerStr = rawContent?.lowercase() ?: ""
                val isBlacklisted = lowerStr.contains("reject") ||
                        lowerStr.contains("رفض") ||
                        lowerStr.contains("إلغاء") ||
                        lowerStr.contains("تجاهل") ||
                        lowerStr.contains("خروج") ||
                        lowerStr.contains("رجوع") ||
                        lowerStr.contains("cancel") ||
                        lowerStr.contains("decline") ||
                        lowerStr.contains("dismiss") ||
                        lowerStr.contains("موقعك") ||
                        viewId.contains("reject") ||
                        viewId.contains("cancel") ||
                        viewId.contains("decline") ||
                        viewId.contains("dismiss") ||
                        viewId.contains("location") ||
                        viewId.contains("gps")

                if (!isBlacklisted) {
                    val nodeBounds = Rect()
                    node.getBoundsInScreen(nodeBounds)
                    if (!nodeBounds.isEmpty && nodeBounds.width() > 24 && nodeBounds.height() > 16) {
                        val isOverlappingExisting = result.acceptCandidates.any { existing ->
                            kotlin.math.abs(existing.bounds.centerY() - nodeBounds.centerY()) < 35 &&
                                kotlin.math.abs(existing.bounds.centerX() - nodeBounds.centerX()) < 100
                        }
                        if (!isOverlappingExisting) {
                            val hasExplicitSliderSignal =
                                (rawContent != null && ACCEPT_SLIDER_KEYWORDS.any { rawContent.contains(it, ignoreCase = true) }) ||
                                        viewId.contains("slide") ||
                                        viewId.contains("swipe") ||
                                        viewId.contains("drag") ||
                                        viewId.contains("seek")
                            val hasExplicitButtonSignal =
                                rawContent != null && ACCEPT_DIRECT_BUTTON_KEYWORDS.any { rawContent.contains(it, ignoreCase = true) }
                            val isSliderNode = hasExplicitSliderSignal ||
                                    (!hasExplicitButtonSignal && !node.isClickable && nodeBounds.width() > nodeBounds.height() * 3.4f)
                            result.acceptCandidates.add(AcceptCandidate(node, nodeBounds, isSliderNode))
                        }
                    }
                }
            }
        }

        // 2. إضافة النص المؤهل وفحص المسافات المباشرة (يدعم النصوص المدمجة الطويلة في Compose/Flutter)
        if (rawContent != null && rawContent.length in 2..1500) {
            val lowerStr = rawContent.lowercase()
            val isIgnored = IGNORED_NAV_TEXTS.any { lowerStr == it || lowerStr.startsWith("$it ") }
            if (!isIgnored) {
                val normalizedContent = normalizeArabicDigits(rawContent)
                result.texts.add(normalizedContent)
                if (collectButtons) {
                    val tb = Rect()
                    node.getBoundsInScreen(tb)
                    if (!tb.isEmpty) {
                        result.positionedTexts.add(tb.centerY() to normalizedContent)
                    }
                }

                // فحص سريع لنصوص المسافات أثناء المرور لتسريع التحليل
                if (result.directDeliveryKm == null && (lowerStr.contains("عميل") || lowerStr.contains("توصيل") || lowerStr.contains("وجهة") || lowerStr.contains("delivery") || lowerStr.contains("dropoff"))) {
                    val m = deliveryDistancePattern.matcher(normalizedContent)
                    if (m.find()) {
                        val valStr = m.group(1) ?: m.group(2)
                        result.directDeliveryKm = valStr?.replace(',', '.')?.toDoubleOrNull()
                    }
                }

                if (result.directPickupKm == null && (lowerStr.contains("مطعم") || lowerStr.contains("متجر") || lowerStr.contains("استلام") || lowerStr.contains("pickup") || lowerStr.contains("store"))) {
                    val m = pickupDistancePattern.matcher(normalizedContent)
                    if (m.find()) {
                        val valStr = m.group(1) ?: m.group(2)
                        result.directPickupKm = valStr?.replace(',', '.')?.toDoubleOrNull()
                    }
                }

                if (result.directPayoutSar == null && (lowerStr.contains("ريال") || lowerStr.contains("ر.س") || lowerStr.contains("sar"))) {
                    val m = payoutPattern.matcher(normalizedContent)
                    if (m.find()) {
                        result.directPayoutSar = m.group(1)?.replace(',', '.')?.toDoubleOrNull()
                    }
                }
            }
        }

        // النزول للأبناء
        val childCount = node.childCount
        for (i in 0 until childCount) {
            fastSinglePassTraversal(node.getChild(i), result, displayHeight, collectButtons)
        }
    }

    /**
     * تحديد موضع مقبض شريط السحب (هل يبدأ من اليمين أم من اليسار؟) لضمان اتجاه سحب حقيقي 100%
     */
    private fun detectThumbStartsOnRight(container: AccessibilityNodeInfo, containerBounds: Rect): Boolean? {
        val centerX = containerBounds.centerX()
        val maxThumbSize = (containerBounds.height() * 2.1f).toInt().coerceAtLeast(80)
        var foundRightThumb = false
        var foundLeftThumb = false

        fun inspectChildren(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 4) return
            val count = n.childCount
            for (i in 0 until count) {
                val child = n.getChild(i) ?: continue
                val cls = child.className?.toString()?.lowercase() ?: ""
                val hasText = !child.text.isNullOrBlank()
                val cb = Rect()
                child.getBoundsInScreen(cb)
                val isIconOrThumb = !hasText && (
                    cls.contains("image") ||
                    cls.contains("thumb") ||
                    cls.contains("icon") ||
                    cls.contains("circle") ||
                    child.childCount == 0
                )
                if (!cb.isEmpty && isIconOrThumb && cb.width() in 18..maxThumbSize) {
                    if (cb.centerX() > centerX + 35) foundRightThumb = true
                    if (cb.centerX() < centerX - 35) foundLeftThumb = true
                }
                inspectChildren(child, depth + 1)
            }
        }
        inspectChildren(container, 0)
        return when {
            foundRightThumb && !foundLeftThumb -> true
            foundLeftThumb && !foundRightThumb -> false
            else -> null
        }
    }

    private suspend fun dispatchGestureAwaitingCompletion(gesture: GestureDescription, maxWaitMs: Long): Boolean {
        return withTimeoutOrNull(maxWaitMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }, null)
                if (!dispatched && cont.isActive) {
                    cont.resumeWith(Result.success(false))
                }
            }
        } ?: false
    }

    /**
     * تنفيذ القبول الحقيقي بالمدة الزمنية الفعلية المختارة (10ms - 200ms) بدون أي تأخير وهمي:
     * - إذا كان العنصر شريط سحب (Slider): يبدأ السحب الفوري في 0ms بالضبط بزمن durationMs الحقيقي دون نقرة وسطية مسبقة!
     * - إذا كان العنصر زر قبول مباشر (Direct Button): ينفذ نقرة مباشرة فوراً في 0ms.
     */
    private suspend fun performAcceptSuspended(
        node: AccessibilityNodeInfo?,
        isSlider: Boolean,
        allowOppositeFallback: Boolean = true,
        isFollowUpInBatch: Boolean = false
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (node == null) return false

        val currentPkg = currentForegroundPackage.trim().lowercase()
        if (currentPkg.contains("whatsapp") || currentPkg == "com.whatsapp" || currentPkg == "com.whatsapp.w4b" || currentPkg.contains("telegram")) {
            Log.w("LocateGoService", "🚫 Blocked gesture: Target is WhatsApp/Chat - Gestures strictly forbidden!")
            return false
        }

        val refreshed = try {
            node.refresh()
        } catch (_: Exception) {
            true
        }
        if (isFollowUpInBatch && !refreshed) {
            return false
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val initialHeight = bounds.height().coerceAtLeast(32)
        val maxControlHeight = if (isSlider) {
            (screenHeight * 0.115f).toInt().coerceIn(175, 250)
        } else {
            (initialHeight * 2.4f).toInt().coerceIn(140, 260)
        }

        var targetContainer: AccessibilityNodeInfo = node
        var pNode: AccessibilityNodeInfo? = node.parent
        var climbDepth = 0
        while (pNode != null && climbDepth < 5) {
            val pBounds = Rect()
            pNode.getBoundsInScreen(pBounds)
            val verticalShift = kotlin.math.abs(pBounds.centerY() - bounds.centerY())
            if (pBounds.width() >= bounds.width() &&
                pBounds.height() in initialHeight..maxControlHeight &&
                verticalShift < 80 &&
                pBounds.height() < (screenHeight * 0.14f)
            ) {
                bounds.set(pBounds)
                targetContainer = pNode
                pNode = pNode.parent
                climbDepth++
            } else {
                break
            }
        }

        if (bounds.isEmpty || bounds.centerY() < (screenHeight * 0.08f)) {
            Log.w("LocateGoService", "⚠️ Target bounds invalid, skipping to protect UI.")
            return false
        }

        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val durationMs = liveSwipeSpeedMs.coerceIn(10L, 200L)

        // إذا كان زر قبول مباشر وليس شريط سحب: نقر فوري مباشر على الزر أو حاويته القابلة للنقر
        if (!isSlider) {
            try {
                var clickTarget: AccessibilityNodeInfo? = node
                repeat(4) {
                    clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickTarget = clickTarget?.parent
                }
            } catch (_: Exception) {}

            val tapPath = Path().apply {
                moveTo(cx, cy)
                lineTo(cx, cy)
            }
            val tapGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, durationMs.coerceIn(10L, 40L)))
                .build()
            val ok = dispatchGestureAwaitingCompletion(tapGesture, durationMs + 180L)

            if (liveEnableCustomInterval) {
                val tapDelay = liveTapIntervalMs.coerceIn(5L, 300L)
                delay(tapDelay)
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {}
            }
            return ok
        }

        // شريط السحب (Slider): سحب كامل 100% من مركز المقبض وحتى ما بعد نهاية الشريط بالكامل مهما كانت السرعة
        val trackLeft = if (bounds.width() < screenWidth * 0.52f) {
            (screenWidth * 0.055f)
        } else {
            bounds.left.toFloat()
        }
        val trackRight = if (bounds.width() < screenWidth * 0.52f) {
            (screenWidth * 0.945f)
        } else {
            bounds.right.toFloat()
        }

        val effectiveHeight = bounds.height().toFloat().coerceIn(80f, 220f)
        val thumbCenterInset = (effectiveHeight * 0.42f).coerceIn(22f, 75f)

        // البدء من داخل الدائرة/المقبض والسحب إلى ما بعد نهاية المسار بـ 22px لضمان الوصول 100% للنهاية
        val ltrStartX = (trackLeft + thumbCenterInset).coerceIn(10f, screenWidth - 10f)
        val ltrEndX = (trackRight + 22f).coerceAtMost(screenWidth - 4f)
        val rtlStartX = (trackRight - thumbCenterInset).coerceIn(10f, screenWidth - 10f)
        val rtlEndX = (trackLeft - 22f).coerceAtLeast(4f)

        if (trackRight <= trackLeft + 60f) {
            val tapPath = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
            val tapGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, durationMs.coerceIn(10L, 40L)))
                .build()
            return dispatchGestureAwaitingCompletion(tapGesture, durationMs + 180L)
        }

        val thumbOnRight = detectThumbStartsOnRight(targetContainer, bounds)
        val preferRtl = thumbOnRight ?: packageLearnedRtl[currentForegroundPackage] ?: (liveOrderCardLanguage == "ar")

        val primaryStartX = if (preferRtl) rtlStartX else ltrStartX
        val primaryEndX = if (preferRtl) rtlEndX else ltrEndX

        fun isSliderStillOnSameRow(): Boolean {
            return try {
                val currentRoot = rootInActiveWindow ?: findPackageWindowRoot(currentForegroundPackage) ?: return false
                ACCEPT_SLIDER_KEYWORDS.any { kw ->
                    currentRoot.findAccessibilityNodeInfosByText(kw).any { found ->
                        val fb = Rect()
                        found.getBoundsInScreen(fb)
                        !fb.isEmpty && kotlin.math.abs(fb.centerY() - cy) < 60f
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * تنفيذ سحب متصل بمرحلتين (Two-Stage Latched ContinueStroke):
         * المرحلة 1: السحب بالسرعة المختارة (durationMs) مع إبقاء الإصبع مضغوطاً (willContinue = true)
         * المرحلة 2: تثبيت لحظي عند النهاية 100% لإجبار النظام على إرسال ACTION_MOVE عند النهاية قبل رفع الإصبع ACTION_UP
         * النتيجة: لا يتوقف السحب في المنتصف أبداً حتى على سرعات 10ms-30ms وشاشات 60Hz!
         */
        suspend fun dispatchLatchedFullEndSwipe(sx: Float, ex: Float, durMs: Long): Boolean {
            val mid1 = sx + (ex - sx) * 0.35f
            val mid2 = sx + (ex - sx) * 0.72f
            val dragPath = Path().apply {
                moveTo(sx, cy)
                lineTo(mid1, cy)
                lineTo(mid2, cy)
                lineTo(ex, cy)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val endHoldMs = if (durMs < 50L) 24L else 14L
                val tinyBackX = if (ex > sx) (ex - 2f).coerceAtLeast(4f) else (ex + 2f).coerceAtMost(screenWidth - 4f)
                val endLatchPath = Path().apply {
                    moveTo(ex, cy)
                    lineTo(tinyBackX, cy)
                    lineTo(ex, cy)
                }
                val stroke1 = GestureDescription.StrokeDescription(dragPath, 0L, durMs, true)
                val gesture1 = GestureDescription.Builder().addStroke(stroke1).build()

                return withTimeoutOrNull(durMs + endHoldMs + 250L) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        val d1 = dispatchGesture(gesture1, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                val stroke2 = stroke1.continueStroke(endLatchPath, 0L, endHoldMs, false)
                                val gesture2 = GestureDescription.Builder().addStroke(stroke2).build()
                                val d2 = dispatchGesture(gesture2, object : GestureResultCallback() {
                                    override fun onCompleted(gd2: GestureDescription?) {
                                        if (cont.isActive) cont.resumeWith(Result.success(true))
                                    }

                                    override fun onCancelled(gd2: GestureDescription?) {
                                        if (cont.isActive) cont.resumeWith(Result.success(false))
                                    }
                                }, null)
                                if (!d2 && cont.isActive) {
                                    cont.resumeWith(Result.success(false))
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resumeWith(Result.success(false))
                            }
                        }, null)
                        if (!d1 && cont.isActive) {
                            cont.resumeWith(Result.success(false))
                        }
                    }
                } ?: false
            }

            val fallbackGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(dragPath, 0L, durMs))
                .build()
            return dispatchGestureAwaitingCompletion(fallbackGesture, durMs + 220L)
        }

        Log.i("LocateGoService", "⚡ Latched Full-End Swipe: duration=${durationMs}ms | RTL=$preferRtl (detected=$thumbOnRight) | X=$primaryStartX->$primaryEndX | Y=$cy")
        val completed = dispatchLatchedFullEndSwipe(primaryStartX, primaryEndX, durationMs)

        delay(14L)
        val stillPresentAfterPrimary = isSliderStillOnSameRow()
        if (!stillPresentAfterPrimary && currentForegroundPackage.isNotBlank()) {
            packageLearnedRtl[currentForegroundPackage] = preferRtl
        }

        if (allowOppositeFallback && stillPresentAfterPrimary) {
            // إذا كان الاتجاه مختلفاً ولم يتغير الشريط، نجرب الاتجاه العكسي فوراً بنفس الآلية المضمونة للنهاية
            val altStartX = if (preferRtl) ltrStartX else rtlStartX
            val altEndX = if (preferRtl) ltrEndX else rtlEndX
            val altDuration = durationMs.coerceAtLeast(if (durationMs < 40L) 40L else durationMs)
            Log.i("LocateGoService", "⚡ Opposite latched swipe fallback: duration=${altDuration}ms | X=$altStartX->$altEndX | Y=$cy")
            dispatchLatchedFullEndSwipe(altStartX, altEndX, altDuration)

            delay(14L)
            if (!isSliderStillOnSameRow() && currentForegroundPackage.isNotBlank()) {
                packageLearnedRtl[currentForegroundPackage] = !preferRtl
            }
        }

        return completed
    }

    private suspend fun executeFallbackAccept(root: AccessibilityNodeInfo): Boolean {
        val currentRoot = rootInActiveWindow ?: root

        for (keyword in ALL_ACCEPT_KEYWORDS) {
            val nodes = currentRoot.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) {
                for (node in nodes) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 60) {
                        val isSlider = ACCEPT_SLIDER_KEYWORDS.any { keyword.contains(it, ignoreCase = true) }
                        if (performAcceptSuspended(node, isSlider)) return true
                    }
                }
            }
        }

        for (viewId in ACCEPT_VIEW_IDS) {
            val fullId = "${currentForegroundPackage}:id/$viewId"
            val nodes = currentRoot.findAccessibilityNodeInfosByViewId(fullId)
            if (nodes.isNotEmpty()) {
                for (node in nodes) {
                    val isSlider = viewId.contains("slide") || viewId.contains("swipe")
                    if (performAcceptSuspended(node, isSlider)) return true
                }
            }
        }

        return false
    }

    private fun normalizeArabicDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append(('0' + (ch - '\u0660')))
                in '\u06F0'..'\u06F9' -> sb.append(('0' + (ch - '\u06F0')))
                '\u066B', '\u066C', '،' -> sb.append('.')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun extractAllDistancesFromTexts(texts: List<String>): List<Double> {
        val result = mutableListOf<Double>()
        val kmPattern = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:كم|كيلو|km|k\\.m)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val meterPattern = Pattern.compile("(?<![:\\d.])(\\d{2,4})\\s*(?:متر|امتار|أمتار|meters?|meter|m|م)(?=$|[\\s\\)\\]،,.])", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        for (item in texts) {
            val normalizedText = normalizeArabicDigits(item)
            val m = kmPattern.matcher(normalizedText)
            while (m.find()) {
                val raw = m.group(1) ?: continue
                raw.replace(',', '.').toDoubleOrNull()?.let {
                    if (it in 0.0..150.0) result.add(it)
                }
            }
            val mm = meterPattern.matcher(normalizedText)
            while (mm.find()) {
                val raw = mm.group(1) ?: continue
                raw.toDoubleOrNull()?.let { meters ->
                    if (meters in 50.0..999.0) {
                        result.add(meters / 1000.0)
                    }
                }
            }
        }
        return result
    }

    private fun extractStoreName(texts: List<String>): String {
        for (item in texts) {
            val trimmed = item.trim()
            if (trimmed.length in 3..35 &&
                !generalDistancePattern.matcher(trimmed).find() &&
                !payoutPattern.matcher(trimmed).find() &&
                !orderIdPattern.matcher(trimmed).find() &&
                !ACCEPT_BUTTON_KEYWORDS.any { trimmed.contains(it, ignoreCase = true) } &&
                !trimmed.contains("توصيل", true) &&
                !trimmed.contains("طلب", true) &&
                !trimmed.contains("رفض", true) &&
                !trimmed.contains("تجاهل", true) &&
                !trimmed.contains("استلام", true)
            ) {
                return trimmed
            }
        }
        return "متجر العرض"
    }

    private fun extractCustomerDistrict(fullContent: String, texts: List<String>): String? {
        val matcher = districtPattern.matcher(fullContent)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }

        return texts.firstOrNull {
            it.contains("حي ", true) ||
            it.contains("شارع ", true) ||
            it.contains("طريق ", true) ||
            it.contains("مجمع ", true)
        }
    }

    private fun notifyDriverAcceptedAsync() {
        if (!liveSoundAlerts) return
        serviceScope.launch {
            var toneGen: ToneGenerator? = null
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 220)
                delay(280)
            } catch (_: Exception) {
            } finally {
                try {
                    toneGen?.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun notifyDriverRejectedAsync() {
        if (!liveSoundAlerts) return
        serviceScope.launch {
            var toneGen: ToneGenerator? = null
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
                toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                delay(260)
            } catch (_: Exception) {
            } finally {
                try {
                    toneGen?.release()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        sharedPrefsListener?.let {
            getSharedPreferences("locate_go_prefs", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // لا توجد مؤقتات أو مهام معلقة
    }
}
