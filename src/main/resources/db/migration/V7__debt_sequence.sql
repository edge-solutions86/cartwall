-- Tell identical debts apart.
--
-- A line that asked for five spots in a week and got one is owed four, and the
-- gather reports four debts that are identical in every column. Keyed on line, day
-- and cause alone they collapsed into a single row, and the client was quietly owed
-- a quarter of what they were actually owed.
--
-- The ordinal is assigned by counting identical debts within one gather, so the same
-- span gathered again numbers them the same way and the nightly job stays a no-op on
-- a second run.

ALTER TABLE makegood_debt ADD COLUMN sequence INTEGER DEFAULT 1 NOT NULL;
ALTER TABLE makegood_debt DROP CONSTRAINT uq_makegood_debt_line_day_cause;
ALTER TABLE makegood_debt ADD CONSTRAINT uq_makegood_debt_line_day_cause_sequence
    UNIQUE (order_number, line, day, cause, sequence);
