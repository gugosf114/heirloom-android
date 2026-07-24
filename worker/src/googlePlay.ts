export const CREDIT_PACKS: Readonly<Record<string, number>> = Object.freeze({
  heirloom_restorations_5_v1: 5,
  heirloom_restorations_20_v1: 20,
  heirloom_restorations_50_v1: 50,
});

const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';
const GOOGLE_SCOPES = [
  'https://www.googleapis.com/auth/androidpublisher',
  'https://www.googleapis.com/auth/playintegrity',
].join(' ');

interface ServiceAccount {
  client_email: string;
  private_key: string;
}

interface CachedAccessToken {
  token: string;
  expiresAt: number;
}

let cachedAccessToken: CachedAccessToken | undefined;

export interface ProductPurchaseV2 {
  productLineItem?: Array<{
    productId?: string;
    productOfferDetails?: {
      consumptionState?: string;
    };
  }>;
  purchaseStateContext?: {
    purchaseState?: string;
  };
  orderId?: string;
  regionCode?: string;
  acknowledgementState?: string;
}

export interface VerifiedPurchase {
  productId: string;
  credits: number;
  orderId?: string;
  regionCode: string;
  acknowledged: boolean;
  consumed: boolean;
}

export interface IntegrityVerdict {
  tokenPayloadExternal?: {
    requestDetails?: {
      requestPackageName?: string;
      requestHash?: string;
      timestampMillis?: string;
    };
    accountDetails?: {
      appLicensingVerdict?: string;
    };
    appIntegrity?: {
      appRecognitionVerdict?: string;
      packageName?: string;
      versionCode?: string;
    };
    deviceIntegrity?: {
      deviceRecognitionVerdict?: string[];
    };
  };
}

export interface IntegrityExpectations {
  packageName: string;
  requestHash: string;
  minimumVersionCode: number;
  nowMillis?: number;
}

export function parseVerifiedPurchase(payload: ProductPurchaseV2): VerifiedPurchase | null {
  if (payload.purchaseStateContext?.purchaseState !== 'PURCHASED') return null;
  const lineItems = payload.productLineItem ?? [];
  if (lineItems.length !== 1) return null;
  const productId = lineItems[0].productId ?? '';
  const credits = CREDIT_PACKS[productId];
  if (!credits) return null;
  const regionCode = payload.regionCode ?? '';
  if (regionCode !== 'US') return null;
  return {
    productId,
    credits,
    orderId: payload.orderId,
    regionCode,
    acknowledged: payload.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    consumed:
      lineItems[0].productOfferDetails?.consumptionState === 'CONSUMPTION_STATE_CONSUMED',
  };
}

export function validateIntegrityVerdict(
  verdict: IntegrityVerdict,
  expected: IntegrityExpectations,
): boolean {
  const payload = verdict.tokenPayloadExternal;
  if (!payload) return false;
  const request = payload.requestDetails;
  const app = payload.appIntegrity;
  const device = payload.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  const timestamp = Number(request?.timestampMillis);
  const now = expected.nowMillis ?? Date.now();
  return (
    request?.requestPackageName === expected.packageName &&
    request.requestHash === expected.requestHash &&
    Number.isFinite(timestamp) &&
    Math.abs(now - timestamp) <= 2 * 60 * 1000 &&
    payload.accountDetails?.appLicensingVerdict === 'LICENSED' &&
    app?.appRecognitionVerdict === 'PLAY_RECOGNIZED' &&
    app.packageName === expected.packageName &&
    Number(app.versionCode) >= expected.minimumVersionCode &&
    device.includes('MEETS_DEVICE_INTEGRITY')
  );
}

export async function decodeIntegrityToken(
  serviceAccountJson: string,
  packageName: string,
  integrityToken: string,
): Promise<IntegrityVerdict> {
  const accessToken = await googleAccessToken(serviceAccountJson);
  const response = await fetch(
    `https://playintegrity.googleapis.com/v1/${encodeURIComponent(packageName)}:decodeIntegrityToken`,
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${accessToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({ integrity_token: integrityToken }),
    },
  );
  if (!response.ok) throw new Error(`Play Integrity rejected token: ${response.status}`);
  return response.json<IntegrityVerdict>();
}

