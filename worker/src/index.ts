/**
 * Heirloom edge gateway.
 *
 * Android -> this Worker -> the fully self-hosted Cloud Run GPU pipeline.
 * The Worker owns app authentication, report intake, and response streaming.
 * It does not run restoration models and it never sends photos to Replicate.
 */

export interface Env {
  APP_SHARED_SECRET?: string;
  PIPELINE_BASE_URL: string;
  PIPELINE_SHARED_SECRET?: string;
  REPORTS: KVNamespace;
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

const REPORT_REASONS = new Set([
  'wrong_person',
  'distorted_face',
  'offensive_or_unexpected',
  'poor_quality',
  'other',
]);
const REPORT_TTL_SECONDS = 90 * 24 * 60 * 60;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === 'GET' && url.pathname === '/health') {
      return pipelineHealth(env);
    }

    if (request.method !== 'POST') {
      return jsonResponse({ error: 'not found' }, 404);
    }
    if (!appIsAuthorized(request, env)) {
      return jsonResponse({ error: 'unauthorized' }, 401);
    }

    if (url.pathname === '/restore' || url.pathname === '/restore-stream') {
      return proxyRestoration(request, url, env);
    }
    if (url.pathname === '/report') {
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
      },
      upstream.ok ? 200 : 503,
    );
  } catch {
    return jsonResponse({ ok: false, backend: 'cloud-run' }, 503);
  }
}

async function proxyRestoration(
  request: Request,
  requestUrl: URL,
  env: Env,
): Promise<Response> {
  const upstreamUrl = new URL(requestUrl.pathname + requestUrl.search, normalizedPipelineBase(env));
  const headers = new Headers();
  const contentType = request.headers.get('content-type');
  if (contentType) headers.set('content-type', contentType);
  if (env.PIPELINE_SHARED_SECRET) {
    headers.set('x-pipeline-key', env.PIPELINE_SHARED_SECRET);
  }

  try {
    const upstream = await fetch(upstreamUrl, {
      method: 'POST',
      headers,
      body: request.body,
      redirect: 'manual',
    });
    const responseHeaders = new Headers(upstream.headers);
    responseHeaders.set('cache-control', 'no-store');
    responseHeaders.set('x-content-type-options', 'nosniff');
    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
    });
  } catch {
    return jsonResponse({ error: 'restoration service unavailable' }, 503);
  }
}

async function receiveReport(request: Request, env: Env): Promise<Response> {
  let raw: ReportPayload;
  try {
    raw = await request.json<ReportPayload>();
  } catch {
    return jsonResponse({ error: 'invalid report' }, 400);
  }

  const validated = validateReportPayload(raw);
  if (!validated.ok) {
    return jsonResponse({ error: validated.error }, 400);
  }

  const createdAt = new Date().toISOString();
  const key = `report:${createdAt}:${crypto.randomUUID()}`;
  await env.REPORTS.put(
    key,
    JSON.stringify({
      created_at: createdAt,
      ...validated.value,
    }),
    { expirationTtl: REPORT_TTL_SECONDS },
  );
  return jsonResponse({ accepted: true }, 202);
}

function appIsAuthorized(request: Request, env: Env): boolean {
  if (!env.APP_SHARED_SECRET) return true;
  return secureEqual(request.headers.get('x-app-key') ?? '', env.APP_SHARED_SECRET);
}

function normalizedPipelineBase(env: Env): URL {
  const value = env.PIPELINE_BASE_URL.trim();
  if (!value) throw new Error('PIPELINE_BASE_URL is not configured');
  return new URL(value.endsWith('/') ? value : `${value}/`);
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
  if (details.length > 1000) {
    return { ok: false, error: 'details too long' };
  }

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
