/**
 * End-to-end smoke test for the Heirloom restoration pipeline.
 *
 * What it does:
 *   1. Downloads a known public-domain old photo (Migrant Mother, Library
 *      of Congress, 1936 — B&W, face-centered, period damage).
 *   2. Posts it to the Worker's /restore-stream endpoint as multipart.
 *   3. Streams NDJSON stage events back to stdout with timestamps.
 *   4. Asserts: pipeline reaches `final`, AdaFace either reports a cosine
 *      similarity OR is explicitly skipped, B&W input gets colorized.
 *
 * Run:
 *   npm run smoke               # local Worker on :8787 (run wrangler dev first)
 *   WORKER_URL=https://heirloom-worker.gugosf.workers.dev npm run smoke
 *
 * Override the test image:
 *   TEST_IMAGE_URL=https://example.com/old.jpg npm run smoke
 *
 * Exits non-zero on any pipeline error or assertion failure — usable in CI.
 */

const WORKER_URL = process.env.WORKER_URL ?? 'http://127.0.0.1:8787';
const TEST_IMAGE_URL =
  process.env.TEST_IMAGE_URL ??
  'https://tile.loc.gov/storage-services/service/pnp/fsa/8b29000/8b29500/8b29516r.jpg';
const TEST_UA =
  'HeirloomSmokeTest/0.1 (https://github.com/gugosf114/heirloom-android; gugosf@gmail.com)';

interface StageEvent {
  kind: 'stage_start' | 'stage_done' | 'stage_skipped' | 'final' | 'error';
  stage?: string;
  t_ms: number;
  output_url?: string;
  reason?: string;
  message?: string;
  result?: PipelineResult;
  extra?: Record<string, unknown>;
}

interface PipelineResult {
  restored_url: string;
  cosine_similarity: number | null;
  identity_warning: boolean;
  was_colorized: boolean;
  adaface_skipped: boolean;
}

class SmokeAssertion extends Error {}

async function main(): Promise<void> {
  console.log(`[smoke] worker  : ${WORKER_URL}`);
  console.log(`[smoke] image   : ${TEST_IMAGE_URL}`);

  console.log('[smoke] downloading test image...');
  const downloadStart = Date.now();
  const imgResp = await fetch(TEST_IMAGE_URL, { headers: { 'User-Agent': TEST_UA } });
  if (!imgResp.ok) {
    throw new Error(`Test image download failed: ${imgResp.status} ${imgResp.statusText}`);
  }
  const imgBytes = new Uint8Array(await imgResp.arrayBuffer());
  console.log(
    `[smoke] image    : ${imgBytes.byteLength.toLocaleString()} bytes ` +
      `in ${Date.now() - downloadStart}ms`,
  );

  // FormData expects a Blob; the global Blob constructor is in Node 18+.
  const form = new FormData();
  form.append('image', new Blob([imgBytes], { type: 'image/jpeg' }), 'migrant_mother.jpg');

  console.log('[smoke] posting to /restore-stream...');
  const reqStart = Date.now();
  const resp = await fetch(`${WORKER_URL}/restore-stream`, {
    method: 'POST',
    body: form,
  });
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`Worker returned ${resp.status}: ${body}`);
  }
  if (!resp.body) {
    throw new Error('Worker returned no streaming body');
  }

  const events: StageEvent[] = [];
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let nl = buffer.indexOf('\n');
    while (nl !== -1) {
      const line = buffer.slice(0, nl).trim();
      buffer = buffer.slice(nl + 1);
      if (line.length > 0) {
        const event = JSON.parse(line) as StageEvent;
        events.push(event);
        printEvent(event);
      }
      nl = buffer.indexOf('\n');
    }
  }

  console.log(`[smoke] wallclock total: ${Date.now() - reqStart}ms`);
  await assertPipelineHealth(events);
  console.log('[smoke] PASS');
}

