-- Credit memos.
--
-- An invoice that has gone out does not change. When it turns out to be wrong the
-- answer is a second document that says so, and both stay on file. The reason is a
-- short fixed list rather than free text because the month end report groups by it.

CREATE TABLE credit_memo (
    number         VARCHAR(24)  NOT NULL,
    invoice_number VARCHAR(24)  NOT NULL,
    advertiser     VARCHAR(16)  NOT NULL,
    payer          VARCHAR(16)  NOT NULL,
    raised         DATE         NOT NULL,
    reason         VARCHAR(32)  NOT NULL,
    note           VARCHAR(120) NOT NULL,
    gross          INTEGER      NOT NULL,
    commission     INTEGER      NOT NULL,
    net            INTEGER      NOT NULL,
    sent           BOOLEAN      NOT NULL,
    spots          INTEGER      NOT NULL,
    CONSTRAINT pk_credit_memo PRIMARY KEY (number),
    CONSTRAINT fk_credit_memo_invoice_number_invoice FOREIGN KEY (invoice_number) REFERENCES invoice (number),
    CONSTRAINT fk_credit_memo_advertiser_advertiser FOREIGN KEY (advertiser) REFERENCES advertiser (code)
);

CREATE INDEX ix_credit_memo_raised ON credit_memo (raised);
