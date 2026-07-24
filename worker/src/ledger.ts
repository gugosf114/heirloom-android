const FREE_CREDITS = 3;
const SESSION_LIFETIME_SECONDS = 30 * 24 * 60 * 60;

export interface Balance {
  freeRemaining: number;
  paidRemaining: number;
  totalRemaining: number;
}

export interface PaidCandidate {
  tokenHash: string;
  purchaseToken: string;
  productId: string;
  creditsRemaining: number;
  acknowledged: boolean;
}

export interface Reservation {
  requestId: string;
  clientId: string;
  source: 'free' | 'paid';
  tokenHash?: string;
}

interface SessionRow {
  client_id: string;
  expires_at: number;
}

interface PurchaseRow {
  token_hash: string;
  purchase_token: string;
  product_id: string;
  credits_remaining: number;
  acknowledged: number;
}

interface ReservationRow {
  request_id: string;
  client_id: string;
  source: 'free' | 'paid';
  token_hash: string | null;
}

export class DuplicateRestorationError extends Error {}
export class NoCreditsError extends Error {}

export async function ensureClient(db: D1Database, clientId: string): Promise<void> {
  const now = nowSeconds();
  await db
    .prepare(
      `INSERT OR IGNORE INTO clients
         (client_id, free_remaining, created_at, updated_at)
       VALUES (?1, ?2, ?3, ?3)`,
    )
    .bind(clientId, FREE_CREDITS, now)
    .run();
}

export async function registerPurchase(
  db: D1Database,
  clientId: string,
  tokenHash: string,
  purchaseToken: string,
  productId: string,
  credits: number,
  orderId: string | undefined,
  regionCode: string,
  acknowledged: boolean,
): Promise<{ inserted: boolean }> {
  const now = nowSeconds();
  const inserted = await db
    .prepare(
      `INSERT OR IGNORE INTO purchases
         (token_hash, purchase_token, product_id, credits_granted, credits_remaining,
          order_id, region_code, state, acknowledged, created_at, updated_at)
       VALUES (?1, ?2, ?3, ?4, ?4, ?5, ?6, 'active', ?7, ?8, ?8)`,
    )
    .bind(
      tokenHash,
      purchaseToken,
      productId,
      credits,
      orderId ?? null,
      regionCode,
      acknowledged ? 1 : 0,
      now,
    )
    .run();
  await db
    .prepare(
      `INSERT OR IGNORE INTO client_purchases (client_id, token_hash, linked_at)
       VALUES (?1, ?2, ?3)`,
    )
    .bind(clientId, tokenHash, now)
    .run();
  return { inserted: (inserted.meta.changes ?? 0) === 1 };
}

export async function markPurchaseAcknowledged(
  db: D1Database,
  tokenHash: string,
): Promise<void> {
  await db
    .prepare(
      `UPDATE purchases
          SET acknowledged = 1, updated_at = ?2
        WHERE token_hash = ?1`,
    )
    .bind(tokenHash, nowSeconds())
    .run();
}

export async function cancelPurchase(db: D1Database, tokenHash: string): Promise<void> {
  await db
    .prepare(
      `UPDATE purchases
          SET state = 'cancelled', credits_remaining = 0, updated_at = ?2
        WHERE token_hash = ?1`,
    )
    .bind(tokenHash, nowSeconds())
    .run();
}

export async function issueSession(
  db: D1Database,
  clientId: string,
): Promise<{ token: string; expiresAt: number }> {
  const now = nowSeconds();
  const token = randomToken();
  const sessionHash = await sha256Hex(token);
  const expiresAt = now + SESSION_LIFETIME_SECONDS;
  await db
    .prepare(
      `INSERT INTO sessions (session_hash, client_id, expires_at, created_at)
       VALUES (?1, ?2, ?3, ?4)`,
    )
    .bind(sessionHash, clientId, expiresAt, now)
    .run();
  await db.prepare('DELETE FROM sessions WHERE expires_at < ?1').bind(now).run();
  return { token, expiresAt };
}

