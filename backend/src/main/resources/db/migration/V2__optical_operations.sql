-- V2: domínio operacional e financeiro. Sempre filtrar pelo optic_id validado na sessão.
CREATE TABLE customers (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    full_name VARCHAR(180) NOT NULL,
    cpf VARCHAR(11),
    birth_date DATE,
    phone VARCHAR(24),
    address TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, id)
);
CREATE UNIQUE INDEX uq_customer_cpf ON customers(optic_id,cpf) WHERE cpf IS NOT NULL;
CREATE INDEX ix_customers_name ON customers(optic_id,lower(full_name));

CREATE TABLE catalog_items (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK(kind IN ('LENS','FRAME')),
    brand VARCHAR(120) NOT NULL,
    model VARCHAR(160),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id)
);
CREATE INDEX ix_catalog_optic_kind ON catalog_items(optic_id,kind,active);

CREATE TABLE card_machines (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    name VARCHAR(140) NOT NULL,
    debit_fee NUMERIC(9,4) NOT NULL CHECK(debit_fee>=0 AND debit_fee<100),
    credit_fee NUMERIC(9,4) NOT NULL CHECK(credit_fee>=0 AND credit_fee<100),
    fixed_fee NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK(fixed_fee>=0),
    debit_days INT NOT NULL DEFAULT 1 CHECK(debit_days>=0),
    credit_days INT NOT NULL DEFAULT 30 CHECK(credit_days>=0),
    anticipated BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY(optic_id,id)
);
CREATE TABLE card_machine_rates (
    optic_id UUID NOT NULL,
    machine_id UUID NOT NULL,
    installments INT NOT NULL CHECK(installments>=1 AND installments<=12),
    fee NUMERIC(9,4) NOT NULL CHECK(fee>=0 AND fee<100),
    PRIMARY KEY(optic_id,machine_id,installments),
    FOREIGN KEY(optic_id,machine_id) REFERENCES card_machines(optic_id,id)
);

CREATE TABLE sales (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    customer_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    lens_id UUID NOT NULL,
    frame_id UUID NOT NULL,
    title VARCHAR(180),
    sold_on DATE NOT NULL DEFAULT CURRENT_DATE,
    expected_delivery DATE,
    status VARCHAR(32) NOT NULL DEFAULT 'AWAITING_PRODUCTION'
        CHECK(status IN ('AWAITING_PRODUCTION','READY_FOR_DELIVERY','DELIVERED','CANCELLED')),
    replacement_months INT NOT NULL CHECK(replacement_months IN (6,12)),
    gross_amount NUMERIC(19,2) NOT NULL CHECK(gross_amount>0),
    fee_amount NUMERIC(19,2) NOT NULL CHECK(fee_amount>=0),
    charged_amount NUMERIC(19,2) NOT NULL CHECK(charged_amount>0),
    net_amount NUMERIC(19,2) NOT NULL CHECK(net_amount>=0),
    payment_method VARCHAR(12) NOT NULL CHECK(payment_method IN ('DINHEIRO','PIX','DEBITO','CREDITO')),
    installments INT NOT NULL DEFAULT 1 CHECK(installments BETWEEN 1 AND 12),
    card_machine_id UUID,
    delivered_at TIMESTAMPTZ,
    delivered_by UUID,
    notes TEXT,
    idempotency_key VARCHAR(96),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id),
    FOREIGN KEY(optic_id,customer_id) REFERENCES customers(optic_id,id),
    FOREIGN KEY(optic_id,seller_id) REFERENCES optic_memberships(optic_id,account_id),
    FOREIGN KEY(optic_id,lens_id) REFERENCES catalog_items(optic_id,id),
    FOREIGN KEY(optic_id,frame_id) REFERENCES catalog_items(optic_id,id),
    FOREIGN KEY(optic_id,card_machine_id) REFERENCES card_machines(optic_id,id),
    FOREIGN KEY(optic_id,delivered_by) REFERENCES optic_memberships(optic_id,account_id)
);
CREATE UNIQUE INDEX uq_sales_idempotency ON sales(optic_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_sales_optic_date ON sales(optic_id,sold_on DESC);
CREATE INDEX ix_sales_optic_status ON sales(optic_id,status);
CREATE INDEX ix_sales_customer ON sales(optic_id,customer_id);

CREATE TABLE sale_events (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL,
    sale_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    previous_status VARCHAR(32),
    next_status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(optic_id,sale_id) REFERENCES sales(optic_id,id),
    FOREIGN KEY(optic_id,actor_id) REFERENCES optic_memberships(optic_id,account_id)
);

CREATE TABLE finance_entries (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    direction VARCHAR(7) NOT NULL CHECK(direction IN ('INCOME','EXPENSE')),
    category VARCHAR(80) NOT NULL,
    description VARCHAR(300) NOT NULL,
    gross_amount NUMERIC(19,2) NOT NULL CHECK(gross_amount>0),
    fee_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK(fee_amount>=0),
    net_amount NUMERIC(19,2) NOT NULL CHECK(net_amount>=0),
    occurred_on DATE NOT NULL DEFAULT CURRENT_DATE,
    due_on DATE,
    settled_on DATE,
    origin VARCHAR(12) NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','SALE')),
    sale_id UUID,
    reversed_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id),
    FOREIGN KEY(optic_id,sale_id) REFERENCES sales(optic_id,id),
    FOREIGN KEY(optic_id,created_by) REFERENCES optic_memberships(optic_id,account_id)
);
CREATE INDEX ix_finance_optic_date ON finance_entries(optic_id,occurred_on DESC);
CREATE INDEX ix_finance_settlement ON finance_entries(optic_id,due_on,settled_on);

