-- Notificações push (Web Push padrão, sem serviço pago). Uma inscrição por aparelho/navegador.
CREATE TABLE push_subscriptions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    endpoint TEXT NOT NULL UNIQUE,
    p256dh VARCHAR(120) NOT NULL,
    auth VARCHAR(40) NOT NULL,
    device VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_success_at TIMESTAMPTZ,
    failures INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_push_subscriptions_account ON push_subscriptions(account_id);

-- Chaves VAPID geradas pelo próprio sistema quando não vierem por variável de ambiente.
CREATE TABLE platform_push_keys (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    public_key VARCHAR(120) NOT NULL,
    private_key VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
