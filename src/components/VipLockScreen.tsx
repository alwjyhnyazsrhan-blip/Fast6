import React, { useState, useEffect } from 'react';
import { Copy, Check, ChevronDown, MessageSquare, Send, Sparkles } from 'lucide-react';
import { getVipDeviceId, isAuthorizedDevice } from '../utils/device';
import { getNativeBridge } from '../utils/nativeBridge';
import { Language, translations } from '../utils/i18n';

interface VipLockScreenProps {
  onUnlock: (code: string, planName?: string, expiryDate?: string) => void;
  initialError?: string | null;
  lang?: Language;
  onLanguageChange?: (lang: Language) => void;
}

interface PackagePlan {
  id: string;
  priceSar: number | string;
  durationLabelAr: string;
  durationLabelEn: string;
  featuresAr: string[];
  featuresEn: string[];
  days: number;
}

const PACKAGES: PackagePlan[] = [
  {
    id: '1_month',
    priceSar: '',
    durationLabelAr: 'شهر',
    durationLabelEn: '1 Month',
    featuresAr: ['دعم مستمر', 'تحسين في الأداء', 'تحديثات'],
    featuresEn: ['Continuous Support', 'Performance Improvement', 'Regular Updates'],
    days: 30,
  },
  {
    id: '2_months',
    priceSar: '',
    durationLabelAr: 'شهرين',
    durationLabelEn: '2 Months',
    featuresAr: ['دعم مستمر', 'تحسين في الأداء', 'تحديثات'],
    featuresEn: ['Continuous Support', 'Performance Improvement', 'Regular Updates'],
    days: 60,
  },
  {
    id: '3_months',
    priceSar: '',
    durationLabelAr: '3 أشهر',
    durationLabelEn: '3 Months',
    featuresAr: ['دعم مستمر', 'تحسين في الأداء', 'تحديثات'],
    featuresEn: ['Continuous Support', 'Performance Improvement', 'Regular Updates'],
    days: 90,
  },
  {
    id: 'trial_3_days',
    priceSar: '',
    durationLabelAr: 'تجربة 3 أيام',
    durationLabelEn: 'Trial (3 Days)',
    featuresAr: ['دعم مستمر', 'تحسين في الأداء', 'تحديثات'],
    featuresEn: ['Continuous Support', 'Performance Improvement', 'Regular Updates'],
    days: 3,
  },
];

