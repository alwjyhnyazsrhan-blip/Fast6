import fs from 'fs';
import path from 'path';

export type SubscriptionDuration =
  | 'trial_3_days'
  | '1_month'
  | '2_months'
  | '3_months'
  | '6_months'
  | '1_year'
  | 'custom';

export type SubscriptionStatus = 'active' | 'used' | 'expired' | 'revoked';

export interface SubscriptionCode {
  id: string;
  code: string;
  planName: string;
  duration: SubscriptionDuration;
  durationDays: number;
  targetDeviceId?: string | null;
  usedByDeviceId?: string | null;
  driverName?: string | null;
  driverPhone?: string | null;
  notes?: string | null;
  startMode: 'on_first_activation' | 'from_now';
  createdAt: string;
  activatedAt?: string | null;
  expiryDate: string;
  status: SubscriptionStatus;
  lastVerifiedAt?: string | null;
}

export interface SubscriptionDbSchema {
  version: number;
  updatedAt: string;
  codes: Record<string, SubscriptionCode>;
}

export interface CreateSubscriptionPayload {
  code?: string;
  planName?: string;
  duration?: SubscriptionDuration;
  durationDays?: number;
  targetDeviceId?: string | null;
  driverName?: string | null;
  driverPhone?: string | null;
  notes?: string | null;
  startMode?: 'on_first_activation' | 'from_now';
}

const FIREBASE_DB_URL = process.env.FIREBASE_DB_URL || 'https://appprotector-79e9c-default-rtdb.firebaseio.com';

export function toFirebasePayload(record: SubscriptionCode) {
  return {
    id: record.id,
    code: record.code,
    status: record.status,
    expiry_date: record.expiryDate,
    expiryDate: record.expiryDate,
    used_by: record.usedByDeviceId || record.targetDeviceId || '',
    usedByDeviceId: record.usedByDeviceId || null,
    target_device_id: record.targetDeviceId || '',
    targetDeviceId: record.targetDeviceId || null,
    driver_name: record.driverName || '',
    driverName: record.driverName || null,
    driver_phone: record.driverPhone || '',
    driverPhone: record.driverPhone || null,
    plan_name: record.planName,
    planName: record.planName,
    duration: record.duration,
    duration_days: record.durationDays,
    durationDays: record.durationDays,
    max_uses: 1,
    used_count: record.usedByDeviceId ? 1 : 0,
    note: record.notes || '',
    notes: record.notes || null,
    start_mode: record.startMode,
    startMode: record.startMode,
    created_at: record.createdAt,
    createdAt: record.createdAt,
    activated_at: record.activatedAt || null,
    activatedAt: record.activatedAt || null,
    last_verified_at: record.lastVerifiedAt || null,
    lastVerifiedAt: record.lastVerifiedAt || null,
  };
}

export class SubscriptionDatabase {
  private dbFilePath: string;
  private dbData: SubscriptionDbSchema;
  private saveTimeout: NodeJS.Timeout | null = null;

  constructor() {
    const dataDir = path.join(process.cwd(), 'data');
    if (!fs.existsSync(dataDir)) {
      try {
        fs.mkdirSync(dataDir, { recursive: true });
      } catch (err) {
        console.error('[SubscriptionDatabase] Error creating data directory:', err);
      }
    }

    this.dbFilePath = path.join(dataDir, 'subscriptions.json');
    this.dbData = this.loadDatabase();

    // مزامنة فورية مع قاعدة بيانات فايربيس السحابية https://appprotector-79e9c-default-rtdb.firebaseio.com
    setTimeout(() => {
      this.syncAllWithFirebase()
        .then((res) => {
          console.log(`[SubscriptionDatabase] ✅ Synced ${res.syncedCount} code(s) from ${FIREBASE_DB_URL}`);
        })
        .catch((err) => {
          console.warn('[SubscriptionDatabase] Initial Firebase RTDB sync notice:', err);
        });
    }, 1000);

    // مزامنة دورية كل 60 ثانية لضمان تحديث الأكواد سحابياً أولاً بأول
    setInterval(() => {
      this.syncAllWithFirebase().catch(() => {});
    }, 60 * 1000);
  }

  private loadDatabase(): SubscriptionDbSchema {
    try {
      if (fs.existsSync(this.dbFilePath)) {
        const raw = fs.readFileSync(this.dbFilePath, 'utf-8');
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed.codes === 'object') {
          console.log(
            `[SubscriptionDatabase] Loaded ${Object.keys(parsed.codes).length} subscription code(s).`
          );
          return parsed;
        }
      }
    } catch (err) {
      console.warn('[SubscriptionDatabase] Error reading subscriptions.json:', err);
    }

