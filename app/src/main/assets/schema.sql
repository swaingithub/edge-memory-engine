
CREATE TABLE IF NOT EXISTS event_log (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_urn TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    action TEXT NOT NULL,
    source_app TEXT NOT NULL,
    raw_text TEXT NOT NULL,
    binary_embedding BLOB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_entity_time ON event_log(entity_urn, timestamp ASC);
CREATE INDEX IF NOT EXISTS idx_time ON event_log(timestamp DESC);

CREATE VIRTUAL TABLE IF NOT EXISTS event_fts USING fts5(
    raw_text,
    content='event_log',
    content_rowid='event_id',
    tokenize='porter unicode61'
);

CREATE TRIGGER IF NOT EXISTS trg_event_ai AFTER INSERT ON event_log BEGIN
    INSERT INTO event_fts(rowid, raw_text) VALUES (new.event_id, new.raw_text);
END;

CREATE TABLE IF NOT EXISTS daily_summaries (
    day_date TEXT PRIMARY KEY,
    summary_text TEXT NOT NULL,
    summary_embedding BLOB NOT NULL
);
