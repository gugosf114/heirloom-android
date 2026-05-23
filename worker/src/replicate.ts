/**
 * Thin typed wrapper around the Replicate prediction API. We poll because
 * webhooks require persisting state across requests; polling keeps the whole
 * lifecycle inside a single Worker invocation.
 *
 * https://replicate.com/docs/reference/http
 */

export interface ReplicateConfig {
  token: string;
}

interface PredictionResponse {
  id: string;
  status: 'starting' | 'processing' | 'succeeded' | 'failed' | 'canceled';
  output: unknown;
  error?: string | null;
  logs?: string | null;
}

const REPLICATE_BASE = 'https://api.replicate.com/v1';
// Poll every 5s, not 1.5s. Each poll is a Cloudflare subrequest, and Workers
// Free is capped at 50 subrequests per request. AdaFace cold starts can take
// 90s — at 1.5s polling that alone burns 42 subrequests and starves the
// last stage. 5s gives us ~18 polls for a 90s wait, leaving room for all
// five stages plus retry-on-429 attempts.
const POLL_INTERVAL_MS = 5000;
const POLL_TIMEOUT_MS = 180_000;

/**
 * Run a Replicate model and return its output. Throws on failure or timeout —
 * caller decides how to surface that to the client.
 */
export async function runReplicate(
  config: ReplicateConfig,
  version: string,
  input: Record<string, unknown>,
): Promise<unknown> {
  let prediction = await createWithRateLimitRetry(config, version, input);
  const startedAt = Date.now();

  while (prediction.status !== 'succeeded' && prediction.status !== 'failed' && prediction.status !== 'canceled') {
    if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
      throw new Error(`Replicate poll timeout for ${prediction.id}`);
    }
    await sleep(POLL_INTERVAL_MS);
    const poll = await fetch(`${REPLICATE_BASE}/predictions/${prediction.id}`, {
      headers: { Authorization: `Token ${config.token}` },
    });
    if (!poll.ok) {
      throw new Error(`Replicate poll failed (${poll.status})`);
    }
    prediction = await poll.json();
  }

  if (prediction.status !== 'succeeded') {
    throw new Error(`Replicate ${prediction.status}: ${prediction.error ?? 'unknown'}`);
  }
  return prediction.output;
}

/**
 * Create a prediction, honoring Replicate's 429 retry_after on rate-limit
 * pushback. New Replicate accounts (< $5 lifetime spend) get a burst-of-1
 * rate limit; any production worker also wants this for transient bursts.
 */
async function createWithRateLimitRetry(
  config: ReplicateConfig,
  version: string,
  input: Record<string, unknown>,
): Promise<PredictionResponse> {
  const MAX_RETRIES = 5;
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    const resp = await fetch(`${REPLICATE_BASE}/predictions`, {
      method: 'POST',
      headers: {
        Authorization: `Token ${config.token}`,
        'Content-Type': 'application/json',
        // 30s synchronous wait — most Replicate models finish inside this window
        // on warm boots. Cold starts fall back to the polling loop below.
        Prefer: 'wait=30',
      },
      body: JSON.stringify({ version, input }),
    });

    if (resp.ok) return resp.json();

    const body = await resp.text();
    if (resp.status !== 429 || attempt === MAX_RETRIES) {
      throw new Error(`Replicate create failed (${resp.status}): ${body}`);
    }

    let waitSec = 10;
    const header = resp.headers.get('retry-after');
    if (header) {
      const n = parseInt(header, 10);
      if (!Number.isNaN(n)) waitSec = n;
    } else {
      try {
        const parsed = JSON.parse(body) as { retry_after?: number };
        if (typeof parsed.retry_after === 'number') waitSec = parsed.retry_after;
      } catch {
        // fall through to default 10s
      }
    }
    // 1s buffer so we don't land exactly on the reset boundary.
    await sleep((waitSec + 1) * 1000);
  }
  throw new Error('Replicate create: exhausted retry budget');
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/** Normalize a Replicate output to a single URL (some models return arrays). */
export function asUrl(output: unknown): string {
  if (typeof output === 'string') return output;
  if (Array.isArray(output) && output.length > 0 && typeof output[0] === 'string') return output[0];
  if (typeof output === 'object' && output !== null) {
    const obj = output as Record<string, unknown>;
    if (typeof obj.image === 'string') return obj.image;
    if (typeof obj.output === 'string') return obj.output;
  }
  throw new Error(`Unexpected Replicate output shape: ${JSON.stringify(output).slice(0, 200)}`);
}

/**
 * Pull a numeric similarity score out of an AdaFace-style response. Models
 * differ in shape — some return a number, some return {similarity: x},
 * some return an array. Best-effort extraction with a clear failure mode.
 */
export function asSimilarity(output: unknown): number {
  if (typeof output === 'number') return output;
  if (typeof output === 'string') {
    const parsed = parseFloat(output);
    if (!Number.isNaN(parsed)) return parsed;
  }
  if (Array.isArray(output) && output.length > 0) {
    return asSimilarity(output[0]);
  }
  if (typeof output === 'object' && output !== null) {
    const obj = output as Record<string, unknown>;
    for (const key of ['cosine_similarity', 'similarity', 'score', 'cos_sim']) {
      if (key in obj) return asSimilarity(obj[key]);
    }
  }
  throw new Error(`Could not extract similarity from: ${JSON.stringify(output).slice(0, 200)}`);
}