    // Initialize with default schema and sample codes
    const initialDb: SubscriptionDbSchema = {
      version: 1,
      updatedAt: new Date().toISOString(),
      codes: {},
    };

    // Seed initial codes if empty
    this.seedDefaultCodes(initialDb);
    return initialDb;
  }

  private seedDefaultCodes(db: SubscriptionDbSchema) {
    const now = Date.now();
    
    // Sample 1: 3-day trial code
    const trialCode: SubscriptionCode = {
      id: `sub-trial-${now}`,
      code: 'VIP-FREE-TRIAL',
      planName: 'تجربة 3 أيام',
      duration: 'trial_3_days',
      durationDays: 3,
      targetDeviceId: null,
      usedByDeviceId: null,
      driverName: 'تجربة مجانية',
      driverPhone: null,
      notes: 'كود تجريبي عام لمدة 3 أيام',
      startMode: 'on_first_activation',
      createdAt: new Date().toISOString(),
      activatedAt: null,
      expiryDate: new Date(now + 3 * 86400 * 1000).toISOString(),
      status: 'active',
      lastVerifiedAt: null,
    };

    // Sample 2: 1-month VIP subscription
    const monthCode: SubscriptionCode = {
      id: `sub-month-${now}`,
      code: 'LOCATE-VIP-2026',
      planName: 'شهر',
      duration: '1_month',
      durationDays: 30,
      targetDeviceId: null,
      usedByDeviceId: null,
      driverName: 'اشتراك شهري',
      driverPhone: null,
      notes: 'كود VIP شهري مفتوح لجميع الأجهزة',
      startMode: 'on_first_activation',
      createdAt: new Date().toISOString(),
      activatedAt: null,
      expiryDate: new Date(now + 30 * 86400 * 1000).toISOString(),
      status: 'active',
      lastVerifiedAt: null,
    };

    db.codes[trialCode.code] = trialCode;
    db.codes[monthCode.code] = monthCode;
  }

  private saveDatabase() {
    this.dbData.updatedAt = new Date().toISOString();
    try {
      const tempPath = `${this.dbFilePath}.tmp`;
      fs.writeFileSync(tempPath, JSON.stringify(this.dbData, null, 2), 'utf-8');
      fs.renameSync(tempPath, this.dbFilePath);
    } catch (err) {
      console.error('[SubscriptionDatabase] Error saving subscriptions.json:', err);
    }
  }

  private scheduleSave() {
    if (this.saveTimeout) {
      clearTimeout(this.saveTimeout);
    }
    this.saveTimeout = setTimeout(() => {
      this.saveDatabase();
      this.saveTimeout = null;
    }, 100);
  }

  public generateRandomCode(prefix = 'VIP'): string {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let part1 = '';
    let part2 = '';
    for (let i = 0; i < 4; i++) {
      part1 += chars.charAt(Math.floor(Math.random() * chars.length));
      part2 += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return `${prefix}-${part1}-${part2}`;
  }

  public normalizeCode(code: string): string {
    return (code || '').trim().toUpperCase();
  }

  public sanitizeDeviceId(deviceId?: string | null): string {
    if (!deviceId || typeof deviceId !== 'string') return '';
    return deviceId.trim().toUpperCase().replace(/[^A-Z0-9_=-]/g, '').slice(0, 32);
  }

  public getAllCodes(): SubscriptionCode[] {
    return Object.values(this.dbData.codes).sort((a, b) => {
      return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
    });
  }

  public getCode(rawCodeOrId: string): SubscriptionCode | null {
    const key = this.normalizeCode(rawCodeOrId);
    if (this.dbData.codes[key]) {
      return this.dbData.codes[key];
    }
    // Search by ID
    const found = Object.values(this.dbData.codes).find((c) => c.id === rawCodeOrId || c.code === key);
    return found || null;
  }

  public createCode(payload: CreateSubscriptionPayload): SubscriptionCode {
    const code = this.normalizeCode(payload.code || this.generateRandomCode('VIP'));
    const duration = payload.duration || '1_month';
    const durationDays = payload.durationDays || this.getDaysForDuration(duration);
    const planName = payload.planName || this.getPlanNameForDuration(duration);
    const startMode = payload.startMode || 'on_first_activation';
    const targetDeviceId = payload.targetDeviceId ? this.sanitizeDeviceId(payload.targetDeviceId) : null;

    const now = Date.now();
    const expiryDate =
      startMode === 'from_now'
        ? new Date(now + durationDays * 86400 * 1000).toISOString()
        : new Date(now + durationDays * 86400 * 1000).toISOString();

    const record: SubscriptionCode = {
      id: `sub-${Date.now()}-${Math.floor(Math.random() * 1000)}`,
      code,
      planName,
      duration,
      durationDays,
      targetDeviceId: targetDeviceId || null,
      usedByDeviceId: targetDeviceId || null, // Pre-bind if explicitly targeted
      driverName: payload.driverName?.trim() || null,
      driverPhone: payload.driverPhone?.trim() || null,
      notes: payload.notes?.trim() || null,
      startMode,
      createdAt: new Date().toISOString(),
      activatedAt: startMode === 'from_now' ? new Date().toISOString() : null,
      expiryDate,
      status: 'active',
      lastVerifiedAt: null,
    };

    this.dbData.codes[code] = record;
    this.scheduleSave();
    this.syncCodeToFirebase(record).catch(() => {});
    console.log(`[SubscriptionDatabase] Created subscription code: ${code} (${planName}, Target: ${targetDeviceId || 'Any'})`);
    return record;
  }

  public updateCode(codeOrId: string, updates: Partial<SubscriptionCode>): SubscriptionCode | null {
    const existing = this.getCode(codeOrId);
    if (!existing) return null;

    if (updates.driverName !== undefined) existing.driverName = updates.driverName;
    if (updates.driverPhone !== undefined) existing.driverPhone = updates.driverPhone;
    if (updates.notes !== undefined) existing.notes = updates.notes;
    if (updates.status !== undefined) existing.status = updates.status;
    if (updates.targetDeviceId !== undefined) {
      existing.targetDeviceId = updates.targetDeviceId ? this.sanitizeDeviceId(updates.targetDeviceId) : null;
    }
    if (updates.expiryDate !== undefined) existing.expiryDate = updates.expiryDate;

    this.scheduleSave();
    this.syncCodeToFirebase(existing).catch(() => {});
    return existing;
  }

  public extendCode(codeOrId: string, extraDays: number): SubscriptionCode | null {
    const existing = this.getCode(codeOrId);
    if (!existing) return null;

    const currentExpiry = new Date(existing.expiryDate).getTime();
    const baseTime = currentExpiry > Date.now() ? currentExpiry : Date.now();
    const newExpiry = new Date(baseTime + extraDays * 86400 * 1000).toISOString();

    existing.expiryDate = newExpiry;
    existing.durationDays += extraDays;
    if (existing.status === 'expired') {
      existing.status = existing.usedByDeviceId ? 'used' : 'active';
    }

    this.scheduleSave();
    this.syncCodeToFirebase(existing).catch(() => {});
    console.log(`[SubscriptionDatabase] Extended code ${existing.code} by ${extraDays} days to ${newExpiry}`);
    return existing;
  }

  public revokeCode(codeOrId: string): SubscriptionCode | null {
    const existing = this.getCode(codeOrId);
    if (!existing) return null;
    existing.status = 'revoked';
    this.scheduleSave();
    this.syncCodeToFirebase(existing).catch(() => {});
    return existing;
  }

  public activateCode(codeOrId: string): SubscriptionCode | null {
    const existing = this.getCode(codeOrId);
    if (!existing) return null;
    existing.status = existing.usedByDeviceId ? 'used' : 'active';
    this.scheduleSave();
    this.syncCodeToFirebase(existing).catch(() => {});
    return existing;
  }

  public unbindDevice(codeOrId: string): SubscriptionCode | null {
    const existing = this.getCode(codeOrId);
    if (!existing) return null;
    existing.usedByDeviceId = null;
    existing.targetDeviceId = null;
    existing.status = 'active';
    this.scheduleSave();
    this.syncCodeToFirebase(existing).catch(() => {});
    console.log(`[SubscriptionDatabase] Unbound device from code ${existing.code}`);
    return existing;
  }

  public deleteCode(codeOrId: string): boolean {
    const existing = this.getCode(codeOrId);
    if (!existing) return false;
    const targetId = existing.id;
    delete this.dbData.codes[existing.code];
    this.scheduleSave();
    this.deleteCodeFromFirebase(targetId).catch(() => {});
    return true;
  }

  public async syncCodeToFirebase(record: SubscriptionCode): Promise<boolean> {
    try {
      const payload = toFirebasePayload(record);
      const cleanBase = FIREBASE_DB_URL.replace(/\/+$/, '');
      const res = await fetch(`${cleanBase}/activation_codes/${record.id}.json`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      return res.ok;
    } catch (err) {
      // Offline fallback: log and continue seamlessly
      console.warn(`[SubscriptionDatabase] Offline notice: Synced locally, Firebase sync deferred: ${record.code}`);
      return false;
    }
  }

  public async deleteCodeFromFirebase(id: string): Promise<boolean> {
    try {
      const cleanBase = FIREBASE_DB_URL.replace(/\/+$/, '');
      const res = await fetch(`${cleanBase}/activation_codes/${id}.json`, {
        method: 'DELETE',
      });
      return res.ok;
    } catch {
      return false;
    }
  }

  public async syncAllWithFirebase(): Promise<{ syncedCount: number; success: boolean }> {
    try {
      const cleanBase = FIREBASE_DB_URL.replace(/\/+$/, '');
      const res = await fetch(`${cleanBase}/activation_codes.json`);
      if (!res.ok) throw new Error(`Firebase status ${res.status}`);
      const data: Record<string, any> = (await res.json()) || {};
      const remoteCodes = Object.values(data);
      let count = 0;

      for (const item of remoteCodes) {
        if (!item || !item.code) continue;
        const normCode = this.normalizeCode(item.code);
        const existing = this.dbData.codes[normCode];
        if (!existing) {
          const imported: SubscriptionCode = {
            id: item.id || `sub-${Date.now()}-${Math.floor(Math.random() * 1000)}`,
            code: normCode,
            planName: item.plan_name || item.planName || 'مخصص',
            duration: item.duration || 'custom',
            durationDays: Number(item.duration_days || item.durationDays || 30),
            targetDeviceId: item.target_device_id || item.targetDeviceId || null,
            usedByDeviceId: item.used_by || item.usedByDeviceId || null,
            driverName: item.driver_name || item.driverName || null,
            driverPhone: item.driver_phone || item.driverPhone || null,
            notes: item.note || item.notes || null,
            startMode: item.start_mode || item.startMode || 'on_first_activation',
            createdAt: item.created_at || item.createdAt || new Date().toISOString(),
            activatedAt: item.activated_at || item.activatedAt || null,
            expiryDate: item.expiry_date || item.expiryDate || new Date(Date.now() + 30 * 86400 * 1000).toISOString(),
            status: item.status || 'active',
            lastVerifiedAt: item.last_verified_at || item.lastVerifiedAt || null,
          };
          this.dbData.codes[normCode] = imported;
          count++;
        } else {
          // تحديث الحالة وتاريخ الانتهاء والجهاز إذا تم تعديلها في فايربيس
          let updated = false;
          if (item.status && item.status !== existing.status) {
            existing.status = item.status;
            updated = true;
          }
          if (item.used_by && item.used_by !== existing.usedByDeviceId) {
            existing.usedByDeviceId = item.used_by;
            updated = true;
          }
          if (item.expiry_date && item.expiry_date !== existing.expiryDate) {
            existing.expiryDate = item.expiry_date;
            updated = true;
          }
          if (updated) count++;
        }
      }

      for (const localCode of Object.values(this.dbData.codes)) {
        const foundInRemote = remoteCodes.some((r) => r && this.normalizeCode(r.code) === localCode.code);
        if (!foundInRemote) {
          await this.syncCodeToFirebase(localCode);
        }
      }

      if (count > 0) {
        this.scheduleSave();
      }
      return { syncedCount: count, success: true };
    } catch (err) {
      return { syncedCount: 0, success: false };
    }
  }

  /**
   * التحقق والتفعيل البرمجي الشامل من كود الاشتراك مع الربط الآلي بمعرف الجهاز
   */
  public verifyOrRedeem(rawCode: string, rawDeviceId: string): {
    isValid: boolean;
    reason: 'active' | 'expired' | 'revoked' | 'not_found' | 'device_mismatch';
    message: string;
    code?: string;
    planName?: string;
    expiryDate?: string;
    remainingHours?: number;
    usedBy?: string;
  } {
    const code = this.normalizeCode(rawCode);
    const deviceId = this.sanitizeDeviceId(rawDeviceId);

    const record = this.dbData.codes[code];
    if (!record) {
      return {
        isValid: false,
        reason: 'not_found',
        message: '❌ كود الاشتراك غير مسجل في قاعدة البيانات',
      };
    }

    // 1. فحص الإلغاء أو التعطيل
    if (record.status === 'revoked') {
      return {
        isValid: false,
        reason: 'revoked',
        message: '🚫 تم تعطيل أو إلغاء صلاحية هذا الكود من قبل الإدارة',
      };
    }

    // 2. التحقق من تطابق معرف الجهاز (Device ID Binding)
    // أ) إذا كان الكود مخصصاً لجهاز مستهدف محدد
    if (record.targetDeviceId && record.targetDeviceId !== '') {
      if (record.targetDeviceId !== deviceId) {
        return {
          isValid: false,
          reason: 'device_mismatch',
          message: `⚠️ كود الاشتراك مخصص لجهاز آخر (${record.targetDeviceId})`,
          usedBy: record.targetDeviceId,
        };
      }
    }

    // ب) إذا كان الكود قد استُخدم سابقاً من جهاز آخر
    if (record.usedByDeviceId && record.usedByDeviceId !== '') {
      if (record.usedByDeviceId !== deviceId) {
        return {
          isValid: false,
          reason: 'device_mismatch',
          message: `⚠️ كود الاشتراك مرتبط ومستخدم بجوال آخر (${record.usedByDeviceId})`,
          usedBy: record.usedByDeviceId,
        };
      }
    }

    // 3. التفعيل الأول للكود وربطه بهذا الجهاز وبدء حساب المدة
    const now = Date.now();
    if (!record.activatedAt) {
      record.activatedAt = new Date().toISOString();
      record.usedByDeviceId = deviceId;
      record.status = 'used';

      // إذا كان خيار التفعيل يبدأ عند أول استخدام: نبدأ حساب تاريخ الانتهاء من الآن
      if (record.startMode === 'on_first_activation') {
        record.expiryDate = new Date(now + record.durationDays * 86400 * 1000).toISOString();
      }
      this.scheduleSave();
      this.syncCodeToFirebase(record).catch(() => {});
      console.log(`[SubscriptionDatabase] First-time activation: Code ${code} bound to Device ${deviceId}`);
    } else if (!record.usedByDeviceId) {
      record.usedByDeviceId = deviceId;
      record.status = 'used';
      this.scheduleSave();
      this.syncCodeToFirebase(record).catch(() => {});
    }

    // 4. التحقق من تاريخ الصلاحية
    const expiryTime = new Date(record.expiryDate).getTime();
    if (!isNaN(expiryTime) && now > expiryTime + 5 * 60 * 1000) {
      record.status = 'expired';
      this.scheduleSave();
      return {
        isValid: false,
        reason: 'expired',
        message: `⏰ انتهت صلاحية اشتراكك في (${new Date(record.expiryDate).toLocaleDateString('ar-SA')})`,
        code: record.code,
        planName: record.planName,
        expiryDate: record.expiryDate,
        remainingHours: 0,
      };
    }

    // 5. حساب الساعات المتبقية
    const diffMs = expiryTime - now;
    const remainingHours = Math.max(0, Math.round(diffMs / (1000 * 60 * 60)));

    record.lastVerifiedAt = new Date().toISOString();
    this.scheduleSave();

    return {
      isValid: true,
      reason: 'active',
      message: '✅ كود الاشتراك نشط وصالح',
      code: record.code,
      planName: record.planName,
      expiryDate: record.expiryDate,
      remainingHours,
      usedBy: record.usedByDeviceId || record.targetDeviceId || deviceId,
    };
  }

  public getStats() {
    const list = Object.values(this.dbData.codes);
    const now = Date.now();

    const active = list.filter((c) => {
      const exp = new Date(c.expiryDate).getTime();
      return c.status !== 'revoked' && exp > now;
    }).length;

    const used = list.filter((c) => !!c.usedByDeviceId).length;
    const expired = list.filter((c) => {
      const exp = new Date(c.expiryDate).getTime();
      return c.status === 'expired' || exp <= now;
    }).length;
    const revoked = list.filter((c) => c.status === 'revoked').length;

    return {
      total: list.length,
      active,
      used,
      expired,
      revoked,
    };
  }

  private getDaysForDuration(duration: SubscriptionDuration): number {
    switch (duration) {
      case 'trial_3_days':
        return 3;
      case '1_month':
        return 30;
      case '2_months':
        return 60;
      case '3_months':
        return 90;
      case '6_months':
        return 180;
      case '1_year':
        return 365;
      case 'custom':
        return 30;
      default:
        return 30;
    }
  }

  private getPlanNameForDuration(duration: SubscriptionDuration): string {
    switch (duration) {
      case 'trial_3_days':
        return 'تجربة 3 أيام';
      case '1_month':
        return 'شهر';
      case '2_months':
        return 'شهرين';
      case '3_months':
        return '3 أشهر';
      case '6_months':
        return '6 أشهر';
      case '1_year':
        return 'سنة';
      case 'custom':
        return 'مخصص';
      default:
        return 'شهر';
    }
  }
}

export const subscriptionDb = new SubscriptionDatabase();
