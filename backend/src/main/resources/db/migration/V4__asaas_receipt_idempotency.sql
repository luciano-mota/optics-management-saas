-- Idempotência por cobrança, além do identificador de evento recebido.
CREATE TABLE asaas_payment_receipts (
    payment_id VARCHAR(128) PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    event_id VARCHAR(128) NOT NULL UNIQUE,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
