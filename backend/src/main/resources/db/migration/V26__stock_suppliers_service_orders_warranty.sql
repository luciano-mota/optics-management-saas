-- Fornecedores e laboratórios.
CREATE TABLE suppliers (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    kind VARCHAR(12) NOT NULL DEFAULT 'FORNECEDOR' CHECK (kind IN ('FORNECEDOR','LABORATORIO','AMBOS')),
    document VARCHAR(14),
    phone VARCHAR(24),
    email VARCHAR(254),
    contact_name VARCHAR(120),
    notes VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, id)
);

-- Estoque opcional por produto (lente sob encomenda normalmente não controla).
ALTER TABLE catalog_items ADD COLUMN track_stock BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE catalog_items ADD COLUMN min_stock INT NOT NULL DEFAULT 0 CHECK (min_stock >= 0);

-- Movimentos de estoque (nada é apagado; o saldo é a soma).
CREATE TABLE stock_movements (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    item_id UUID NOT NULL,
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('ENTRADA','SAIDA_VENDA','DEVOLUCAO','AJUSTE')),
    quantity INT NOT NULL CHECK (quantity <> 0),
    unit_cost NUMERIC(10,2) CHECK (unit_cost IS NULL OR unit_cost >= 0),
    supplier_id UUID,
    sale_id UUID,
    note VARCHAR(200),
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, id),
    FOREIGN KEY (optic_id, item_id) REFERENCES catalog_items(optic_id, id),
    FOREIGN KEY (optic_id, supplier_id) REFERENCES suppliers(optic_id, id),
    FOREIGN KEY (optic_id, sale_id) REFERENCES sales(optic_id, id)
);
CREATE INDEX ix_stock_movements_item ON stock_movements(optic_id, item_id, created_at);

-- Ordens de serviço: pedido de lentes ao laboratório ou conserto de óculos do cliente.
CREATE TABLE service_orders (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    number BIGINT NOT NULL CHECK (number > 0),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('LENTES','CONSERTO')),
    sale_id UUID,
    customer_id UUID NOT NULL,
    supplier_id UUID,
    description VARCHAR(500) NOT NULL,
    price NUMERIC(10,2) CHECK (price IS NULL OR price >= 0),
    status VARCHAR(12) NOT NULL DEFAULT 'ABERTA' CHECK (status IN ('ABERTA','ENVIADA','RECEBIDA','ENTREGUE','CANCELADA')),
    sent_on DATE,
    expected_on DATE,
    received_on DATE,
    delivered_on DATE,
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, id),
    UNIQUE (optic_id, number),
    FOREIGN KEY (optic_id, sale_id) REFERENCES sales(optic_id, id),
    FOREIGN KEY (optic_id, customer_id) REFERENCES customers(optic_id, id),
    FOREIGN KEY (optic_id, supplier_id) REFERENCES suppliers(optic_id, id)
);
CREATE INDEX ix_service_orders_status ON service_orders(optic_id, status, expected_on);
CREATE TABLE service_order_events (
    optic_id UUID NOT NULL,
    id UUID NOT NULL,
    order_id UUID NOT NULL,
    previous_status VARCHAR(12),
    next_status VARCHAR(12) NOT NULL,
    actor_id UUID NOT NULL REFERENCES accounts(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (optic_id, id),
    FOREIGN KEY (optic_id, order_id) REFERENCES service_orders(optic_id, id)
);

-- Garantia da venda em meses (0 = sem garantia; nulo = não informada).
ALTER TABLE sales ADD COLUMN warranty_months INT CHECK (warranty_months IS NULL OR warranty_months BETWEEN 0 AND 60);