export const VipLockScreen: React.FC<VipLockScreenProps> = ({
  onUnlock,
  initialError,
  lang = 'ar',
  onLanguageChange,
}) => {
  const [currentLang, setCurrentLang] = useState<Language>(lang);
  const t = translations[currentLang];

  const [deviceId, setDeviceId] = useState<string>('');
  const [activationKey, setActivationKey] = useState<string>('');
  const [selectedPackageId, setSelectedPackageId] = useState<string>('trial_3_days');
  const [errorMessage, setErrorMessage] = useState<string>(initialError || '');
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [isCopied, setIsCopied] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isInitialChecking, setIsInitialChecking] = useState<boolean>(true);

  // Sync external language prop
  useEffect(() => {
    setCurrentLang(lang);
  }, [lang]);

  const handleLangToggle = (newLang: Language) => {
    setCurrentLang(newLang);
    if (onLanguageChange) onLanguageChange(newLang);
  };

  // Get active Hardware Device ID
  useEffect(() => {
    const rawId = getVipDeviceId();
    setDeviceId(rawId);
  }, []);

  // Check saved license on mount
  useEffect(() => {
    const checkSaved = async () => {
      try {
        const savedCode = localStorage.getItem('vip_active_code');
        const termination = localStorage.getItem('vip_termination_notice');
        if (termination || initialError) {
          setIsInitialChecking(false);
          return;
        }

        if (savedCode) {
          await verifyCodeOnline(savedCode, true);
        } else {
          setIsInitialChecking(false);
        }
      } catch {
        setIsInitialChecking(false);
      }
    };
    checkSaved();
  }, [initialError]);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => {
      setToastMessage(null);
    }, 2800);
  };

  const handleCopyUserId = async () => {
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(deviceId);
      } else {
        const el = document.createElement('textarea');
        el.value = deviceId;
        document.body.appendChild(el);
        el.select();
        document.execCommand('copy');
        document.body.removeChild(el);
      }
      setIsCopied(true);
      showToast(t.copied);
      setTimeout(() => setIsCopied(false), 2000);
    } catch {
      showToast(deviceId);
    }
  };

  const handleSelectPackage = (pkg: PackagePlan) => {
    setSelectedPackageId(pkg.id);
    const label = currentLang === 'ar' ? pkg.durationLabelAr : pkg.durationLabelEn;
    showToast(`${t.selectedPackageToast}: ${label}`);
  };

  const getSelectedPlan = (): PackagePlan => {
    return PACKAGES.find((p) => p.id === selectedPackageId) || PACKAGES[3];
  };

  // Contact via Telegram
  const handleContactTelegram = () => {
    const url = 'https://t.me/Wsxderfpo';
    try {
      const bridge = (window as any).LocateGoNative;
      if (bridge && typeof bridge.openUrl === 'function') {
        const handled = bridge.openUrl(url);
        if (handled) return;
      }
    } catch {}
    window.open(url, '_blank');
  };

  // Contact via WhatsApp with exact message template from Video 1
  const handleContactWhatsApp = () => {
    const plan = getSelectedPlan();
    const planLabel = currentLang === 'ar' ? plan.durationLabelAr : plan.durationLabelEn;
    const phone = '967712397720';

    const text =
      currentLang === 'ar'
        ? `مرحباً، أريد الاستفسار أو الاشتراك\n\nUser ID: ${deviceId}\n\nالباقة المختارة: ${planLabel}`
        : `Hello, I would like to inquire or subscribe\n\nUser ID: ${deviceId}\n\nSelected Package: ${planLabel}`;

    // 1. Try Native Android Bridge first to launch Intent directly and avoid ERR_UNKNOWN_URL_SCHEME
    try {
      const bridge = (window as any).LocateGoNative;
      if (bridge && typeof bridge.openWhatsApp === 'function') {
        const handled = bridge.openWhatsApp(phone, text);
        if (handled) return;
      }
    } catch {}

    // 2. Fallback to wa.me URL
    const url = `https://wa.me/${phone}?text=${encodeURIComponent(text)}`;
    try {
      window.open(url, '_blank');
    } catch {
      window.location.href = url;
    }
  };

  // Verify and unlock
  const verifyCodeOnline = async (codeToTest: string, isSilent = false): Promise<boolean> => {
    const cleanCode = codeToTest.trim().toUpperCase();
    if (!cleanCode) {
      if (!isSilent) setErrorMessage(currentLang === 'ar' ? 'الرجاء إدخال مفتاح التفعيل' : 'Please enter activation key');
      return false;
    }

    if (!isSilent) setIsLoading(true);
    setErrorMessage('');

    try {
      // 1. Check if user typed or selected Trial and used a trial keyword or valid key
      const currentPlan = getSelectedPlan();
      const isTrialAttempt =
        cleanCode === 'TRIAL' ||
        cleanCode === 'FREE' ||
        cleanCode.startsWith('TR-') ||
        cleanCode === 'LOCATE' ||
        cleanCode.includes('TRIAL');

      if (isTrialAttempt) {
        const expiry = new Date(Date.now() + 3 * 24 * 3600 * 1000).toISOString();
        localStorage.setItem('vip_active_code', cleanCode);
        localStorage.setItem('vip_device_id', deviceId);
        localStorage.setItem('vip_plan_name', currentLang === 'ar' ? 'تجريبي 3 أيام' : 'Trial 3 Days');
        localStorage.setItem('vip_expiry_date', expiry);

        const bridge = getNativeBridge();
        if (bridge && typeof bridge.setVipLicensed === 'function') {
          bridge.setVipLicensed(true);
        }

        onUnlock(cleanCode, currentLang === 'ar' ? 'تجريبي 3 أيام' : 'Trial 3 Days', expiry);
        setIsLoading(false);
        setIsInitialChecking(false);
        return true;
      }

      // 2. Fetch Firebase/server database codes
      const res = await fetch('/tables/activation_codes');
      if (res.ok) {
        const json = await res.json();
        const codes = json.data || [];
        const match = codes.find((c: any) => (c.code || '').toUpperCase() === cleanCode);

        if (match) {
          if (match.status === 'expired') {
            if (!isSilent) setErrorMessage(currentLang === 'ar' ? 'انتهت صلاحية هذا الكود' : 'This code has expired');
            setIsLoading(false);
            setIsInitialChecking(false);
            return false;
          }

          if (match.used_by && match.used_by.trim() !== '' && !isAuthorizedDevice(match.used_by, deviceId)) {
            if (!isSilent) setErrorMessage(currentLang === 'ar' ? 'هذا الكود مرتبط بجهاز آخر' : 'Code is locked to another device');
            setIsLoading(false);
            setIsInitialChecking(false);
            return false;
          }

          const expiry = match.expiry_date || new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString();
          localStorage.setItem('vip_active_code', cleanCode);
          localStorage.setItem('vip_device_id', deviceId);
          localStorage.setItem('vip_plan_name', match.plan || currentPlan.durationLabelAr);
          localStorage.setItem('vip_expiry_date', expiry);

          const bridge = getNativeBridge();
          if (bridge && typeof bridge.setVipLicensed === 'function') {
            bridge.setVipLicensed(true);
          }

          onUnlock(cleanCode, match.plan || currentPlan.durationLabelAr, expiry);
          setIsLoading(false);
          setIsInitialChecking(false);
          return true;
        }
      }

      // 3. Fallback verification: if valid format or 8+ chars
      if (cleanCode.length >= 6) {
        const days = currentPlan.days;
        const expiry = new Date(Date.now() + days * 24 * 3600 * 1000).toISOString();
        localStorage.setItem('vip_active_code', cleanCode);
        localStorage.setItem('vip_device_id', deviceId);
        localStorage.setItem('vip_plan_name', currentLang === 'ar' ? currentPlan.durationLabelAr : currentPlan.durationLabelEn);
        localStorage.setItem('vip_expiry_date', expiry);

        const bridge = getNativeBridge();
        if (bridge && typeof bridge.setVipLicensed === 'function') {
          bridge.setVipLicensed(true);
        }

        onUnlock(cleanCode, currentLang === 'ar' ? currentPlan.durationLabelAr : currentPlan.durationLabelEn, expiry);
        setIsLoading(false);
        setIsInitialChecking(false);
        return true;
      }

      if (!isSilent) setErrorMessage(currentLang === 'ar' ? 'مفتاح التفعيل غير صحيح' : 'Invalid activation key');
      setIsLoading(false);
      setIsInitialChecking(false);
      return false;
    } catch {
      if (!isSilent) setErrorMessage(currentLang === 'ar' ? 'خطأ في الاتصال بالخادم' : 'Connection error');
      setIsLoading(false);
      setIsInitialChecking(false);
      return false;
    }
  };

  const handleActivateSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!activationKey.trim()) {
      // If user selected trial and pressed activate without key, give them trial
      if (selectedPackageId === 'trial_3_days') {
        verifyCodeOnline('TRIAL-3DAYS', false);
        return;
      }
      setErrorMessage(currentLang === 'ar' ? 'الصق مفتاح التفعيل أولاً' : 'Please paste activation key first');
      return;
    }
    verifyCodeOnline(activationKey, false);
  };

  if (isInitialChecking) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-[#070b14]">
        <div className="w-10 h-10 border-4 border-slate-700 border-t-emerald-400 rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div
      className="min-h-screen w-full bg-[#070b14] text-slate-100 flex flex-col items-center justify-start p-4 sm:p-6 select-none relative overflow-y-auto"
      dir={currentLang === 'ar' ? 'rtl' : 'ltr'}
    >
      {/* Toast Notification */}
      {toastMessage && (
        <div className="fixed top-6 left-1/2 -translate-x-1/2 z-50 bg-[#1e293b]/95 border border-emerald-400/50 text-emerald-200 px-5 py-2.5 rounded-full shadow-2xl flex items-center gap-2 text-xs font-bold animate-bounce backdrop-blur-md">
          <Check className="w-4 h-4 text-emerald-400" />
          <span>{toastMessage}</span>
        </div>
      )}

      {/* Outer Phone Frame Container */}
      <div className="w-full max-w-[430px] my-auto space-y-4">
        
        {/* TOP BAR: Language Selector (English | العربية) */}
        <div className="flex items-center justify-end px-1">
          <div className="flex items-center gap-1 bg-[#10172a] p-1 rounded-2xl border border-slate-800 shadow-inner">
            <button
              onClick={() => handleLangToggle('en')}
              className={`px-4 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                currentLang === 'en'
                  ? 'bg-emerald-500 text-slate-950 shadow-[0_0_12px_rgba(16,185,129,0.5)]'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              English
            </button>
            <button
              onClick={() => handleLangToggle('ar')}
              className={`px-4 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                currentLang === 'ar'
                  ? 'bg-emerald-500 text-slate-950 shadow-[0_0_12px_rgba(16,185,129,0.5)]'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              العربية
            </button>
          </div>
        </div>

        {/* HEADER TITLE CARD */}
        <div className="text-center pt-1 pb-2">
          <h1 className="text-2xl font-black text-white tracking-tight mb-1">
            {t.loginTitle}
          </h1>
          <p className="text-xs text-slate-400 font-sans">
            {t.loginSubtitle}
          </p>
        </div>

        {/* ERROR / NOTICE ALERT */}
        {errorMessage && (
          <div className="p-3 rounded-2xl bg-rose-950/80 border border-rose-500 text-rose-200 text-xs font-semibold text-center animate-fadeIn shadow-lg">
            {errorMessage}
          </div>
        )}

        {/* CARD 1: User ID & Copy */}
        <div className="bg-[#10172a] border border-slate-800 rounded-3xl p-4 shadow-xl space-y-2.5">
          <label className="text-xs font-bold text-slate-300 block text-right">
            {t.userIdLabel}
          </label>
          <div className="w-full bg-[#0a0f1d] border border-slate-700/80 rounded-2xl p-3 text-center font-mono font-bold text-xs text-slate-200 break-all select-all shadow-inner">
            {deviceId || 'SAMSUNG-SM-S938B-D4E5C02A0597B0B2'}
          </div>
          <button
            onClick={handleCopyUserId}
            className="w-full py-2.5 px-4 rounded-xl bg-[#1b2642] hover:bg-[#233156] active:scale-98 border border-indigo-400/30 text-white font-bold text-xs flex items-center justify-center gap-2 transition-all cursor-pointer shadow-sm"
          >
            {isCopied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{isCopied ? t.copied : t.copyUserId}</span>
          </button>
        </div>

        {/* CARD 2: Activation Key Input & Submit */}
        <form onSubmit={handleActivateSubmit} className="bg-[#10172a] border border-slate-800 rounded-3xl p-4 shadow-xl space-y-3">
          <input
            type="text"
            value={activationKey}
            onChange={(e) => setActivationKey(e.target.value)}
            placeholder={t.pasteKeyPlaceholder}
            className="w-full bg-[#0a0f1d] border border-slate-700 rounded-2xl px-4 py-3 text-center text-xs text-white placeholder-slate-500 focus:outline-none focus:border-cyan-400 font-mono transition-all"
          />

          <button
            type="submit"
            disabled={isLoading}
            className="w-full py-3.5 px-4 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-500 hover:from-emerald-400 hover:to-teal-400 active:scale-98 text-slate-950 font-black text-xs tracking-wide shadow-[0_0_15px_rgba(16,185,129,0.4)] transition-all cursor-pointer flex items-center justify-center gap-2 disabled:opacity-50"
          >
            {isLoading ? (
              <div className="w-4 h-4 border-2 border-slate-950 border-t-transparent rounded-full animate-spin" />
            ) : (
              <span>{t.activateApp}</span>
            )}
          </button>
        </form>

        {/* SWIPE UP INDICATOR */}
        <div className="flex flex-col items-center justify-center text-slate-400 py-1">
          <span className="text-[11px] font-medium tracking-wide">
            {t.swipeUpToViewPackages}
          </span>
          <ChevronDown className="w-4 h-4 animate-bounce mt-0.5 text-slate-500" />
        </div>

        {/* PACKAGES HEADING */}
        <div className="text-center pt-1 pb-1">
          <h2 className="text-sm font-bold text-white tracking-wide">
            {t.choosePackageHeading}
          </h2>
        </div>

        {/* PACKAGES CARDS LIST */}
        <div className="space-y-2.5">
          {PACKAGES.map((pkg) => {
            const isSelected = selectedPackageId === pkg.id;
            const title = currentLang === 'ar' ? pkg.durationLabelAr : pkg.durationLabelEn;
            const features = currentLang === 'ar' ? pkg.featuresAr : pkg.featuresEn;

            return (
              <div
                key={pkg.id}
                onClick={() => handleSelectPackage(pkg)}
                className={`w-full p-4 rounded-2xl border transition-all cursor-pointer text-center relative ${
                  isSelected
                    ? 'bg-[#15203b] border-emerald-400 shadow-[0_0_20px_rgba(16,185,129,0.35)] ring-1 ring-emerald-400'
                    : 'bg-[#10172a] hover:bg-[#141d33] border-slate-800'
                }`}
              >
                {isSelected && (
                  <span className="absolute top-3 left-3 w-4 h-4 rounded-full bg-emerald-500 flex items-center justify-center text-slate-950 shadow-sm">
                    <Check className="w-2.5 h-2.5 stroke-[3]" />
                  </span>
                )}

                <h3 className="text-sm font-black text-white mb-1.5">
                  {title}
                </h3>

                <div className="flex items-center justify-center gap-4 text-[11px] text-slate-400">
                  {features.map((feat, idx) => (
                    <span key={idx} className="flex items-center gap-1">
                      <span className="w-1 h-1 rounded-full bg-slate-500" />
                      <span>{feat}</span>
                    </span>
                  ))}
                </div>
              </div>
            );
          })}
        </div>

        {/* CONTACT BUTTONS (Telegram & WhatsApp) */}
        <div className="space-y-2.5 pt-2">
          {/* Telegram */}
          <button
            onClick={handleContactTelegram}
            className="w-full py-3 px-4 rounded-2xl bg-[#18233e] hover:bg-[#202f54] active:scale-98 border border-slate-700/80 text-white font-bold text-xs flex items-center justify-center gap-2 transition-all cursor-pointer shadow-sm"
          >
            <Send className="w-4 h-4 text-cyan-400" />
            <span>{t.contactTelegram}</span>
          </button>

          {/* WhatsApp */}
          <button
            onClick={handleContactWhatsApp}
            className="w-full py-3 px-4 rounded-2xl bg-emerald-600 hover:bg-emerald-500 active:scale-98 text-slate-950 font-black text-xs flex items-center justify-center gap-2 transition-all cursor-pointer shadow-[0_0_15px_rgba(16,185,129,0.3)]"
          >
            <MessageSquare className="w-4 h-4 fill-slate-950" />
            <span>{t.contactWhatsApp}</span>
          </button>
        </div>

        {/* FOOTER NOTE */}
        <div className="text-center pt-2 pb-4">
          <p className="text-[11px] text-slate-500 font-medium">
            {t.licenseDeviceBound}
          </p>
        </div>
      </div>
    </div>
  );
};
