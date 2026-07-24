/**
 * Heirloom edge gateway.
 *
 * Android -> this Worker -> the self-hosted Cloud Run GPU pipeline.
 * Google Play verifies purchases; D1 owns trial, credit, and restoration state.
 */

import {
  acknowledgeProduct,
  consumeProduct,
  decodeIntegrityToken,
  getProductPurchase,
  parseVerifiedPurchase,
  validateIntegrityVerdict,
} from './googlePlay';
import {
  DuplicateRestorationError,
  NoCreditsError,
  authenticateSession,
  balanceForClient,
  cancelPurchase,
  completeRestoration,
  ensureClient,
  issueSession,
  markConsumePending,
  markPurchaseAcknowledged,
  markPurchaseConsumed,
  paidCandidates,
  purchaseForTokenHash,
  refundRestoration,
  registerPurchase,
  reserveRestoration,
  sha256Hex,
  type Reservation,
} from './ledger';

export interface Env {
  APP_SHARED_SECRET?: string;
  SMOKE_TEST_SECRET?: string;
  PIPELINE_BASE_URL: string;
  PIPELINE_SHARED_SECRET?: string;
  GOOGLE_SERVICE_ACCOUNT_JSON?: string;
  PLAY_PACKAGE_NAME?: string;
  REQUIRE_PLAY_INTEGRITY?: string;
  MIN_ANDROID_VERSION_CODE?: string;
  REPORTS: KVNamespace;
  BILLING: D1Database;
}

interface BillingSyncPayload {
  client_id?: unknown;
  request_id?: unknown;
  integrity_token?: unknown;
  purchases?: unknown;
}

interface ClientPurchase {
  product_id: string;
  purchase_token: string;
}

interface ReportPayload {
  reason?: unknown;
  details?: unknown;
  cosine_similarity?: unknown;
  identity_warning?: unknown;
  identity_unverified?: unknown;
  was_colorized?: unknown;
  app_version?: unknown;
}

const PACKAGE_NAME = 'com.wimlabs.heirloom';
const REPORT_REASONS = new Set([
  'wrong_person',
  'distorted_face',
  'offensive_or_unexpected',
  'poor_quality',
  'other',
]);
const REPORT_TTL_SECONDS = 90 * 24 * 60 * 60;
const CLIENT_ID_PATTERN = /^[a-f0-9]{64}$/;
const REQUEST_ID_PATTERN = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === 'GET' && url.pathname === '/health') {
      return pipelineHealth(env);
    }
    if (request.method !== 'POST') {
      return jsonResponse({ error: 'not found' }, 404);
    }
    if (url.pathname === '/billing/sync') {
      return syncBilling(request, env);
    }
    if (url.pathname === '/restore' || url.pathname === '/restore-stream') {
      return authorizeAndRestore(request, url, env, ctx);
    }
    if (url.pathname === '/report') {
      const clientId = await authenticatedClient(request, env);
      if (!clientId) return jsonResponse({ error: 'unauthorized' }, 401);
      return receiveReport(request, env);
    }
    return jsonResponse({ error: 'not found' }, 404);
  },
};

async function pipelineHealth(env: Env): Promise<Response> {
  try {
    const upstream = await fetch(new URL('/health', normalizedPipelineBase(env)));
    return jsonResponse(
      {
        ok: upstream.ok,
        backend: 'cloud-run',
        billing: 'server-verified-credits',
      },
      upstream.ok ? 200 : 503,
    );
  } catch {
    return jsonResponse({ ok: false, backend: 'cloud-run' }, 503);
  }
}

