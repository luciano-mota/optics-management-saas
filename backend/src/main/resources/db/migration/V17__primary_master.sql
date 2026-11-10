-- Master principal: quem criou a ótica. Nunca pode ser removido, rebaixado nem ter os acessos
-- alterados por outra pessoa. Somente ele gerencia os demais administradores master.
ALTER TABLE optic_memberships ADD COLUMN primary_master BOOLEAN NOT NULL DEFAULT FALSE;

-- Óticas existentes: o Master ativo mais antigo é o principal.
UPDATE optic_memberships m SET primary_master = TRUE
 WHERE (m.optic_id, m.account_id) IN (
   SELECT DISTINCT ON (optic_id) optic_id, account_id
     FROM optic_memberships
    WHERE role = 'MASTER' AND active
    ORDER BY optic_id, joined_at, account_id);

CREATE UNIQUE INDEX uq_memberships_one_primary ON optic_memberships(optic_id) WHERE primary_master;
ALTER TABLE optic_memberships ADD CONSTRAINT ck_primary_is_active_master
  CHECK (NOT primary_master OR (role = 'MASTER' AND active));
