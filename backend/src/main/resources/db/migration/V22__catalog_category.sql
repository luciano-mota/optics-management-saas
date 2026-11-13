-- Tipo da lente e categoria da armação, para filtrar e agrupar as vendas nos relatórios.
-- Itens antigos ficam sem categoria ("Não informado") até a ótica completar.
ALTER TABLE catalog_items ADD COLUMN category VARCHAR(20);
ALTER TABLE catalog_items ADD CONSTRAINT ck_catalog_category CHECK (
    category IS NULL
    OR (kind = 'LENS' AND category IN ('MONOFOCAL','MULTIFOCAL','BIFOCAL','OCUPACIONAL','OUTRA'))
    OR (kind = 'FRAME' AND category IN ('GRAU','SOLAR','INFANTIL','ESPORTIVA','OUTRA')));
CREATE INDEX ix_catalog_optic_category ON catalog_items(optic_id, kind, category);
