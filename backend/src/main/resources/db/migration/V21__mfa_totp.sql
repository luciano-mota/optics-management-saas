-- Verificação em duas etapas (TOTP, RFC 6238) opcional, recomendada para administradores e operador.
ALTER TABLE accounts ADD COLUMN mfa_secret VARCHAR(64);
ALTER TABLE accounts ADD COLUMN mfa_enabled_at TIMESTAMPTZ;
-- Último intervalo de 30 s aceito: impede reutilizar o mesmo código (replay).
ALTER TABLE accounts ADD COLUMN mfa_last_step BIGINT;
ALTER TABLE accounts ADD CONSTRAINT ck_mfa_enabled_has_secret
    CHECK (mfa_enabled_at IS NULL OR mfa_secret IS NOT NULL);

-- Códigos de recuperação de uso único; só o hash SHA-256 é guardado.
CREATE TABLE account_mfa_recovery_codes (
    account_id UUID NOT NULL REFERENCES accounts(id),
    code_hash CHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, code_hash)
);
