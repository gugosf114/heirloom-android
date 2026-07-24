import assert from 'node:assert/strict';
import test from 'node:test';

import { secureEqual, validateReportPayload } from '../src/index';

test('secureEqual accepts only identical values', () => {
  assert.equal(secureEqual('same-value', 'same-value'), true);
  assert.equal(secureEqual('same-value', 'same-valuE'), false);
  assert.equal(secureEqual('short', 'shorter'), false);
});

test('report validation keeps only bounded, non-photo metadata', () => {
  const result = validateReportPayload({
    reason: 'wrong_person',
    details: 'The restored face does not resemble the original.',
    cosine_similarity: 0.42,
    identity_warning: true,
    identity_unverified: false,
    was_colorized: false,
    app_version: '0.2.0',
  });

  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.deepEqual(result.value, {
    reason: 'wrong_person',
    details: 'The restored face does not resemble the original.',
    cosine_similarity: 0.42,
    identity_warning: true,
    identity_unverified: false,
    was_colorized: false,
    app_version: '0.2.0',
  });
});

test('report validation rejects unknown reasons and oversized details', () => {
  assert.equal(validateReportPayload({ reason: 'unknown' }).ok, false);
  assert.equal(
    validateReportPayload({
      reason: 'other',
      details: 'x'.repeat(1001),
    }).ok,
    false,
  );
});
