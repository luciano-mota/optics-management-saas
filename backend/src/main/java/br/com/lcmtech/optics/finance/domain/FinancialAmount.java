package br.com.lcmtech.optics.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetário positivo e imutável, em reais, sempre com 2 casas decimais. Use em vez de
 * double/float para dinheiro.
 */
public record FinancialAmount(BigDecimal value) {
    public FinancialAmount {
        Objects.requireNonNull(value, "value");
        if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("O valor aceita no máximo 2 casas decimais.");
        }
        value = value.setScale(2, RoundingMode.UNNECESSARY);
        if (value.signum() <= 0) throw new IllegalArgumentException("O valor deve ser maior que zero.");
        if (value.precision() > 19) throw new IllegalArgumentException("Valor alto demais.");
    }
}
