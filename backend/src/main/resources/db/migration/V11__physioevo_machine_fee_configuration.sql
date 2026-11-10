-- PhysioEvo V21/V22 adaptation: prazo por crédito à vista e taxas separadas do parcelado.
-- Sem alterar vendas ou recebíveis antigos. Configurações antigas preservadas.
CREATE TABLE card_machine_credit_terms (
    optic_id UUID NOT NULL,
    machine_id UUID NOT NULL,
    days INTEGER NOT NULL CHECK(days BETWEEN 0 AND 60),
    fee NUMERIC(9,4) NOT NULL CHECK(fee >= 0 AND fee < 100),
    PRIMARY KEY(optic_id,machine_id,days),
    FOREIGN KEY(optic_id,machine_id) REFERENCES card_machines(optic_id,id)
);
INSERT INTO card_machine_credit_terms(optic_id,machine_id,days,fee)
SELECT optic_id,id,credit_days,credit_fee FROM card_machines;

ALTER TABLE card_machine_rates ADD COLUMN sale_fee NUMERIC(9,4);
ALTER TABLE card_machine_rates
    ADD COLUMN installment_fee NUMERIC(9,4) NOT NULL DEFAULT 0;
UPDATE card_machine_rates SET sale_fee=fee;
ALTER TABLE card_machine_rates ALTER COLUMN sale_fee SET NOT NULL;
ALTER TABLE card_machine_rates ADD CONSTRAINT chk_card_machine_fee_parts
    CHECK(sale_fee >= 0 AND installment_fee >= 0 AND fee = sale_fee + installment_fee);
