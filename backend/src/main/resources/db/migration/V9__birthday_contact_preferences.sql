-- V9: preferência explícita de comunicação de aniversário e modal diária por conta/ótica.
ALTER TABLE customers ADD COLUMN birthday_whatsapp_opt_in BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE birthday_modal_views (
    optic_id UUID NOT NULL REFERENCES optics(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    viewed_on DATE NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(optic_id,account_id,viewed_on)
);
CREATE INDEX ix_customers_birthdays ON customers(optic_id,birth_date)
    WHERE birth_date IS NOT NULL;
