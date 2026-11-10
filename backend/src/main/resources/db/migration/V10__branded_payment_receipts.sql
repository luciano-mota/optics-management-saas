-- V10: identidade visual e recibos imutáveis de pagamentos reais.
CREATE TABLE optic_receipt_identity (
    optic_id UUID PRIMARY KEY REFERENCES optics(id) ON DELETE CASCADE,
    logo BYTEA,
    logo_mime VARCHAR(30),
    signature BYTEA,
    signature_mime VARCHAR(30),
    signer_name VARCHAR(180),
    signer_role VARCHAR(120),
    next_receipt_number BIGINT NOT NULL DEFAULT 1 CHECK(next_receipt_number>0),
    updated_by UUID REFERENCES accounts(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sale_receipts (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    receipt_number BIGINT NOT NULL CHECK(receipt_number>0),
    sale_id UUID NOT NULL,
    finance_entry_id UUID NOT NULL,
    paid_on DATE NOT NULL,
    payment_gross NUMERIC(19,2) NOT NULL CHECK(payment_gross>0),
    pdf_bytes BYTEA NOT NULL,
    snapshot JSONB NOT NULL,
    issued_by UUID NOT NULL REFERENCES accounts(id),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id),
    UNIQUE(optic_id,receipt_number),
    UNIQUE(optic_id,finance_entry_id),
    FOREIGN KEY(optic_id,sale_id) REFERENCES sales(optic_id,id),
    FOREIGN KEY(optic_id,finance_entry_id) REFERENCES finance_entries(optic_id,id)
);
CREATE INDEX idx_sale_receipts_recent ON sale_receipts(optic_id,issued_at DESC);

CREATE TABLE receipt_share_tokens (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(optic_id,receipt_id) REFERENCES sale_receipts(optic_id,id)
);
CREATE INDEX idx_receipt_share_tokens_expiry ON receipt_share_tokens(expires_at);