CREATE TABLE home_visits (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    lead_name VARCHAR(180) NOT NULL,
    lead_phone VARCHAR(24) NOT NULL,
    lead_email VARCHAR(254),
    lead_address TEXT NOT NULL,
    appointment_at TIMESTAMPTZ NOT NULL,
    notes TEXT,
    consent_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK(status IN('REQUESTED','CLAIMED','CONFIRMED','VISITED','CONVERTED','NOT_CONVERTED','CANCELLED')),
    assigned_to UUID,
    customer_id UUID,
    sale_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id),
    FOREIGN KEY(optic_id,assigned_to) REFERENCES optic_memberships(optic_id,account_id),
    FOREIGN KEY(optic_id,customer_id) REFERENCES customers(optic_id,id),
    FOREIGN KEY(optic_id,sale_id) REFERENCES sales(optic_id,id)
);
CREATE INDEX ix_visits_optic_status ON home_visits(optic_id,status,appointment_at);

CREATE TABLE visit_events (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL,
    visit_id UUID NOT NULL,
    actor_id UUID,
    event VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(optic_id,visit_id) REFERENCES home_visits(optic_id,id),
    FOREIGN KEY(optic_id,actor_id) REFERENCES optic_memberships(optic_id,account_id)
);

CREATE TABLE file_attachments (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    customer_id UUID,
    sale_id UUID,
    visit_id UUID,
    original_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(80) NOT NULL CHECK(content_type IN('application/pdf','image/jpeg','image/png','image/webp')),
    bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,id),
    FOREIGN KEY(optic_id,customer_id) REFERENCES customers(optic_id,id),
    FOREIGN KEY(optic_id,sale_id) REFERENCES sales(optic_id,id),
    FOREIGN KEY(optic_id,visit_id) REFERENCES home_visits(optic_id,id),
    CHECK(customer_id IS NOT NULL OR sale_id IS NOT NULL OR visit_id IS NOT NULL)
);

CREATE TABLE site_settings (
    optic_id UUID PRIMARY KEY REFERENCES optics(id),
    primary_color VARCHAR(7) NOT NULL DEFAULT '#116b6d',
    secondary_color VARCHAR(7) NOT NULL DEFAULT '#f3faf8',
    font VARCHAR(30) NOT NULL DEFAULT 'Manrope',
    template VARCHAR(30) NOT NULL DEFAULT 'classic',
    campaign_title VARCHAR(180) NOT NULL DEFAULT 'Sua ótica vai até você!',
    campaign_description VARCHAR(600) NOT NULL DEFAULT 'Já tem a receita? Agende uma visita em sua casa.',
    logo BYTEA,
    logo_content_type VARCHAR(60),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE platform_notices (
    id UUID PRIMARY KEY,
    optic_id UUID REFERENCES optics(id),
    subject VARCHAR(180) NOT NULL,
    message TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES platform_operators(account_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE notice_reads (
    notice_id UUID NOT NULL REFERENCES platform_notices(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(notice_id,account_id)
);
CREATE TABLE reminder_events (
    optic_id UUID NOT NULL REFERENCES optics(id),
    sale_id UUID NOT NULL,
    months INT NOT NULL CHECK(months IN (3,6,9,12)),
    channel VARCHAR(20) NOT NULL DEFAULT 'IN_APP',
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,sale_id,months,channel),
    FOREIGN KEY(optic_id,sale_id) REFERENCES sales(optic_id,id)
);
