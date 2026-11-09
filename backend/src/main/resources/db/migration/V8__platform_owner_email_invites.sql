-- Convite de acesso global por email: token de uso único, nunca armazenar texto puro.
CREATE TABLE platform_owner_invites (
    id UUID PRIMARY KEY,
    invite_email VARCHAR(254) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX ix_platform_owner_invites_throttle
    ON platform_owner_invites(lower(invite_email), created_at DESC);
