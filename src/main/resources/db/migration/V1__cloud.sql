-- Workbench Cloud schema, PostgreSQL 16.
CREATE TABLE users (
  id text PRIMARY KEY,
  email text NOT NULL UNIQUE,
  pass_hash text NOT NULL,
  created_at bigint NOT NULL
);

CREATE TABLE refresh_tokens (
  token_hash text PRIMARY KEY,
  user_id text NOT NULL,
  device_id text NOT NULL,
  expires_at bigint NOT NULL,
  replaced_by text
);

CREATE TABLE access_tokens (
  token_hash text PRIMARY KEY,
  user_id text NOT NULL,
  device_id text NOT NULL,
  expires_at bigint NOT NULL
);
CREATE INDEX idx_access_tokens_expiry ON access_tokens(expires_at);

CREATE TABLE devices (
  id text NOT NULL,
  user_id text NOT NULL,
  name text,
  last_cursor bigint NOT NULL DEFAULT 0,
  last_sync_at bigint,
  cursors text NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, user_id)
);

CREATE TABLE change_log (
  server_seq bigserial PRIMARY KEY,
  user_id text NOT NULL,
  device_id text NOT NULL,
  client_epoch text NOT NULL DEFAULT '',
  client_seq bigint NOT NULL,
  table_name text NOT NULL,
  row_id text NOT NULL,
  op text NOT NULL,
  payload jsonb,
  updated_at bigint NOT NULL,
  CONSTRAINT uq_change_log UNIQUE (user_id, device_id, client_epoch, client_seq)
);
CREATE INDEX idx_cl_user ON change_log(user_id, server_seq);
CREATE INDEX idx_cl_user_table_seq ON change_log(user_id, table_name, server_seq);

CREATE TABLE row_states (
  user_id text NOT NULL,
  table_name text NOT NULL,
  row_id text NOT NULL,
  payload jsonb NOT NULL,
  deleted boolean NOT NULL DEFAULT false,
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, table_name, row_id)
);
CREATE INDEX idx_row_states_table ON row_states(user_id, table_name, updated_at);

CREATE TABLE sync_sessions (
  user_id text PRIMARY KEY,
  session_id text NOT NULL UNIQUE,
  device_id text NOT NULL,
  opened_at bigint NOT NULL,
  expires_at bigint NOT NULL
);
CREATE INDEX idx_sync_sessions_expiry ON sync_sessions(expires_at);

CREATE TABLE sync_table_state (
  user_id text NOT NULL,
  table_name text NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  mode text NOT NULL DEFAULT 'row',
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, table_name)
);

CREATE TABLE blobs (
  user_id text NOT NULL,
  sha256 text NOT NULL,
  size bigint NOT NULL,
  mime text,
  backend text NOT NULL,
  storage_key text NOT NULL,
  state text NOT NULL DEFAULT 'ready',
  created_at bigint NOT NULL,
  verified_at bigint,
  last_access_at bigint,
  PRIMARY KEY (user_id, sha256)
);
CREATE INDEX idx_blobs_state_age ON blobs(state, created_at);

CREATE TABLE blob_refs (
  user_id text NOT NULL,
  file_id text NOT NULL,
  sha256 text NOT NULL,
  deleted boolean NOT NULL DEFAULT false,
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, file_id)
);
CREATE INDEX idx_blob_refs_live ON blob_refs(user_id, sha256, deleted);
