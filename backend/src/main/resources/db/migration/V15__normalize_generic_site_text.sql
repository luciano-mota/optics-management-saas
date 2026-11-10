-- Normaliza a codificacao dos textos iniciais: somente defaults, sem alterar campanhas personalizadas.
-- Os literais U& escapados sao ASCII e independem da codificacao de recursos do Flyway.
ALTER TABLE site_settings ALTER COLUMN campaign_title
    SET DEFAULT U&'Bem-vindo \00E0 nossa \00F3tica';
ALTER TABLE site_settings ALTER COLUMN campaign_description
    SET DEFAULT U&'Conhe\00E7a nossa \00F3tica, novidades e condi\00E7\00F5es especiais. Fale com nossa equipe!';

UPDATE site_settings
SET campaign_title=U&'Bem-vindo \00E0 nossa \00F3tica',
    campaign_description=U&'Conhe\00E7a nossa \00F3tica, novidades e condi\00E7\00F5es especiais. Fale com nossa equipe!',
    updated_at=now()
WHERE campaign_title IN (
    U&'Bem-vindo \00E0 nossa \00F3tica',
    U&'Bem-vindo \00C3\00A0 nossa \00C3\00B3tica',
    U&'Sua \00F3tica vai at\00E9 voc\00EA!',
    U&'Sua \00C3\00B3tica vai at\00C3\00A9 voc\00C3\00AA!'
);