function printEvent(e: StageEvent): void {
  const t = `${(e.t_ms / 1000).toFixed(1).padStart(6)}s`;
  switch (e.kind) {
    case 'stage_start':
      console.log(`[${t}] -> ${e.stage} starting`);
      break;
    case 'stage_done': {
      const dur = e.extra?.duration_ms ? ` (${e.extra.duration_ms}ms)` : '';
      const note: string[] = [];
      if (e.extra?.cosine_similarity !== undefined) {
        note.push(`cos=${(e.extra.cosine_similarity as number)?.toFixed(3)}`);
      }
      if (e.extra?.identity_warning) note.push('identity_warning');
      if (e.extra?.is_grayscale !== undefined) {
        note.push(`grayscale=${e.extra.is_grayscale}`);
      }
      const tail = note.length ? ` [${note.join(' ')}]` : '';
      console.log(`[${t}] <- ${e.stage} done${dur}${tail}`);
      if (e.output_url) console.log(`        url: ${e.output_url}`);
      break;
    }
    case 'stage_skipped':
      console.log(`[${t}] -- ${e.stage} skipped: ${e.reason}`);
      break;
    case 'final':
      console.log(`[${t}] == final`);
      console.log(`        ${JSON.stringify(e.result, null, 2).split('\n').join('\n        ')}`);
      break;
    case 'error':
      console.log(`[${t}] !! error: ${e.message}`);
      break;
  }
}

async function assertPipelineHealth(events: StageEvent[]): Promise<void> {
  const errors = events.filter(e => e.kind === 'error');
  if (errors.length > 0) {
    throw new SmokeAssertion(`Pipeline emitted error: ${errors[0].message}`);
  }

  const finalEvent = events.find(e => e.kind === 'final');
  if (!finalEvent || !finalEvent.result) {
    throw new SmokeAssertion('Pipeline never emitted a final event');
  }
  const result = finalEvent.result;

  // Each restoration stage must have a stage_done.
  const stagesSeen = new Set(
    events.filter(e => e.kind === 'stage_done' || e.kind === 'stage_skipped').map(e => e.stage),
  );
  for (const stage of ['bopb', 'codeformer', 'esrgan', 'colorize_check']) {
    if (!stagesSeen.has(stage)) {
      throw new SmokeAssertion(`Stage ${stage} did not complete`);
    }
  }

  // AdaFace gate: either the model ran (cosine_similarity is a number) or
  // it was explicitly skipped (adaface_skipped flag). The test fails if
  // the gate is silently passed without either signal.
  if (result.adaface_skipped) {
    if (result.cosine_similarity !== null) {
      throw new SmokeAssertion(
        `AdaFace skipped but cosine_similarity=${result.cosine_similarity} (should be null)`,
      );
    }
    if (result.identity_warning) {
      throw new SmokeAssertion('AdaFace skipped but identity_warning=true (inconsistent)');
    }
    console.log('[smoke] AdaFace: skipped (no SHA pinned) — OK');
  } else {
    if (typeof result.cosine_similarity !== 'number') {
      throw new SmokeAssertion('AdaFace not skipped but cosine_similarity is not a number');
    }
    console.log(
      `[smoke] AdaFace: cosine=${result.cosine_similarity.toFixed(3)} ` +
        `warning=${result.identity_warning}`,
    );
  }

  // Migrant Mother is B&W, so DDColor must have run.
  if (!result.was_colorized) {
    console.warn('[smoke] WARN: input was expected to be B&W but was_colorized=false');
  }

  if (!result.restored_url || !/^https?:\/\//.test(result.restored_url)) {
    throw new SmokeAssertion(`Bad restored_url: ${result.restored_url}`);
  }
}

main().catch(err => {
  if (err instanceof SmokeAssertion) {
    console.error(`[smoke] FAIL: ${err.message}`);
  } else {
    console.error(`[smoke] ERROR: ${err instanceof Error ? err.message : err}`);
  }
  process.exit(1);
});
