-- Aceite dos termos de uso e da política de privacidade: data e versão aceitas por conta.
ALTER TABLE accounts ADD COLUMN terms_accepted_at TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN terms_version VARCHAR(20);