export async function authenticateSession(
  db: D1Database,
  token: string,
): Promise<string | null> {
  if (token.length < 32 || token.length > 256) return null;
  const row = await db
    .prepare(
      `SELECT client_id, expires_at
         FROM sessions
        WHERE session_hash = ?1`,
    )
    .bind(await sha256Hex(token))
    .first<SessionRow>();
  if (!row || row.expires_at <= nowSeconds()) return null;
  return row.client_id;
}

export async function balanceForClient(db: D1Database, clientId: string): Promise<Balance> {
  const row = await db
    .prepare(
      `SELECT c.free_remaining,
              COALESCE(SUM(
                CASE WHEN p.state = 'active' THEN p.credits_remaining ELSE 0 END
              ), 0) AS paid_remaining
         FROM clients c
         LEFT JOIN client_purchases cp ON cp.client_id = c.client_id
         LEFT JOIN purchases p ON p.token_hash = cp.token_hash
        WHERE c.client_id = ?1
        GROUP BY c.client_id, c.free_remaining`,
    )
    .bind(clientId)
    .first<{ free_remaining: number; paid_remaining: number }>();
  const freeRemaining = Number(row?.free_remaining ?? 0);
  const paidRemaining = Number(row?.paid_remaining ?? 0);
  return {
    freeRemaining,
    paidRemaining,
    totalRemaining: freeRemaining + paidRemaining,
  };
}

export async function paidCandidates(
  db: D1Database,
  clientId: string,
): Promise<PaidCandidate[]> {
  const result = await db
    .prepare(
      `SELECT p.token_hash, p.purchase_token, p.product_id,
              p.credits_remaining, p.acknowledged
         FROM purchases p
         JOIN client_purchases cp ON cp.token_hash = p.token_hash
        WHERE cp.client_id = ?1
          AND p.state = 'active'
          AND p.credits_remaining > 0
        ORDER BY p.created_at ASC`,
    )
    .bind(clientId)
    .all<PurchaseRow>();
  return result.results.map(row => ({
    tokenHash: row.token_hash,
    purchaseToken: row.purchase_token,
    productId: row.product_id,
    creditsRemaining: Number(row.credits_remaining),
    acknowledged: row.acknowledged === 1,
  }));
}

export async function reserveRestoration(
  db: D1Database,
  clientId: string,
  requestId: string,
  validPaidTokenHashes: string[],
): Promise<Reservation> {
  const duplicate = await db
    .prepare('SELECT request_id FROM restorations WHERE request_id = ?1')
    .bind(requestId)
    .first<{ request_id: string }>();
  if (duplicate) throw new DuplicateRestorationError('restoration request already used');

  const now = nowSeconds();
  try {
    const [, inserted] = await db.batch([
      db
        .prepare(
          `UPDATE clients
              SET free_remaining = free_remaining - 1,
                  updated_at = ?2
            WHERE client_id = ?1
              AND free_remaining > 0`,
        )
        .bind(clientId, now),
      db
        .prepare(
          `INSERT INTO restorations
             (request_id, client_id, source, status, created_at)
           SELECT ?1, ?2, 'free', 'reserved', ?3
            WHERE changes() = 1`,
        )
        .bind(requestId, clientId, now),
    ]);
    if ((inserted.meta.changes ?? 0) === 1) {
      return { requestId, clientId, source: 'free' };
    }
  } catch (error) {
    if (errorMessage(error).includes('UNIQUE')) {
      throw new DuplicateRestorationError('restoration request already used');
    }
    throw error;
  }

  for (const tokenHash of validPaidTokenHashes) {
    try {
      const [, inserted] = await db.batch([
        db
          .prepare(
            `UPDATE purchases
                SET credits_remaining = credits_remaining - 1,
                    updated_at = ?3
              WHERE token_hash = ?2
                AND state = 'active'
                AND credits_remaining > 0
                AND EXISTS (
                  SELECT 1 FROM client_purchases
                   WHERE client_id = ?1 AND token_hash = ?2
                )`,
          )
          .bind(clientId, tokenHash, now),
        db
          .prepare(
            `INSERT INTO restorations
               (request_id, client_id, source, token_hash, status, created_at)
             SELECT ?1, ?2, 'paid', ?3, 'reserved', ?4
              WHERE changes() = 1`,
          )
          .bind(requestId, clientId, tokenHash, now),
      ]);
      if ((inserted.meta.changes ?? 0) === 1) {
        return {
          requestId,
          clientId,
          source: 'paid',
          tokenHash,
        };
      }
    } catch (error) {
      if (errorMessage(error).includes('UNIQUE')) {
        throw new DuplicateRestorationError('restoration request already used');
      }
      throw error;
    }
  }
  throw new NoCreditsError('no restoration credits remaining');
}