async function syncBilling(request: Request, env: Env): Promise<Response> {
  let raw: BillingSyncPayload;
  try {
    raw = await request.json<BillingSyncPayload>();
  } catch {
    return jsonResponse({ error: 'invalid billing request' }, 400);
  }
  const parsed = parseBillingSync(raw);
  if (!parsed.ok) return jsonResponse({ error: parsed.error }, 400);

  const packageName = env.PLAY_PACKAGE_NAME?.trim() || PACKAGE_NAME;
  if (requiresIntegrity(env)) {
    if (!env.GOOGLE_SERVICE_ACCOUNT_JSON) {
      return jsonResponse({ error: 'billing verification unavailable' }, 503);
    }
    try {
      const verdict = await decodeIntegrityToken(
        env.GOOGLE_SERVICE_ACCOUNT_JSON,
        packageName,
        parsed.value.integrityToken,
      );
      const expectedHash = await integrityRequestHash(
        '/billing/sync',
        parsed.value.clientId,
        parsed.value.requestId,
        canonicalPurchaseBinding(parsed.value.purchases),
      );
      const valid = validateIntegrityVerdict(verdict, {
        packageName,
        requestHash: expectedHash,
        minimumVersionCode: Number(env.MIN_ANDROID_VERSION_CODE ?? '6'),
      });
      if (!valid) return jsonResponse({ error: 'unlicensed or modified app' }, 403);
    } catch {
      return jsonResponse({ error: 'app verification failed' }, 403);
    }
  } else if (!appIsAuthorized(request, env)) {
    return jsonResponse({ error: 'unauthorized development build' }, 401);
  }

  await ensureClient(env.BILLING, parsed.value.clientId);
  for (const purchase of parsed.value.purchases) {
    await syncPurchase(env, packageName, parsed.value.clientId, purchase);
  }
  const session = await issueSession(env.BILLING, parsed.value.clientId);
  const balance = await balanceForClient(env.BILLING, parsed.value.clientId);
  return jsonResponse({
    session_token: session.token,
    session_expires_at: session.expiresAt,
    free_remaining: balance.freeRemaining,
    paid_remaining: balance.paidRemaining,
    total_remaining: balance.totalRemaining,
  });
}

async function syncPurchase(
  env: Env,
  packageName: string,
  clientId: string,
  purchase: ClientPurchase,
): Promise<void> {
  if (!env.GOOGLE_SERVICE_ACCOUNT_JSON) return;
  const tokenHash = await sha256Hex(purchase.purchase_token);
  let verified;
  try {
    verified = parseVerifiedPurchase(
      await getProductPurchase(
        env.GOOGLE_SERVICE_ACCOUNT_JSON,
        packageName,
        purchase.purchase_token,
      ),
    );
  } catch {
    return;
  }
  if (!verified || verified.productId !== purchase.product_id || verified.consumed) {
    await cancelPurchase(env.BILLING, tokenHash);
    return;
  }
  await registerPurchase(
    env.BILLING,
    clientId,
    tokenHash,
    purchase.purchase_token,
    verified.productId,
    verified.credits,
    verified.orderId,
    verified.regionCode,
    verified.acknowledged,
  );
  const registered = await purchaseForTokenHash(env.BILLING, tokenHash);
  if (registered?.creditsRemaining === 0) {
    try {
      await consumeProduct(
        env.GOOGLE_SERVICE_ACCOUNT_JSON,
        packageName,
        registered.productId,
        registered.purchaseToken,
      );
      await markPurchaseConsumed(env.BILLING, tokenHash);
    } catch {
      await markConsumePending(env.BILLING, tokenHash);
    }
    return;
  }
  if (!verified.acknowledged) {
    try {
      await acknowledgeProduct(
        env.GOOGLE_SERVICE_ACCOUNT_JSON,
        packageName,
        verified.productId,
        purchase.purchase_token,
      );
      await markPurchaseAcknowledged(env.BILLING, tokenHash);
    } catch {
      // The next sync retries. Purchase state is rechecked before paid use.
    }
  }
}

async function authorizeAndRestore(
  request: Request,
  requestUrl: URL,
  env: Env,
  ctx: ExecutionContext,
): Promise<Response> {
  if (smokeIsAuthorized(request, env)) {
    return proxySmokeRestoration(request, requestUrl, env);
  }
  const clientId = await authenticatedClient(request, env);
  if (!clientId) return jsonResponse({ error: 'billing session expired' }, 401);
  const requestId = request.headers.get('x-restoration-id')?.trim() ?? '';
  if (!REQUEST_ID_PATTERN.test(requestId)) {
    return jsonResponse({ error: 'invalid restoration id' }, 400);
  }

  let reservation: Reservation;
  try {
    const currentBalance = await balanceForClient(env.BILLING, clientId);
    let validPaidTokenHashes: string[] = [];
    if (currentBalance.freeRemaining <= 0) {
      validPaidTokenHashes = await verifyPaidCandidates(env, clientId);
    }
    reservation = await reserveRestoration(
      env.BILLING,
      clientId,
      requestId,
      validPaidTokenHashes,
    );
  } catch (error) {
    if (error instanceof DuplicateRestorationError || errorMessage(error).includes('UNIQUE')) {
      return jsonResponse({ error: 'restoration request already used' }, 409);
    }
    if (error instanceof NoCreditsError) {
      const balance = await balanceForClient(env.BILLING, clientId);
      return jsonResponse(
        {
          error: 'no restoration credits remaining',
          free_remaining: balance.freeRemaining,
          paid_remaining: balance.paidRemaining,
          total_remaining: balance.totalRemaining,
        },
        402,
      );
    }
    throw error;
  }
  return proxyRestoration(request, requestUrl, env, ctx, reservation);
}

