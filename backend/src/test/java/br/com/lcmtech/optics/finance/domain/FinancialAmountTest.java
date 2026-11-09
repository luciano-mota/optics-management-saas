package br.com.lcmtech.optics.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinancialAmountTest {
    @Test
    void usaDuasCasasDecimais() {
        assertThat(new FinancialAmount(new BigDecimal("12")).value())
                .isEqualByComparingTo("12.00");
    }
    @Test
    void rejeitaValorZeroENumeroFracionarioInvalido() {
        assertThatThrownBy(() -> new FinancialAmount(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FinancialAmount(new BigDecimal("1.234")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
