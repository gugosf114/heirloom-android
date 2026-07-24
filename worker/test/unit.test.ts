import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canonicalPurchaseBinding,
  integrityRequestHash,
  secureEqual,
  validateReportPayload,
} from '../src/index';
import {
  parseVerifiedPurchase,
  validateIntegrityVerdict,
} from '../src/googlePlay';

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

test('verified US credit purchase maps only known products', () => {
  assert.deepEqual(
    parseVerifiedPurchase({
      purchaseStateContext: { purchaseState: 'PURCHASED' },
      acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
      regionCode: 'US',
      orderId: 'GPA.1234',
      productLineItem: [
        {
          productId: 'heirloom_restorations_20_v1',
          productOfferDetails: {
            consumptionState: 'CONSUMPTION_STATE_YET_TO_BE_CONSUMED',
          },
        },
      ],
    }),
    {
      productId: 'heirloom_restorations_20_v1',
      credits: 20,
      orderId: 'GPA.1234',
      regionCode: 'US',
      acknowledged: true,
      consumed: false,
    },
  );
  assert.equal(
    parseVerifiedPurchase({
      purchaseStateContext: { purchaseState: 'PURCHASED' },
      regionCode: 'CA',
      productLineItem: [{ productId: 'heirloom_restorations_20_v1' }],
    }),
    null,
  );
  assert.equal(
    parseVerifiedPurchase({
      purchaseStateContext: { purchaseState: 'CANCELLED' },
      regionCode: 'US',
      productLineItem: [{ productId: 'heirloom_restorations_20_v1' }],
    }),
    null,
  );
});

test('integrity verdict is bound to request, Play license, app, version, and device', async () => {
  const purchaseBinding = canonicalPurchaseBinding([
    {
      product_id: 'heirloom_restorations_20_v1',
      purchase_token: 'token-two',
    },
    {
      product_id: 'heirloom_restorations_5_v1',
      purchase_token: 'token-one',
    },
  ]);
  const requestHash = await integrityRequestHash(
    '/billing/sync',
    'a'.repeat(64),
    'bced8a7c-c744-4ae4-89ec-9ac44d0b8cc0',
    purchaseBinding,
  );
  const now = Date.now();
  const verdict = {
    tokenPayloadExternal: {
      requestDetails: {
        requestPackageName: 'com.wimlabs.heirloom',
        requestHash,
        timestampMillis: String(now),
      },
      accountDetails: { appLicensingVerdict: 'LICENSED' },
      appIntegrity: {
        appRecognitionVerdict: 'PLAY_RECOGNIZED',
        packageName: 'com.wimlabs.heirloom',
        versionCode: '6',
      },
      deviceIntegrity: {
        deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'],
      },
    },
  };
  assert.equal(
    validateIntegrityVerdict(verdict, {
      packageName: 'com.wimlabs.heirloom',
      requestHash,
      minimumVersionCode: 6,
      nowMillis: now,
    }),
    true,
  );
  assert.equal(
    validateIntegrityVerdict(verdict, {
      packageName: 'com.wimlabs.heirloom',
      requestHash: 'tampered',
      minimumVersionCode: 6,
      nowMillis: now,
    }),
    false,
  );
});

test('integrity purchase binding is deterministic and changes with a receipt', async () => {
  const purchases = [
    {
      product_id: 'heirloom_restorations_20_v1',
      purchase_token: 'token-two',
    },
    {
      product_id: 'heirloom_restorations_5_v1',
      purchase_token: 'token-one',
    },
  ];
  const forward = canonicalPurchaseBinding(purchases);
  const reversed = canonicalPurchaseBinding([...purchases].reverse());
  assert.equal(forward, reversed);

  const originalHash = await integrityRequestHash('/billing/sync', 'a'.repeat(64), 'request', forward);
  const changedHash = await integrityRequestHash(
    '/billing/sync',
    'a'.repeat(64),
    'request',
    canonicalPurchaseBinding([
      purchases[0],
      { ...purchases[1], purchase_token: 'tampered-token' },
    ]),
  );
  assert.notEqual(originalHash, changedHash);
});