export async function getProductPurchase(
  serviceAccountJson: string,
  packageName: string,
  purchaseToken: string,
): Promise<ProductPurchaseV2> {
  const accessToken = await googleAccessToken(serviceAccountJson);
  const response = await fetch(
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${
      encodeURIComponent(packageName)
    }/purchases/productsv2/tokens/${encodeURIComponent(purchaseToken)}`,
    { headers: { authorization: `Bearer ${accessToken}` } },
  );
  if (!response.ok) throw new Error(`Google Play purchase verification failed: ${response.status}`);
  return response.json<ProductPurchaseV2>();
}

export async function acknowledgeProduct(
  serviceAccountJson: string,
  packageName: string,
  productId: string,
  purchaseToken: string,
): Promise<void> {
  await productMutation(
    serviceAccountJson,
    packageName,
    productId,
    purchaseToken,
    'acknowledge',
    '{}',
  );
}

export async function consumeProduct(
  serviceAccountJson: string,
  packageName: string,
  productId: string,
  purchaseToken: string,
): Promise<void> {
  await productMutation(
    serviceAccountJson,
    packageName,
    productId,
    purchaseToken,
    'consume',
  );
}

async function productMutation(
  serviceAccountJson: string,
  packageName: string,
  productId: string,
  purchaseToken: string,
  action: 'acknowledge' | 'consume',
  body?: string,
): Promise<void> {
  const accessToken = await googleAccessToken(serviceAccountJson);
  const response = await fetch(
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${
      encodeURIComponent(packageName)
    }/purchases/products/${encodeURIComponent(productId)}/tokens/${
      encodeURIComponent(purchaseToken)
    }:${action}`,
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${accessToken}`,
        'content-type': 'application/json',
      },
      body,
    },
  );
  if (!response.ok) throw new Error(`Google Play ${action} failed: ${response.status}`);
}

async function googleAccessToken(serviceAccountJson: string): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt - 60_000 > Date.now()) {
    return cachedAccessToken.token;
  }
  const account = JSON.parse(serviceAccountJson) as Partial<ServiceAccount>;
  if (!account.client_email || !account.private_key) {
    throw new Error('Google service account secret is incomplete');
  }
  const nowSeconds = Math.floor(Date.now() / 1000);
  const assertion = await signJwt(
    account.private_key,
    { alg: 'RS256', typ: 'JWT' },
    {
      iss: account.client_email,
      scope: GOOGLE_SCOPES,
      aud: GOOGLE_TOKEN_URL,
      iat: nowSeconds,
      exp: nowSeconds + 3600,
    },
  );
  const response = await fetch(GOOGLE_TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });
  if (!response.ok) throw new Error(`Google OAuth failed: ${response.status}`);
  const payload = await response.json<{ access_token?: string; expires_in?: number }>();
  if (!payload.access_token) throw new Error('Google OAuth returned no access token');
  cachedAccessToken = {
    token: payload.access_token,
    expiresAt: Date.now() + (payload.expires_in ?? 3600) * 1000,
  };
  return payload.access_token;
}

async function signJwt(
  privateKeyPem: string,
  header: Record<string, unknown>,
  claims: Record<string, unknown>,
): Promise<string> {
  const unsigned = `${base64UrlJson(header)}.${base64UrlJson(claims)}`;
  const key = await crypto.subtle.importKey(
    'pkcs8',
    pemToArrayBuffer(privateKeyPem),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    key,
    new TextEncoder().encode(unsigned),
  );
  return `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
}

function base64UrlJson(value: Record<string, unknown>): string {
  return base64UrlBytes(new TextEncoder().encode(JSON.stringify(value)));
}

function base64UrlBytes(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const encoded = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, '')
    .replace(/-----END PRIVATE KEY-----/g, '')
    .replace(/\s/g, '');
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}
