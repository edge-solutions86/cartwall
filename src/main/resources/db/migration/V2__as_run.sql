-- Carry what actually aired on the spot row.
--
-- Billing reads the spot, so what the as-run said about it belongs on the spot and
-- not in a table of its own: whether it went out, when it really went out, how long
-- it really ran, and the automation's own word for what happened to it.
--
-- Everything already on file predates the as-run import, so the columns come in with
-- the values that mean "nobody has posted an as-run against this yet".

ALTER TABLE air_spot ADD COLUMN aired BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE air_spot ADD COLUMN aired_at INTEGER;
ALTER TABLE air_spot ADD COLUMN aired_length INTEGER;
ALTER TABLE air_spot ADD COLUMN aired_status VARCHAR(32) DEFAULT 'unknown' NOT NULL;
