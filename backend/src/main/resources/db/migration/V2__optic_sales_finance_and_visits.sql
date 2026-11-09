-- Bounded contexts: customers, catalog, sales, finance, home visits.
-- All references within a store use composite foreign keys.
CREATE TABLE customers (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    name VARCHAR(180) NOT NULL,
    cpf VARCHAR(11) NOT NULL,
    birth_date DATE NOT NULL,
    phone VARCHAR(24),
    address TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (optic_id,id),
    UNIQUE (optic_id,cpf)
);
CREATE INDEX ix_customers_search ON customers (optic_id, lower(name));

CREATE TABLE catalog_items (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    category VARCHAR(10) NOT NULL CHECK(category IN ('LENS','FRAME')),
    brand VARCHAR(140) NOT NULL,
    model VARCHAR(140),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(optic_id,id)
);
CREATE INDEX ix_catalog_type_brand ON catalog_items(optic_id,category,brand);

CREATE TABLE sales (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    customer_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    lens_id UUID NOT NULL,
    frame_id UUID NOT NULL,
    lens_snapshot VARCHAR(280) NOT NULL,
    frame_snapshot VARCHAR(280) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'AWAITING_PRODUCTION',
    cadence_months INT NOT NULL CHECK(cadence_months IN (6,12)),
    gross_amount NUMERIC(19,2) NOT NULL CHECK(gross_amount > 0),
    fee_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK(fee_amount >= 0),
    net_amount NUMERIC(19,2) NOT NULL CHECK(net_amount >= 0),
    payment_method VARCHAR(16) NOT NULL CHECK(payment_method IN ('PIX','CASH','DEBIT','CREDIT')),
    installments INT NOT NULL DEFAULT 1 CHECK (installments BETWEEN 1 AND 12),
    notes TEXT,
    expected_delivery_on DATE,
    delivered_at TIMESTAMPTZ,
    delivered_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(optic_id,id),
    FOREIGN KEY (optic_id,customer_id) REFERENCES customers(optic_id,id),
    FOREIGN KEY (optic_id,lens_id) REFERENCES catalog_items(optic_id,id),
    FOREIGN KEY (optic_id,frame_id) REFERENCES catalog_items(optic_id,id),
    FOREIGN KEY (optic_id,seller_id) REFERENCES optic_memberships(optic_id,account_id),
    FOREIGN KEY (optic_id,delivered_by) REFERENCES optic_memberships(optic_id,account_id)
);
CREATE INDEX ix_sales_period ON sales(optic_id,created_at DESC);
CREATE INDEX ix_sales_delivery ON sales(optic_id,status);
CREATE INDEX ix_sales_seller ON sales(optic_id,seller_id);
CREATE INDEX ix_sales_catalog ON sales(optic_id,lens_id,frame_id);

CREATE TABLE card_machines (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    name VARCHAR(120) NOT NULL,
    debit_fee NUMERIC(8,4) NOT NULL DEFAULT 0,
    credit_fee NUMERIC(8,4) NOT NULL DEFAULT 0,
    fixed_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    debit_days INT NOT NULL DEFAULT 1,
    credit_days INT NOT NULL DEFAULT 30,
    anticipated BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(optic_id,id)
);
CREATE TABLE machine_installment_fees (
    optic_id UUID NOT NULL,
    machine_id UUID NOT NULL,
    installments INT NOT NULL CHECK(installments BETWEEN 2 AND 12),
    fee_percent NUMERIC(8,4) NOT NULL CHECK(fee_percent >= 0 AND fee_percent < 100),
    PRIMARY KEY(machine_id,installments),
    FOREIGN KEY(optic_id,machine_id) REFERENCES card_machines(optic_id,id)
);

CREATE TABLE financial_entries (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    sale_id UUID,
    direction VARCHAR(8) NOT NULL CHECK(direction IN ('INCOME','EXPENSE')),
    category VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK(amount > 0),
    due_on DATE NOT NULL,
    settled_on DATE,
    reversed_at TIMESTAMPTZ,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(optic_id,id),
    FOREIGN KEY (optic_id,sale_id) REFERENCES sales(optic_id,id),
    FOREIGN KEY (optic_id,created_by) REFERENCES optic_memberships(optic_id,account_id)
);
CREATE INDEX ix_finance_period ON financial_entries(optic_id,due_on DESC,direction);

CREATE TABLE home_visits (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL REFERENCES optics(id),
    requester_name VARCHAR(180) NOT NULL,
    requester_phone VARCHAR(24) NOT NULL,
    address TEXT NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    assignee_id UUID,
    claimed_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    visited_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    sale_id UUID,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(optic_id,id),
    FOREIGN KEY (optic_id,assignee_id) REFERENCES optic_memberships(optic_id,account_id),
    FOREIGN KEY (optic_id,sale_id) REFERENCES sales(optic_id,id)
);
CREATE INDEX ix_visit_pending ON home_visits(optic_id,status,proposed_at);

CREATE TABLE home_visit_events (
    id UUID PRIMARY KEY,
    optic_id UUID NOT NULL,
    visit_id UUID NOT NULL,
    actor_id UUID,
    old_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (optic_id,visit_id) REFERENCES home_visits(optic_id,id),
    FOREIGN KEY (optic_id,actor_id) REFERENCES optic_memberships(optic_id,account_id)
);
CREATE INDEX ix_visit_events ON home_visit_events(optic_id,visit_id,occurred_at);

CREATE TABLE platform_announcements (
    id UUID PRIMARY KEY,
    optic_id UUID REFERENCES optics(id),
    title VARCHAR(180) NOT NULL,
    message TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES platform_operators(account_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);
CREATE TABLE announcement_reads (
    announcement_id UUID NOT NULL REFERENCES platform_announcements(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(announcement_id,account_id)
);
