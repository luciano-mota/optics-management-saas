-- V7: calendário operacional por ótica, pacientes independentes e consultas parceiras.
-- Dias de funcionamento não são dias de vendas. Toda contagem considera só datas efetivamente abertas.
CREATE TABLE optic_business_calendar (
    optic_id UUID PRIMARY KEY REFERENCES optics(id) ON DELETE CASCADE,
    state_uf VARCHAR(2),
    municipality_ibge VARCHAR(7),
    municipality_name VARCHAR(160),
    time_zone VARCHAR(64) NOT NULL DEFAULT 'America/Fortaleza',
    open_weekdays VARCHAR(20) NOT NULL DEFAULT '1,2,3,4,5,6',
    updated_by UUID REFERENCES accounts(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state_uf IS NULL OR state_uf ~ '^[A-Z]{2}$'),
    CHECK (municipality_ibge IS NULL OR municipality_ibge ~ '^[0-9]{7}$')
);
CREATE TABLE regional_holidays (
    id UUID PRIMARY KEY,
    calendar_year INT NOT NULL CHECK(calendar_year BETWEEN 2000 AND 2199),
    holiday_date DATE NOT NULL,
    name VARCHAR(180) NOT NULL,
    scope VARCHAR(16) NOT NULL CHECK(scope IN ('NATIONAL','STATE','MUNICIPAL','OPTIONAL')),
    state_uf VARCHAR(2),
    municipality_ibge VARCHAR(7),
    source_name VARCHAR(120) NOT NULL,
    source_url TEXT,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(calendar_year,holiday_date,name,scope,state_uf,municipality_ibge)
);
CREATE INDEX idx_holidays_year_scope ON regional_holidays(calendar_year,scope,holiday_date);
CREATE UNIQUE INDEX uq_regional_holiday_natural_key ON regional_holidays(
    calendar_year,holiday_date,scope,
    coalesce(state_uf,''),coalesce(municipality_ibge,''),lower(name));
CREATE TABLE regional_holiday_sync (
    region_key VARCHAR(32) NOT NULL,
    calendar_year INT NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    success BOOLEAN NOT NULL DEFAULT FALSE,
    message VARCHAR(200),
    PRIMARY KEY(region_key,calendar_year)
);
CREATE TABLE optic_day_overrides (
    optic_id UUID NOT NULL REFERENCES optics(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    is_open BOOLEAN NOT NULL,
    title VARCHAR(180),
    source_url TEXT,
    updated_by UUID NOT NULL REFERENCES accounts(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,day)
);
CREATE TABLE optic_month_reviews (
    optic_id UUID NOT NULL REFERENCES optics(id) ON DELETE CASCADE,
    calendar_year INT NOT NULL CHECK(calendar_year BETWEEN 2000 AND 2199),
    calendar_month INT NOT NULL CHECK(calendar_month BETWEEN 1 AND 12),
    reviewed_by UUID NOT NULL REFERENCES accounts(id),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,calendar_year,calendar_month)
);
CREATE TABLE optic_monthly_goals (
    optic_id UUID NOT NULL REFERENCES optics(id) ON DELETE CASCADE,
    calendar_year INT NOT NULL CHECK(calendar_year BETWEEN 2000 AND 2199),
    calendar_month INT NOT NULL CHECK(calendar_month BETWEEN 1 AND 12),
    goal_amount NUMERIC(19,2) NOT NULL CHECK(goal_amount>=0),
    updated_by UUID NOT NULL REFERENCES accounts(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,calendar_year,calendar_month)
);
-- Paciente é entidade independente de clientes. Consulta não cria customer.
CREATE TABLE optometry_patients (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    full_name VARCHAR(180) NOT NULL,
    cpf VARCHAR(11),
    birth_date DATE,
    phone VARCHAR(24),
    email VARCHAR(254),
    address TEXT,
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id)
);
CREATE UNIQUE INDEX uq_optometry_patient_cpf ON optometry_patients(optic_id,cpf) WHERE cpf IS NOT NULL;
CREATE INDEX idx_optometry_patient_name ON optometry_patients(optic_id,lower(full_name));
CREATE TABLE optical_partner_clinics (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    name VARCHAR(180) NOT NULL,
    phone VARCHAR(24),
    address TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id)
);
CREATE TABLE optical_appointments (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    clinic_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
        CHECK(status IN ('SCHEDULED','CONFIRMED','COMPLETED','NO_SHOW','CANCELLED')),
    notes VARCHAR(600),
    created_by UUID NOT NULL REFERENCES accounts(id),
    updated_by UUID REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id),
    FOREIGN KEY(optic_id,patient_id) REFERENCES optometry_patients(optic_id,id),
    FOREIGN KEY(optic_id,clinic_id) REFERENCES optical_partner_clinics(optic_id,id),
    CHECK(ends_at>starts_at)
);
CREATE INDEX idx_appointments_calendar ON optical_appointments(optic_id,starts_at,status);
ALTER TABLE customers ADD COLUMN source_patient_id UUID;
ALTER TABLE customers ADD CONSTRAINT fk_customer_patient
    FOREIGN KEY(optic_id,source_patient_id) REFERENCES optometry_patients(optic_id,id);
CREATE UNIQUE INDEX uq_patient_customer_conversion
    ON customers(optic_id,source_patient_id) WHERE source_patient_id IS NOT NULL;