async function verifyPaidCandidates(env: Env, clientId: string): Promise<string[]> {
  if (!env.GOOGLE_SERVICE_ACCOUNT_JSON) return [];
  const packageName = env.PLAY_PACKAGE_NAME?.trim() || PACKAGE_NAME;
  const valid: string[] = [];
  for (const candidate of await paidCandidates(env.BILLING, clientId)) {
    try {
      const verified = parseVerifiedPurchase(
        await getProductPurchase(
          env.GOOGLE_SERVICE_ACCOUNT_JSON,
          packageName,
          candidate.purchaseToken,
        ),
      );
      if (
        !verified ||
        verified.productId !== candidate.productId ||
        verified.consumed
      ) {
        await cancelPurchase(env.BILLING, candidate.tokenHash);
        continue;
      }
      valid.push(candidate.tokenHash);
      if (!verified.acknowledged) {
        await acknowledgeProduct(
          env.GOOGLE_SERVICE_ACCOUNT_JSON,
          packageName,
          candidate.productId,
          candidate.purchaseToken,
        );
        await markPurchaseAcknowledged(env.BILLING, candidate.tokenHash);
      }
    } catch {
      // Fail closed: an unverifiable purchase cannot spend a paid credit.
    }
  }
  return valid;
}

async function proxyRestoration(
  request: Request,
  requestUrl: URL,
  env: Env,
  ctx: ExecutionContext,
  reservation: Reservation,
): Promise<Response> {
  let upstream: Response;
  try {
    upstream = await fetchPipeline(request, requestUrl, env);
  } catch {
    await refundRestoration(env.BILLING, reservation.requestId);
    return jsonResponse({ error: 'restoration service unavailable' }, 503);
  }
  if (!upstream.ok || !upstream.body) {
    await refundRestoration(env.BILLING, reservation.requestId);
    return copyUpstreamResponse(upstream);
  }

  const balance = await balanceForClient(env.BILLING, reservation.clientId);
  if (requestUrl.pathname === '/restore') {
    const inspection = upstream.clone();
    ctx.waitUntil(
      inspection
        .json<{ restored_url?: string }>()
        .then(result =>
          result.restored_url
            ? finishSuccessfulRestore(env, reservation)
            : refundRestoration(env.BILLING, reservation.requestId),
        )
        .catch(() => refundRestoration(env.BILLING, reservation.requestId)),
    );
    return copyUpstreamResponse(upstream, balance.totalRemaining);
  }

  const monitored = monitorRestorationStream(
    upstream.body,
    () => finishSuccessfulRestore(env, reservation),
    () => refundRestoration(env.BILLING, reservation.requestId),
    ctx,
  );
  return copyUpstreamResponse(
    new Response(monitored, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: upstream.headers,
    }),
    balance.totalRemaining,
  );
}

async function finishSuccessfulRestore(env: Env, reservation: Reservation): Promise<void> {
  const completed = await completeRestoration(env.BILLING, reservation.requestId);
  if (!completed?.tokenHash || !env.GOOGLE_SERVICE_ACCOUNT_JSON) return;
  const purchase = await purchaseForTokenHash(env.BILLING, completed.tokenHash);
  if (!purchase || purchase.creditsRemaining !== 0) return;
  const packageName = env.PLAY_PACKAGE_NAME?.trim() || PACKAGE_NAME;
  try {
    await consumeProduct(
      env.GOOGLE_SERVICE_ACCOUNT_JSON,
      packageName,
      purchase.productId,
      purchase.purchaseToken,
    );
    await markPurchaseConsumed(env.BILLING, completed.tokenHash);
  } catch {
    await markConsumePending(env.BILLING, completed.tokenHash);
  }
}

