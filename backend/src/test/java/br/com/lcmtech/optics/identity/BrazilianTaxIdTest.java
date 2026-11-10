package br.com.lcmtech.optics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BrazilianTaxIdTest {
    @Test
    void verificaCnpjDoMasterNumericoEAlfanumerico() {
        assertThat(BrazilianTaxId.normalizeCnpj("11.222.333/0001-81"))
                .isEqualTo("11222333000181");
        assertThat(BrazilianTaxId.validCnpj("11222333000181")).isTrue();
        assertThat(BrazilianTaxId.validCnpj("12ABC34501DE35")).isTrue();
        assertThat(BrazilianTaxId.validCnpj("11222333000182")).isFalse();
        assertThat(BrazilianTaxId.validCnpj("00000000000000")).isFalse();
    }
}