export async function completeRestoration(
  db: D1Database,
  requestId: string,
): Promise<Reservation | null> {
  const reservation = await reservationForRequest(db, requestId);
  if (!reservation) return null;
  const updated = await db
    .prepare(
      `UPDATE restorations
          SET status = 'completed', finished_at = ?2
        WHERE request_id = ?1 AND status = 'reserved'`,
    )
    .bind(requestId, nowSeconds())
    .run();
  return (updated.meta.changes ?? 0) === 1 ? mapReservation(reservation) : null;
}

export async function refundRestoration(db: D1Database, requestId: string): Promise<void> {
  const reservation = await reservationForRequest(db, requestId);
  if (!reservation) return;
  const now = nowSeconds();
  const refundCredit = reservation.source === 'free'
    ? db
      .prepare(
        `UPDATE clients
            SET free_remaining = MIN(?3, free_remaining + 1),
                updated_at = ?2
          WHERE client_id = ?1
            AND changes() = 1`,
      )
      .bind(reservation.client_id, now, FREE_CREDITS)
    : db
      .prepare(
        `UPDATE purchases
            SET credits_remaining = credits_remaining + 1,
                updated_at = ?2
          WHERE token_hash = ?1
            AND state = 'active'
            AND changes() = 1`,
      )
      .bind(reservation.token_hash, now);
  await db.batch([
    db
      .prepare(
        `UPDATE restorations
            SET status = 'refunded', finished_at = ?2
          WHERE request_id = ?1 AND status = 'reserved'`,
      )
      .bind(requestId, now),
    refundCredit,
  ]);
}

export async function purchaseForTokenHash(
  db: D1Database,
  tokenHash: string,
): Promise<PaidCandidate | null> {
  const row = await db
    .prepare(
      `SELECT token_hash, purchase_token, product_id, credits_remaining, acknowledged
         FROM purchases
        WHERE token_hash = ?1`,
    )
    .bind(tokenHash)
    .first<PurchaseRow>();
  if (!row) return null;
  return {
    tokenHash: row.token_hash,
    purchaseToken: row.purchase_token,
    productId: row.product_id,
    creditsRemaining: Number(row.credits_remaining),
    acknowledged: row.acknowledged === 1,
  };
}

export async function markPurchaseConsumed(
  db: D1Database,
  tokenHash: string,
): Promise<void> {
  await db
    .prepare(
      `UPDATE purchases
          SET state = 'consumed', consume_pending = 0, updated_at = ?2
        WHERE token_hash = ?1 AND credits_remaining = 0`,
    )
    .bind(tokenHash, nowSeconds())
    .run();
}

export async function markConsumePending(
  db: D1Database,
  tokenHash: string,
): Promise<void> {
  await db
    .prepare(
      `UPDATE purchases
          SET consume_pending = 1, updated_at = ?2
        WHERE token_hash = ?1 AND credits_remaining = 0`,
    )
    .bind(tokenHash, nowSeconds())
    .run();
}

async function reservationForRequest(
  db: D1Database,
  requestId: string,
): Promise<ReservationRow | null> {
  return db
    .prepare(
      `SELECT request_id, client_id, source, token_hash
         FROM restorations
        WHERE request_id = ?1`,
    )
    .bind(requestId)
    .first<ReservationRow>();
}

function mapReservation(row: ReservationRow): Reservation {
  return {
    requestId: row.request_id,
    clientId: row.client_id,
    source: row.source,
    tokenHash: row.token_hash ?? undefined,
  };
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map(byte => byte.toString(16).padStart(2, '0'))
    .join('');
}

function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
