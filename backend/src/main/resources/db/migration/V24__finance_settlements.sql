-- Baixas (recebimentos e pagamentos) por lançamento: permite receber parte de uma parcela e estornar uma
-- baixa com data efetiva, sem apagar nada. finance_entries.settled_on passa a significar "quitado por
-- completo em" e é mantido pelo sistema.
CREATE TABLE finance_settlements (
    optic_id UUID NOT NULL REFERENCES optics(id),
    id UUID NOT NULL,
    entry_id UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    paid_on DATE NOT NULL,
    note VARCHAR(200),
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reversed_at TIMESTAMPTZ,
    reversed_on DATE,
    reversed_by UUID REFERENCES accounts(id),
    reversal_reason VARCHAR(200),
    PRIMARY KEY (optic_id, id),
    FOREIGN KEY (optic_id, entry_id) REFERENCES finance_entries(optic_id, id),
    CHECK ((reversed_at IS NULL) = (reversed_on IS NULL))
);
CREATE INDEX ix_settlements_entry ON finance_settlements(optic_id, entry_id);
CREATE INDEX ix_settlements_paid ON finance_settlements(optic_id, paid_on);

-- Histórico: cada lançamento já liquidado vira uma baixa integral na mesma data.
INSERT INTO finance_settlements(optic_id, id, entry_id, amount, paid_on, created_by, created_at)
SELECT optic_id, gen_random_uuid(), id, net_amount, settled_on, created_by, created_at
FROM finance_entries WHERE settled_on IS NOT NULL AND net_amount > 0;

-- Lançamento criado já liquidado (venda em Pix/dinheiro, lançamento manual pago) ganha a baixa integral.
CREATE FUNCTION finance_entry_settled_on_insert() RETURNS trigger AS $$
BEGIN
    IF NEW.settled_on IS NOT NULL AND NEW.net_amount > 0 THEN
        INSERT INTO finance_settlements(optic_id, id, entry_id, amount, paid_on, created_by, created_at)
        VALUES (NEW.optic_id, gen_random_uuid(), NEW.id, NEW.net_amount, NEW.settled_on, NEW.created_by, NEW.created_at);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_finance_entry_settled_on_insert AFTER INSERT ON finance_entries
    FOR EACH ROW EXECUTE FUNCTION finance_entry_settled_on_insert();

-- Recibo passa a ser por baixa (pagamento parcial tem o próprio recibo, com o saldo em aberto).
ALTER TABLE sale_receipts ADD COLUMN settlement_id UUID;
UPDATE sale_receipts r SET settlement_id = (
    SELECT s.id FROM finance_settlements s WHERE s.optic_id = r.optic_id AND s.entry_id = r.finance_entry_id
    ORDER BY s.created_at LIMIT 1);
ALTER TABLE sale_receipts ADD CONSTRAINT fk_sale_receipts_settlement
    FOREIGN KEY (optic_id, settlement_id) REFERENCES finance_settlements(optic_id, id);
ALTER TABLE sale_receipts DROP CONSTRAINT sale_receipts_optic_id_finance_entry_id_key;
CREATE UNIQUE INDEX uq_sale_receipts_settlement ON sale_receipts(optic_id, settlement_id) WHERE settlement_id IS NOT NULL;
