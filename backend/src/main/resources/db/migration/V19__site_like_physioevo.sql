-- Site da ótica no modelo do PhysioEvo: modelo, clima, fontes, textos e listas editáveis.
-- Listas (serviços, depoimentos, perguntas) em JSONB, sempre validadas e limitadas pelo backend.
ALTER TABLE site_settings ADD COLUMN mood VARCHAR(10) NOT NULL DEFAULT 'suave';
ALTER TABLE site_settings ADD COLUMN fonts VARCHAR(20) NOT NULL DEFAULT 'profissional';
ALTER TABLE site_settings ADD COLUMN about TEXT;
ALTER TABLE site_settings ADD COLUMN hours VARCHAR(300);
ALTER TABLE site_settings ADD COLUMN instagram VARCHAR(60);
ALTER TABLE site_settings ADD COLUMN show_map BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE site_settings ADD COLUMN services JSONB NOT NULL DEFAULT '[
  {"name":"Óculos de grau","description":"Armações para todos os estilos, com lentes feitas a partir da sua receita."},
  {"name":"Óculos de sol","description":"Proteção UV com estilo, inclusive com grau."},
  {"name":"Lentes de contato","description":"Orientação para escolher e usar com conforto."},
  {"name":"Exame de vista","description":"Consulta com clínicas parceiras para você sair com a receita em dia."},
  {"name":"Ajustes e manutenção","description":"Ajuste de armação, troca de plaquetas e pequenos reparos."}
]'::jsonb;
ALTER TABLE site_settings ADD COLUMN testimonials JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE site_settings ADD COLUMN faqs JSONB NOT NULL DEFAULT '[
  {"question":"Preciso de receita para fazer óculos de grau?","answer":"Sim. Traga a sua receita ou agende um exame de vista com uma clínica parceira."},
  {"question":"Quanto tempo leva para ficar pronto?","answer":"Depende das lentes escolhidas. Informamos o prazo de entrega no momento da compra."},
  {"question":"Quais formas de pagamento vocês aceitam?","answer":"Pix, dinheiro e cartões de débito e crédito, com parcelamento."}
]'::jsonb;

-- Modelos antigos do Optics para os modelos do PhysioEvo.
ALTER TABLE site_settings ALTER COLUMN template SET DEFAULT 'classico';
UPDATE site_settings SET template = CASE template
  WHEN 'modern' THEN 'imersivo' WHEN 'compact' THEN 'editorial' ELSE 'classico' END
 WHERE template IN ('classic','modern','compact');
