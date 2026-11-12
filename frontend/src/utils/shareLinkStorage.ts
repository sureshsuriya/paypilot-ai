import type { CachedShareLink, GeneratedPaymentLinkResponse } from '@/types';

const SHARE_LINK_STORAGE_PREFIX = 'paypilot.seller.shareLinks.';

function getSessionStorageSafe(): Storage | null {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function readSellerMap(sellerUserId: number): Record<string, CachedShareLink> {
  const storage = getSessionStorageSafe();
  if (!storage || !Number.isFinite(sellerUserId)) {
    return {};
  }
  try {
    const raw = storage.getItem(`${SHARE_LINK_STORAGE_PREFIX}${sellerUserId}`);
    if (!raw) {
      return {};
    }
    const parsed = JSON.parse(raw) as unknown;
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, CachedShareLink>) : {};
  } catch {
    return {};
  }
}

function writeSellerMap(sellerUserId: number, map: Record<string, CachedShareLink>): void {
  const storage = getSessionStorageSafe();
  if (!storage || !Number.isFinite(sellerUserId)) {
    return;
  }
  try {
    storage.setItem(`${SHARE_LINK_STORAGE_PREFIX}${sellerUserId}`, JSON.stringify(map));
  } catch {
    // Best-effort tab session convenience cache only.
  }
}

export function saveCachedShareLink(
  sellerUserId: number | undefined,
  generated: GeneratedPaymentLinkResponse
): CachedShareLink {
  const entry: CachedShareLink = {
    paymentLinkId: generated.link.id,
    invoiceId: generated.link.invoiceId,
    paymentUrl: generated.paymentUrl,
    rawToken: generated.rawToken,
    tokenPrefix: generated.link.tokenPrefix,
    expiresAt: generated.link.expiresAt,
  };
  if (sellerUserId !== undefined) {
    const map = readSellerMap(sellerUserId);
    map[String(generated.link.invoiceId)] = entry;
    writeSellerMap(sellerUserId, map);
  }
  return entry;
}

export function loadCachedShareLink(
  sellerUserId: number | undefined,
  invoiceId: number,
  activePaymentLinkId?: number | null
): CachedShareLink | null {
  if (sellerUserId === undefined) {
    return null;
  }
  const map = readSellerMap(sellerUserId);
  const entry = map[String(invoiceId)] ?? null;
  if (!entry) {
    return null;
  }
  if (activePaymentLinkId !== undefined && activePaymentLinkId !== entry.paymentLinkId) {
    delete map[String(invoiceId)];
    writeSellerMap(sellerUserId, map);
    return null;
  }
  return entry;
}

export function removeCachedShareLink(
  sellerUserId: number | undefined,
  invoiceId: number
): void {
  if (sellerUserId === undefined) {
    return;
  }
  const map = readSellerMap(sellerUserId);
  if (String(invoiceId) in map) {
    delete map[String(invoiceId)];
    writeSellerMap(sellerUserId, map);
  }
}

export function clearAllCachedShareLinks(): void {
  const storage = getSessionStorageSafe();
  if (!storage) {
    return;
  }
  try {
    const keysToRemove: string[] = [];
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (key && key.startsWith(SHARE_LINK_STORAGE_PREFIX)) {
        keysToRemove.push(key);
      }
    }
    for (const key of keysToRemove) {
      storage.removeItem(key);
    }
  } catch {
    // Ignore storage cleanup failures.
  }
}

