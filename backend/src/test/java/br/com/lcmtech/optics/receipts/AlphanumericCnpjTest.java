package br.com.lcmtech.optics.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AlphanumericCnpjTest {
    @Test
    void aceitaCnpjNumericoEAlfanumericoComDigitosValidos(){
        assertThat(ReceiptController.validCnpj("11222333000181")).isTrue();
        // Exemplo do manual oficial da Receita Federal/SERPRO: 12.ABC.345/01DE-35
        assertThat(ReceiptController.validCnpj("12ABC34501DE35")).isTrue();
    }

    @Test
    void rejeitaSequenciasEVerificadoresIncorretos() {
        assertThat(ReceiptController.validCnpj("00000000000000")).isFalse();
        assertThat(ReceiptController.validCnpj("12ABC34501DE36")).isFalse();
        assertThat(ReceiptController.validCnpj("11222333000182")).isFalse();
    }
}
