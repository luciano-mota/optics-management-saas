-- Optics Management: primeira migration independente dos outros SaaS.
-- IDs gerados na aplicação. Todas as referências a lojas serão validadas no backend.
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(180),
    cpf VARCHAR(11),
    birth_date DATE,
    phone VARCHAR(24),
    address TEXT,
    email_verified_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_accounts_email_casefold ON accounts (lower(email));

CREATE TABLE optics (
    id UUID PRIMARY KEY,
    slug VARCHAR(100) NOT NULL UNIQUE,
    trade_name VARCHAR(180) NOT NULL,
    legal_name VARCHAR(180),
    tax_document VARCHAR(14),
    contact_email VARCHAR(254) NOT NULL,
    contact_phone VARCHAR(24),
    whatsapp VARCHAR(24),
    address TEXT,
    site_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE optic_memberships (
    optic_id UUID NOT NULL REFERENCES optics(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    role VARCHAR(20) NOT NULL CHECK (role IN ('MASTER','MANAGER','SECRETARY','SELLER')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, account_id)
);
CREATE INDEX ix_memberships_account ON optic_memberships(account_id);

CREATE TABLE member_permission_overrides (
    optic_id UUID NOT NULL,
    account_id UUID NOT NULL,
    permission VARCHAR(80) NOT NULL,
    effect VARCHAR(6) NOT NULL CHECK (effect IN ('ALLOW','DENY')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, account_id, permission),
    FOREIGN KEY (optic_id, account_id) REFERENCES optic_memberships(optic_id, account_id)
);

CREATE TABLE member_invitations (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    invited_email VARCHAR(254) NOT NULL,
    requested_role VARCHAR(20) NOT NULL CHECK (requested_role IN ('MASTER','MANAGER','SECRETARY','SELLER')),
    token_hash CHAR(64) NOT NULL UNIQUE,
    invited_by UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (optic_id, invited_by) REFERENCES optic_memberships(optic_id, account_id)
);
CREATE INDEX ix_invites_optic_email ON member_invitations(optic_id, lower(invited_email));

CREATE TABLE optic_subscriptions (
    optic_id UUID PRIMARY KEY REFERENCES optics(id),
    billing_mode VARCHAR(16) NOT NULL DEFAULT 'TRIAL'
        CHECK (billing_mode IN ('TRIAL', 'PAID', 'COMPLIMENTARY')),
    trial_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    trial_ends_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '7 days'),
    monthly_price NUMERIC(19,2) CHECK (monthly_price IS NULL OR monthly_price >= 0),
    asaas_customer_id VARCHAR(120),
    asaas_subscription_id VARCHAR(120),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE platform_operators (
    account_id UUID PRIMARY KEY REFERENCES accounts(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    optic_id UUID REFERENCES optics(id),
    actor_account_id UUID REFERENCES accounts(id),
    event_type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(80),
    entity_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_optic_time ON audit_events(optic_id, occurred_at DESC);
