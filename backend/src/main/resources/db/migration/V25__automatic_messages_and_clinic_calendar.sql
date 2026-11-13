-- Avisos automáticos por e-mail (sem custo: Brevo/SMTP já configurados) só para quem autorizou.
ALTER TABLE customers ADD COLUMN email VARCHAR(254);
ALTER TABLE customers ADD COLUMN email_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE customers ADD COLUMN email_opt_in_at TIMESTAMPTZ;
ALTER TABLE optometry_patients ADD COLUMN reminder_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE optometry_patients ADD COLUMN reminder_opt_in_at TIMESTAMPTZ;

-- A ótica liga/desliga cada aviso e escreve o texto. Cada mudança gera uma versão (auditoria).
CREATE TABLE optic_message_settings (
    optic_id UUID PRIMARY KEY REFERENCES optics(id),
    appointment_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    birthday_email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    appointment_template VARCHAR(1000) NOT NULL,
    birthday_template VARCHAR(1000) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    updated_by UUID REFERENCES accounts(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE optic_message_settings_history (
    optic_id UUID NOT NULL REFERENCES optics(id),
    version INT NOT NULL,
    settings JSONB NOT NULL,
    changed_by UUID NOT NULL REFERENCES accounts(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, version)
);

-- Fila de envio: cada aviso é gerado uma vez (dedupe), com tentativas e registro do resultado.
CREATE TABLE message_outbox (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('APPOINTMENT_REMINDER','BIRTHDAY')),
    ref_id UUID NOT NULL,
    dedupe_key VARCHAR(40) NOT NULL,
    recipient_email VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    template_version INT NOT NULL,
    unsubscribe_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','CANCELLED')),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    PRIMARY KEY (optic_id, id),
    UNIQUE (optic_id, kind, ref_id, dedupe_key)
);
CREATE INDEX ix_message_outbox_due ON message_outbox(status, next_attempt_at);

-- Link de calendário (iCal) que a clínica parceira assina; só o hash do token fica guardado.
CREATE TABLE clinic_calendar_feeds (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    clinic_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    last_access_at TIMESTAMPTZ,
    PRIMARY KEY (optic_id, id),
    FOREIGN KEY (optic_id, clinic_id) REFERENCES optical_partner_clinics(optic_id, id)
);
CREATE UNIQUE INDEX uq_clinic_calendar_feed_active ON clinic_calendar_feeds(optic_id, clinic_id) WHERE revoked_at IS NULL;
