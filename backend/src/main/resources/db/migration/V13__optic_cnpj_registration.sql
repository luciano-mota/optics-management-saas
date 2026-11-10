-- O CNPJ obrigatório pertence à ótica, não ao usuário Master.
-- Registros antigos sem CNPJ válido serão solicitados no próximo acesso.
-- O campo tax_document de optics já comporta 14 caracteres alfanuméricos.
CREATE INDEX IF NOT EXISTS idx_optics_tax_document
  ON optics(tax_document) WHERE tax_document IS NOT NULL;
