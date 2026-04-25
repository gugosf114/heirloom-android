/**
 * Heirloom restoration pipeline orchestrator.
 *
 * POST /restore  multipart/form-data { image }
 *   ->  { restored_url, cosine_similarity, identity_warning, was_colorized }
 *
 * Pipeline (mirrors README.md):
 *   1. Bringing-Old-Photos-Back-to-Life  (scratch/tear repair)
 *   2. CodeFormer (fidelity 0.7)         (face restoration)
 *   3. Real-ESRGAN                       (upscale + denoise)
 *   4. AdaFace cosine sim (input vs out) (identity gate)
 *   5. DDColor                           (colorize, B&W only)
 *
 * The AdaFace check compares the ORIGINAL input against the post-ESRGAN
 * output (before colorization). If similarity drops below threshold, we
 * still return the result — but flagged. Silent failure is the worst
 * outcome here; users need to know the restoration drifted.
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

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === '/health' && request.method === 'GET') {
      return jsonResponse({ ok: true });
    }

    if (url.pathname !== '/restore') {
      return new Response('Not found', { status: 404 });
    }

    if (request.method !== 'POST') {
      return new Response('Method not allowed', { status: 405 });
    }

    if (!env.REPLICATE_API_TOKEN) {
      return jsonResponse({ error: 'Worker missing REPLICATE_API_TOKEN' }, 500);
    }

    try {
      const formData = await request.formData();
      const image = formData.get('image');
      if (!(image instanceof File) && !(image instanceof Blob)) {
        return jsonResponse({ error: 'image field is required' }, 400);
      }

      const bytes = new Uint8Array(await image.arrayBuffer());
      if (bytes.byteLength === 0) {
        return jsonResponse({ error: 'image is empty' }, 400);
      }
      if (bytes.byteLength > 5 * 1024 * 1024) {
        // Replicate data: URL inputs are advertised up to 5 MB. Beyond that
        // we'd need to push the bytes to R2 first and pass the public URL.
        return jsonResponse({ error: 'image too large; max 5MB for v1' }, 413);
      }

      const replicate: ReplicateConfig = { token: env.REPLICATE_API_TOKEN };
      const inputDataUrl = bytesToDataUrl(bytes, 'image/jpeg');

      // Stage 1: scratch and tear repair
      const bopbOut = await runReplicate(replicate, env.BOPB_VERSION, {
        image: inputDataUrl,
      });
      const bopbUrl = asUrl(bopbOut);

      // Stage 2: face restoration with deliberate fidelity bias
      const fidelity = parseFloat(env.CODEFORMER_FIDELITY) || 0.7;
      const codeformerOut = await runReplicate(replicate, env.CODEFORMER_VERSION, {
        image: bopbUrl,
        codeformer_fidelity: fidelity,
        background_enhance: true,
        face_upsample: true,
        upscale: 2,
      });
      const codeformerUrl = asUrl(codeformerOut);

      // Stage 3: upscale + denoise the full image
      const esrganOut = await runReplicate(replicate, env.ESRGAN_VERSION, {
        image: codeformerUrl,
        scale: 2,
        face_enhance: false, // CodeFormer already did faces; don't double-process
      });
      const restoredUrl = asUrl(esrganOut);

      // Stage 4: identity gate
      const threshold = parseFloat(env.IDENTITY_THRESHOLD) || 0.6;
      let cosineSimilarity = 1.0;
      let identityWarning = false;
      try {
        const adafaceOut = await runReplicate(replicate, env.ADAFACE_VERSION, {
          image1: inputDataUrl,
          image2: restoredUrl,
        });
        cosineSimilarity = asSimilarity(adafaceOut);
        identityWarning = cosineSimilarity < threshold;
      } catch (err) {
        // AdaFace failure (e.g., no face detected in input) is not a hard
        // failure — we surface "couldn't verify identity" to the client by
        // setting the warning flag. Better than silently passing.
        cosineSimilarity = 0.0;
        identityWarning = true;
        console.warn('AdaFace check failed:', err);
      }

      // Stage 5: colorize only if input was B&W
      const grayscaleThreshold = parseFloat(env.GRAYSCALE_THRESHOLD) || 0.05;
      let finalUrl = restoredUrl;
      let wasColorized = false;
      if (isGrayscale(bytes, grayscaleThreshold)) {
        const ddcolorOut = await runReplicate(replicate, env.DDCOLOR_VERSION, {
          image: restoredUrl,
        });
        finalUrl = asUrl(ddcolorOut);
        wasColorized = true;
      }

      return jsonResponse({
        restored_url: finalUrl,
        cosine_similarity: cosineSimilarity,
        identity_warning: identityWarning,
        was_colorized: wasColorized,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unknown error';
      console.error('Restore pipeline failed:', err);
      return jsonResponse({ error: message }, 500);
    }
  },
};

function bytesToDataUrl(bytes: Uint8Array, mime: string): string {
  // btoa wants binary string; manually build it without spreading the array
  // (spread would blow the call-stack limit on large images).
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
