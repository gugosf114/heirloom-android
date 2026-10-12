import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';
import { refundAbandonedRestorations } from '../src/ledger';

// Run the real D1 SQL against SQLite; model the transaction boundary, not rows.
function database() {
  const sqlite = new DatabaseSync(':memory:');
  sqlite.exec(readFileSync(new URL('../migrations/0001_billing.sql', import.meta.url), 'utf8'));
  function prepare(sql: string) {
    let values: unknown[] = [];
    const statement = sqlite.prepare(sql);
    const params = () => Object.fromEntries(values.map((v,i) => [String(i+1),v]));
    return {
      bind(...args: unknown[]) {values = args;return this;},
      async first() {return statement.get(params() as any) ?? null;},
      async all() {return {results: statement.all(params() as any)};},
      async run() {return {meta: {changes: Number(statement.run(params() as any).changes)}};},
    };
  }
  return {sqlite, db: {prepare, async batch(statements: ReturnType<typeof prepare>[]) {
    sqlite.exec('BEGIN');try {const result=[];for(const s of statements)result.push(await s.run());sqlite.exec('COMMIT');return result;}catch(e){sqlite.exec('ROLLBACK');throw e;}
  }} as unknown as D1Database};
}

test('abandoned credits return once; recent, completed and other-client work stays unchanged', async () => {
  const {sqlite,db} = database();
  sqlite.exec(`INSERT INTO clients VALUES ('owner',0,0,0),('other',2,0,0);
    INSERT INTO restorations(request_id,client_id,source,status,created_at) VALUES
    ('lost','owner','free','reserved',1), ('recent','owner','free','reserved',1500),
    ('done','owner','free','completed',1), ('elsewhere','other','free','reserved',1);`);
  await refundAbandonedRestorations(db,'owner',2000);
  await refundAbandonedRestorations(db,'owner',2000);
  assert.equal(sqlite.prepare("SELECT free_remaining FROM clients WHERE client_id='owner'").get()?.free_remaining,1);
  assert.equal(sqlite.prepare("SELECT status FROM restorations WHERE request_id='lost'").get()?.status,'refunded');
  assert.equal(sqlite.prepare("SELECT status FROM restorations WHERE request_id='recent'").get()?.status,'reserved');
  assert.equal(sqlite.prepare("SELECT status FROM restorations WHERE request_id='done'").get()?.status,'completed');
  assert.equal(sqlite.prepare("SELECT status FROM restorations WHERE request_id='elsewhere'").get()?.status,'reserved');
  sqlite.close();
});
