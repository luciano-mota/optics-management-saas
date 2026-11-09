-- Cobrança hospedada: dados de cartão nunca transitam pelo nosso servidor.
ALTER TABLE optic_subscriptions
    ADD COLUMN payment_link_id VARCHAR(120),
    ADD COLUMN payment_link_url TEXT,
    ADD COLUMN payment_active BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN paid_through_at TIMESTAMPTZ,
    ADD COLUMN payment_updated_at TIMESTAMPTZ;

CREATE TABLE asaas_webhook_events (
    event_id VARCHAR(128) PRIMARY KEY,
    kind VARCHAR(100) NOT NULL,
    payment_id VARCHAR(128),
    optic_id UUID REFERENCES optics(id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_asaas_events_optic ON asaas_webhook_events(optic_id,received_at DESC);

-- Tokens temporários para validação de e-mail e redefinição de senha.
CREATE TABLE account_action_tokens (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    purpose VARCHAR(24) NOT NULL CHECK(purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_account_tokens_lookup ON account_action_tokens(account_id,purpose,expires_at);