function monitorRestorationStream(
  body: ReadableStream<Uint8Array>,
  onSuccess: () => Promise<void>,
  onFailure: () => Promise<void>,
  ctx: ExecutionContext,
): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalSeen = false;
  let settled = false;

  const settle = (success: boolean): void => {
    if (settled) return;
    settled = true;
    ctx.waitUntil(success ? onSuccess() : onFailure());
  };

  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const next = await reader.read();
        if (next.done) {
          buffer += decoder.decode();
          if (buffer.trim()) finalSeen ||= lineIsFinal(buffer);
          settle(finalSeen);
          controller.close();
          return;
        }
        controller.enqueue(next.value);
        buffer += decoder.decode(next.value, { stream: true });
        let newline = buffer.indexOf('\n');
        while (newline >= 0) {
          const line = buffer.slice(0, newline).trim();
          buffer = buffer.slice(newline + 1);
          if (line) finalSeen ||= lineIsFinal(line);
          newline = buffer.indexOf('\n');
        }
      } catch (error) {
        settle(false);
        controller.error(error);
      }
    },
    async cancel(reason) {
      settle(finalSeen);
      await reader.cancel(reason);
    },
  });
}

function lineIsFinal(line: string): boolean {
  try {
    return (JSON.parse(line) as { kind?: string }).kind === 'final';
  } catch {
    return false;
  }
}

async function proxySmokeRestoration(
  request: Request,
  requestUrl: URL,
  env: Env,
): Promise<Response> {
  try {
    return copyUpstreamResponse(await fetchPipeline(request, requestUrl, env));
  } catch {
    return jsonResponse({ error: 'restoration service unavailable' }, 503);
  }
}

async function fetchPipeline(
  request: Request,
  requestUrl: URL,
  env: Env,
): Promise<Response> {
  const upstreamUrl = new URL(requestUrl.pathname + requestUrl.search, normalizedPipelineBase(env));
  const headers = new Headers();
  const contentType = request.headers.get('content-type');
  if (contentType) headers.set('content-type', contentType);
  if (env.PIPELINE_SHARED_SECRET) headers.set('x-pipeline-key', env.PIPELINE_SHARED_SECRET);
  return fetch(upstreamUrl, {
    method: 'POST',
    headers,
    body: request.body,
    redirect: 'manual',
  });
}

function copyUpstreamResponse(upstream: Response, creditsRemaining?: number): Response {
  const responseHeaders = new Headers(upstream.headers);
  responseHeaders.set('cache-control', 'no-store');
  responseHeaders.set('x-content-type-options', 'nosniff');
  if (creditsRemaining !== undefined) {
    responseHeaders.set('x-heirloom-credits-remaining', String(creditsRemaining));
  }
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: responseHeaders,
  });
}

async function receiveReport(request: Request, env: Env): Promise<Response> {
  let raw: ReportPayload;
  try {
    raw = await request.json<ReportPayload>();
  } catch {
    return jsonResponse({ error: 'invalid report' }, 400);
  }
  const validated = validateReportPayload(raw);
  if (!validated.ok) return jsonResponse({ error: validated.error }, 400);

  const createdAt = new Date().toISOString();
  const key = `report:${createdAt}:${crypto.randomUUID()}`;
  await env.REPORTS.put(
    key,
    JSON.stringify({ created_at: createdAt, ...validated.value }),
    { expirationTtl: REPORT_TTL_SECONDS },
  );
  return jsonResponse({ accepted: true }, 202);
}

async function authenticatedClient(request: Request, env: Env): Promise<string | null> {
  const authorization = request.headers.get('authorization') ?? '';
  if (!authorization.startsWith('Bearer ')) return null;
  return authenticateSession(env.BILLING, authorization.slice('Bearer '.length).trim());
}

function appIsAuthorized(request: Request, env: Env): boolean {
  if (!env.APP_SHARED_SECRET) return false;
  return secureEqual(request.headers.get('x-app-key') ?? '', env.APP_SHARED_SECRET);
}

function smokeIsAuthorized(request: Request, env: Env): boolean {
  if (!env.SMOKE_TEST_SECRET) return false;
  return secureEqual(request.headers.get('x-smoke-key') ?? '', env.SMOKE_TEST_SECRET);
}

