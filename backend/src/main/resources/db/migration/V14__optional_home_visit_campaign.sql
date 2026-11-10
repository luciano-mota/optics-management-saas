-- Atendimento domiciliar pertence à campanha de uma ótica específica.
-- Não disponibilizar essa ideia como padrão para todo assinante.
ALTER TABLE optics ADD COLUMN home_visits_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Remover o texto promocional antigo somente quando ainda for o valor padrão.
ALTER TABLE site_settings ALTER COLUMN campaign_title
  SET DEFAULT 'Bem-vindo à nossa ótica';
ALTER TABLE site_settings ALTER COLUMN campaign_description
  SET DEFAULT 'Conheça nossa ótica, novidades e condições especiais. Fale com nossa equipe!';
UPDATE site_settings
SET campaign_title='Bem-vindo à nossa ótica',
    campaign_description='Conheça nossa ótica, novidades e condições especiais. Fale com nossa equipe!',
    updated_at=now()
WHERE campaign_title='Sua ótica vai até você!'
  AND campaign_description='Já tem a receita? Agende uma visita em sua casa.';
