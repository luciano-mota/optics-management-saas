-- Atendimento em domicílio deixa de ser liberado pela LCM DEV TECH: é uma campanha/serviço que a
-- própria ótica liga no editor do site (optics.home_visits_enabled), com título e texto próprios.
ALTER TABLE site_settings ADD COLUMN home_visit_title VARCHAR(140) NOT NULL
  DEFAULT 'Já tem sua receita? Nós vamos até você';
ALTER TABLE site_settings ADD COLUMN home_visit_description VARCHAR(600) NOT NULL
  DEFAULT 'Agende uma visita em casa: levamos armações e lentes para você escolher com calma. Envie a foto da receita e escolha o melhor dia e horário.';
