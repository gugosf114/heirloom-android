/**
 * Heirloom restoration pipeline orchestrator.
 *
 * Two endpoints, same pipeline:
 *   POST /restore         multipart { image }  -> single JSON when done
 *   POST /restore-stream  multipart { image }  -> NDJSON stream of stage events
 *
 * /restore is what the Android app calls in v1. /restore-stream is for
 * smoke tests and (later) a richer client progress UI.
 *
 * Pipeline (mirrors README.md):
 *   1. Bringing-Old-Photos-Back-to-Life  (with_scratch=true)
 *   2. CodeFormer (fidelity 0.7)
 *   3. Real-ESRGAN (scale=2)
 *   4. AdaFace cosine sim (input vs Real-ESRGAN output) -- gracefully
 *      skipped if no AdaFace SHA is pinned
 *   5. DDColor (model_size=large) -- only when input is detected B&W
 *
 * AdaFace below threshold returns the result with identity_warning=true.
 * Silent pass is the worst outcome — users need to know.
 */

import { runReplicate, asUrl, asSimilarity, ReplicateConfig } from './replicate';
import { isGrayscale } from './saturation';

interface Env {
  REPLICATE_API_TOKEN: string;
  IDENTITY_THRESHOLD: string;
  CODEFORMER_FIDELITY: string;
  GRAYSCALE_THRESHOLD: string;
  BOPB_VERSION: string;
  CODEFORMER_VERSION: string;
  ESRGAN_VERSION: string;
  ADAFACE_VERSION: string;
  DDCOLOR_VERSION: string;
}

interface PipelineResult {
  restored_url: string;
  cosine_similarity: number | null;
  identity_warning: boolean;
  was_colorized: boolean;
  adaface_skipped: boolean;
}

type StageEvent =
  | { kind: 'stage_start'; stage: string; t_ms: number }
  | { kind: 'stage_done'; stage: string; t_ms: number; output_url?: string; extra?: Record<string, unknown> }
  | { kind: 'stage_skipped'; stage: string; t_ms: number; reason: string }
  | { kind: 'final'; t_ms: number; result: PipelineResult }
  | { kind: 'error'; t_ms: number; message: string };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === '/health' && request.method === 'GET') {
      return jsonResponse({ ok: true });
    }

    if (request.method !== 'POST') {
      return new Response('Method not allowed', { status: 405 });
    }

    if (url.pathname !== '/restore' && url.pathname !== '/restore-stream') {
      return new Response('Not found', { status: 404 });
    }

    if (!env.REPLICATE_API_TOKEN) {
      return jsonResponse({ error: 'Worker missing REPLICATE_API_TOKEN' }, 500);
    }

    let bytes: Uint8Array;
    try {
      const formData = await request.formData();
      const image = formData.get('image');
      if (!(image instanceof File) && !(image instanceof Blob)) {
        return jsonResponse({ error: 'image field is required' }, 400);
      }
      bytes = new Uint8Array(await image.arrayBuffer());
      if (bytes.byteLength === 0) {
        return jsonResponse({ error: 'image is empty' }, 400);
      }
      if (bytes.byteLength > 5 * 1024 * 1024) {
        return jsonResponse({ error: 'image too large; max 5MB for v1' }, 413);
      }
    } catch (err) {
      return jsonResponse({ error: err instanceof Error ? err.message : 'invalid form' }, 400);
    }

    if (url.pathname === '/restore-stream') {
      return runStreamingPipeline(bytes, env);
    }
    return runBufferedPipeline(bytes, env);
  },
};

async function runBufferedPipeline(bytes: Uint8Array, env: Env): Promise<Response> {
  try {
    let result: PipelineResult | null = null;
    for await (const event of pipelineEvents(bytes, env)) {
      if (event.kind === 'final') result = event.result;
      if (event.kind === 'error') {
        return jsonResponse({ error: event.message }, 500);
      }
    }
    if (!result) return jsonResponse({ error: 'pipeline produced no result' }, 500);
    return jsonResponse(result);
  } catch (err) {
    const message = err instanceof Error ? err.message : 'unknown error';
    return jsonResponse({ error: message }, 500);
  }
}

