-- O cadastro rápido da ótica inicia somente nome/e-mail/senha.
-- O primeiro login passa a requerer dados pessoais completos antes do painel.
ALTER TABLE accounts ADD COLUMN profile_completed_at TIMESTAMPTZ;
UPDATE accounts SET profile_completed_at=now()
 WHERE full_name IS NOT NULL AND btrim(full_name) <> ''
   AND cpf ~ '^[0-9]{11}$'
   AND phone IS NOT NULL AND btrim(phone) <> ''
   AND birth_date IS NOT NULL;
