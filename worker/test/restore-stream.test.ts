import assert from 'node:assert/strict';
import test from 'node:test';
import { monitorRestorationStream, RestorationStreamObserver } from '../src/restoreStream';

const encoder = new TextEncoder();
const final = (image: string) => JSON.stringify({kind: 'final', result: {restored_url: image, identity_warning: false}});

test('a large photograph streams unchanged with bounded metadata memory', async () => {
  const input = encoder.encode('{"kind":"stage_done","stage":"bopb"}\n' + final('data:image/jpeg;base64,' + 'A'.repeat(8 * 1024 * 1024)) + '\n');
  const observer = new RestorationStreamObserver();
  for (let offset = 0; offset < input.length; offset += 1021) {
    observer.push(input.subarray(offset, offset + 1021));
    assert.ok(observer.retainedCharacters <= RestorationStreamObserver.MAX_METADATA);
  }
  assert.equal(observer.finish(), true);
  let offset = 0, completed = 0, refunded = 0;
  const pending: Promise<unknown>[] = [];
  const upstream = new ReadableStream<Uint8Array>({pull(c) {
    if (offset === input.length) { c.close(); return; }
    const end = Math.min(offset + 16381, input.length);
    c.enqueue(input.subarray(offset, end)); offset = end;
  }});
  const stream = monitorRestorationStream(upstream, async () => {completed++;}, async () => {refunded++;}, {waitUntil(p) {pending.push(p);}});
  const delivered = new Uint8Array(await new Response(stream).arrayBuffer());
  await Promise.all(pending);
  assert.deepEqual(delivered, input);
  assert.equal(completed, 1); assert.equal(refunded, 0);
});

test('every chunk boundary preserves final detection, including JSON escapes', () => {
  const bytes = encoder.encode(final('image with \\"quotes\\" and unicode é') + '\r\n');
  for (let boundary = 1; boundary < bytes.length; boundary++) {
    const observer = new RestorationStreamObserver();
    observer.push(bytes.subarray(0, boundary));observer.push(bytes.subarray(boundary));
    assert.equal(observer.finish(), true, `boundary ${boundary}`);
  }
});

test('truncated image, malformed final, missing image and error cannot be charged', () => {
  for (const input of [
    '{"kind":"final","result":{"restored_url":"data:image/jpeg;base64,AAAA',
    '{"kind":"final","result":{"restored_url":"image"',
    '{"kind":"final","result":{"restored_url":""}}\n',
    '{"kind":"final","result":{}}\n',
    final('image') + '\n{"kind":"error","message":"failed"}\n',
  ]) {
    const observer = new RestorationStreamObserver();observer.push(encoder.encode(input));
    assert.equal(observer.finish(), false);
  }
});

test('complete final at EOF is accepted without a newline', () => {
  const observer = new RestorationStreamObserver();observer.push(encoder.encode(final('image')));
  assert.equal(observer.finish(), true);
});

test('disconnect during photo delivery refunds once', async () => {
  let refunds = 0, completions = 0;
  const pending: Promise<unknown>[] = [];
  const upstream = new ReadableStream<Uint8Array>({start(c) {c.enqueue(encoder.encode('{"kind":"final","result":{"restored_url":"AAAA'));}});
  const stream = monitorRestorationStream(upstream, async () => {completions++;}, async () => {refunds++;}, {waitUntil(p){pending.push(p);}});
  const reader = stream.getReader();await reader.read();await reader.cancel();await Promise.all(pending);
  assert.equal(refunds, 1);assert.equal(completions, 0);
});