function runStreamingPipeline(bytes: Uint8Array, env: Env): Response {
  const { readable, writable } = new TransformStream();
  const writer = writable.getWriter();
  const encoder = new TextEncoder();

  (async () => {
    try {
      for await (const event of pipelineEvents(bytes, env)) {
        await writer.write(encoder.encode(JSON.stringify(event) + '\n'));
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'unknown error';
      const event: StageEvent = { kind: 'error', t_ms: 0, message };
      await writer.write(encoder.encode(JSON.stringify(event) + '\n'));
    } finally {
      await writer.close();
    }
  })();

  return new Response(readable, {
    headers: {
      'Content-Type': 'application/x-ndjson',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

async function* pipelineEvents(bytes: Uint8Array, env: Env): AsyncGenerator<StageEvent> {
  const start = Date.now();
  const elapsed = () => Date.now() - start;
  const replicate: ReplicateConfig = { token: env.REPLICATE_API_TOKEN };
  const inputDataUrl = bytesToDataUrl(bytes, 'image/jpeg');

  // Stage 1: BOPB
  yield { kind: 'stage_start', stage: 'bopb', t_ms: elapsed() };
  const bopbStart = Date.now();
  const bopbOut = await runReplicate(replicate, env.BOPB_VERSION, {
    image: inputDataUrl,
    with_scratch: true,
    HR: false,
  });
  const bopbUrl = asUrl(bopbOut);
  yield {
    kind: 'stage_done',
    stage: 'bopb',
    t_ms: elapsed(),
    output_url: bopbUrl,
    extra: { duration_ms: Date.now() - bopbStart },
  };

  // Stage 2: CodeFormer
  yield { kind: 'stage_start', stage: 'codeformer', t_ms: elapsed() };
  const cfStart = Date.now();
  const fidelity = parseFloat(env.CODEFORMER_FIDELITY) || 0.7;
  const codeformerOut = await runReplicate(replicate, env.CODEFORMER_VERSION, {
    image: bopbUrl,
    codeformer_fidelity: fidelity,
    background_enhance: true,
    face_upsample: true,
    upscale: 2,
  });
  const codeformerUrl = asUrl(codeformerOut);
  yield {
    kind: 'stage_done',
    stage: 'codeformer',
    t_ms: elapsed(),
    output_url: codeformerUrl,
    extra: { duration_ms: Date.now() - cfStart, fidelity },
  };

  // Stage 3: Real-ESRGAN
  yield { kind: 'stage_start', stage: 'esrgan', t_ms: elapsed() };
  const esrStart = Date.now();
  const esrganOut = await runReplicate(replicate, env.ESRGAN_VERSION, {
    image: codeformerUrl,
    scale: 2,
    face_enhance: false,
  });
  const restoredUrl = asUrl(esrganOut);
  yield {
    kind: 'stage_done',
    stage: 'esrgan',
    t_ms: elapsed(),
    output_url: restoredUrl,
    extra: { duration_ms: Date.now() - esrStart },
  };

  // Stage 4: AdaFace gate
  const adafacePinned =
    env.ADAFACE_VERSION &&
    !env.ADAFACE_VERSION.startsWith('PLACEHOLDER') &&
    /^[a-f0-9]{64}$/.test(env.ADAFACE_VERSION);
  const threshold = parseFloat(env.IDENTITY_THRESHOLD) || 0.6;
  let cosineSimilarity: number | null = null;
  let identityWarning = false;
  let adafaceSkipped = false;

  if (!adafacePinned) {
    adafaceSkipped = true;
    yield {
      kind: 'stage_skipped',
      stage: 'adaface',
      t_ms: elapsed(),
      reason: 'No AdaFace SHA pinned. Identity gate disabled — caller should treat result as unverified.',
    };
  } else {
    yield { kind: 'stage_start', stage: 'adaface', t_ms: elapsed() };
    const afStart = Date.now();
    try {
      const adafaceOut = await runReplicate(replicate, env.ADAFACE_VERSION, {
        image1: inputDataUrl,
        image2: restoredUrl,
      });
      cosineSimilarity = asSimilarity(adafaceOut);
      identityWarning = cosineSimilarity < threshold;
      yield {
        kind: 'stage_done',
        stage: 'adaface',
        t_ms: elapsed(),
        extra: {
          duration_ms: Date.now() - afStart,
          cosine_similarity: cosineSimilarity,
          threshold,
          identity_warning: identityWarning,
        },
      };
    } catch (err) {
      cosineSimilarity = 0.0;
      identityWarning = true;
      yield {
        kind: 'stage_done',
        stage: 'adaface',
        t_ms: elapsed(),
        extra: {
          duration_ms: Date.now() - afStart,
          error: err instanceof Error ? err.message : 'unknown',
          identity_warning: true,
        },
      };
    }
  }

  // Stage 5: DDColor (B&W only)
  yield { kind: 'stage_start', stage: 'colorize_check', t_ms: elapsed() };
  const grayscaleThreshold = parseFloat(env.GRAYSCALE_THRESHOLD) || 0.05;
  const inputIsGrayscale = isGrayscale(bytes, grayscaleThreshold);
  yield {
    kind: 'stage_done',
    stage: 'colorize_check',
    t_ms: elapsed(),
    extra: { is_grayscale: inputIsGrayscale, threshold: grayscaleThreshold },
  };

  let finalUrl = restoredUrl;
  let wasColorized = false;
  if (inputIsGrayscale) {
    yield { kind: 'stage_start', stage: 'ddcolor', t_ms: elapsed() };
    const ddStart = Date.now();
    const ddcolorOut = await runReplicate(replicate, env.DDCOLOR_VERSION, {
      image: restoredUrl,
      model_size: 'large',
    });
    finalUrl = asUrl(ddcolorOut);
    wasColorized = true;
    yield {
      kind: 'stage_done',
      stage: 'ddcolor',
      t_ms: elapsed(),
      output_url: finalUrl,
      extra: { duration_ms: Date.now() - ddStart },
    };
  } else {
    yield {
      kind: 'stage_skipped',
      stage: 'ddcolor',
      t_ms: elapsed(),
      reason: 'Input is color; colorization skipped.',
    };
  }

  yield {
    kind: 'final',
    t_ms: elapsed(),
    result: {
      restored_url: finalUrl,
      cosine_similarity: cosineSimilarity,
      identity_warning: identityWarning,
      was_colorized: wasColorized,
      adaface_skipped: adafaceSkipped,
    },
  };
}

function bytesToDataUrl(bytes: Uint8Array, mime: string): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return `data:${mime};base64,${btoa(binary)}`;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
