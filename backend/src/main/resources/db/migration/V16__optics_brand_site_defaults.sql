-- Nova identidade visual do Optics Management (índigo/violeta), distinta de PhysioEvo e Ecclesia.
-- Sites que nunca foram personalizados (ainda com as cores padrão antigas) passam para o padrão novo.
-- A ótica continua livre para escolher as próprias cores ou derivá-las da logo.
ALTER TABLE site_settings ALTER COLUMN primary_color SET DEFAULT '#5b4cf0';
ALTER TABLE site_settings ALTER COLUMN secondary_color SET DEFAULT '#f6f6fa';
UPDATE site_settings SET primary_color='#5b4cf0', secondary_color='#f6f6fa'
 WHERE lower(primary_color)='#116b6d' AND lower(secondary_color)='#f3faf8';