function requiresIntegrity(env: Env): boolean {
  return env.REQUIRE_PLAY_INTEGRITY?.toLowerCase() !== 'false';
}

function normalizedPipelineBase(env: Env): URL {
  const value = env.PIPELINE_BASE_URL.trim();
  if (!value) throw new Error('PIPELINE_BASE_URL is not configured');
  return new URL(value.endsWith('/') ? value : `${value}/`);
}

function parseBillingSync(payload: BillingSyncPayload):
  | {
      ok: true;
      value: {
        clientId: string;
        requestId: string;
        integrityToken: string;
        purchases: ClientPurchase[];
      };
    }
  | { ok: false; error: string } {
  if (typeof payload.client_id !== 'string' || !CLIENT_ID_PATTERN.test(payload.client_id)) {
    return { ok: false, error: 'invalid client id' };
  }
  if (typeof payload.request_id !== 'string' || !REQUEST_ID_PATTERN.test(payload.request_id)) {
    return { ok: false, error: 'invalid request id' };
  }
  const integrityToken =
    typeof payload.integrity_token === 'string' ? payload.integrity_token.trim() : '';
  if (!Array.isArray(payload.purchases) || payload.purchases.length > 3) {
    return { ok: false, error: 'invalid purchases' };
  }
  const purchases: ClientPurchase[] = [];
  const seenTokens = new Set<string>();
  for (const value of payload.purchases) {
    if (!value || typeof value !== 'object') return { ok: false, error: 'invalid purchase' };
    const candidate = value as Record<string, unknown>;
    const productId = typeof candidate.product_id === 'string' ? candidate.product_id : '';
    const purchaseToken =
      typeof candidate.purchase_token === 'string' ? candidate.purchase_token : '';
    if (
      !/^heirloom_restorations_(5|20|50)_v1$/.test(productId) ||
      purchaseToken.length < 16 ||
      purchaseToken.length > 4096
    ) {
      return { ok: false, error: 'invalid purchase' };
    }
    if (!seenTokens.has(purchaseToken)) {
      purchases.push({ product_id: productId, purchase_token: purchaseToken });
      seenTokens.add(purchaseToken);
    }
  }
  return {
    ok: true,
    value: {
      clientId: payload.client_id,
      requestId: payload.request_id,
      integrityToken,
      purchases,
    },
  };
}

export async function integrityRequestHash(
  path: string,
  clientId: string,
  requestId: string,
  purchaseBinding = '',
): Promise<string> {
  const digest = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(`POST\n${path}\n${clientId}\n${requestId}\n${purchaseBinding}`),
  );
  let binary = '';
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export function canonicalPurchaseBinding(purchases: ClientPurchase[]): string {
  return purchases
    .map(purchase => ({
      purchase,
      sortKey: `${purchase.product_id}\u0000${purchase.purchase_token}`,
    }))
    .sort((left, right) => left.sortKey < right.sortKey ? -1 : left.sortKey > right.sortKey ? 1 : 0)
    .map(({ purchase }) =>
      `${purchase.product_id.length}:${purchase.product_id}` +
      `${purchase.purchase_token.length}:${purchase.purchase_token}`
    )
    .join('\n');
}

export function secureEqual(left: string, right: string): boolean {
  const max = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let i = 0; i < max; i++) {
    difference |= (left.charCodeAt(i) || 0) ^ (right.charCodeAt(i) || 0);
  }
  return difference === 0;
}

export function validateReportPayload(
  payload: ReportPayload,
):
  | { ok: true; value: Record<string, string | number | boolean | null> }
  | { ok: false; error: string } {
  if (typeof payload.reason !== 'string' || !REPORT_REASONS.has(payload.reason)) {
    return { ok: false, error: 'invalid reason' };
  }
  const details = typeof payload.details === 'string' ? payload.details.trim() : '';
  if (details.length > 1000) return { ok: false, error: 'details too long' };
  return {
    ok: true,
    value: {
      reason: payload.reason,
      details,
      cosine_similarity:
        typeof payload.cosine_similarity === 'number' ? payload.cosine_similarity : null,
      identity_warning: payload.identity_warning === true,
      identity_unverified: payload.identity_unverified === true,
      was_colorized: payload.was_colorized === true,
      app_version:
        typeof payload.app_version === 'string' ? payload.app_version.slice(0, 40) : '',
    },
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    },
  });
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
