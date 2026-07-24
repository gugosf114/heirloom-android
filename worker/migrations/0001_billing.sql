PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS clients (
  client_id TEXT PRIMARY KEY,
  free_remaining INTEGER NOT NULL DEFAULT 3 CHECK (free_remaining BETWEEN 0 AND 3),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS purchases (
  token_hash TEXT PRIMARY KEY,
  purchase_token TEXT NOT NULL,
  product_id TEXT NOT NULL,
  credits_granted INTEGER NOT NULL CHECK (credits_granted > 0),
  credits_remaining INTEGER NOT NULL CHECK (credits_remaining >= 0),
  order_id TEXT,
  region_code TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'active'
    CHECK (state IN ('active', 'cancelled', 'consumed')),
  acknowledged INTEGER NOT NULL DEFAULT 0 CHECK (acknowledged IN (0, 1)),
  consume_pending INTEGER NOT NULL DEFAULT 0 CHECK (consume_pending IN (0, 1)),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS client_purchases (
  client_id TEXT NOT NULL REFERENCES clients(client_id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL REFERENCES purchases(token_hash) ON DELETE CASCADE,
  linked_at INTEGER NOT NULL,
  PRIMARY KEY (client_id, token_hash)
);

CREATE TABLE IF NOT EXISTS sessions (
  session_hash TEXT PRIMARY KEY,
  client_id TEXT NOT NULL REFERENCES clients(client_id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS client_purchases_client_idx ON client_purchases(client_id);

CREATE TABLE IF NOT EXISTS restorations (
  request_id TEXT PRIMARY KEY,
  client_id TEXT NOT NULL REFERENCES clients(client_id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('free', 'paid')),
  token_hash TEXT REFERENCES purchases(token_hash),
  status TEXT NOT NULL DEFAULT 'reserved'
    CHECK (status IN ('reserved', 'completed', 'refunded')),
  created_at INTEGER NOT NULL,
  finished_at INTEGER
);
