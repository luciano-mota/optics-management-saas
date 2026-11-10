package br.com.lcmtech.optics.shop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OpticLocationTest {

    @Test
    void fusoEDeduzidoDaUf() {
        assertThat(OpticLocation.of("Rua A, 1 - São Luís/MA", "São Luís", "ma", "2111300").zone())
                .isEqualTo(ZoneId.of("America/Fortaleza"));
        assertThat(OpticLocation.of("Av. B, 2 - Manaus/AM", "Manaus", "AM", "1302603").zone())
                .isEqualTo(ZoneId.of("America/Manaus"));
        assertThat(OpticLocation.of("Rua C, 3 - São Paulo/SP", "São Paulo", "SP", null).zone())
                .isEqualTo(ZoneId.of("America/Sao_Paulo"));
    }

    @Test
    void codigoIbgeDeveSerDaMesmaUf() {
        // 3550308 = São Paulo/SP; não pode ser aceito para uma ótica no Maranhão.
        assertThatThrownBy(() -> OpticLocation.of("Rua A, 1", "São Luís", "MA", "3550308"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enderecoCidadeEUfSaoObrigatorios() {
        assertThatThrownBy(() -> OpticLocation.of(" ", "São Luís", "MA", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpticLocation.of("Rua A, 1", "", "MA", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpticLocation.of("Rua A, 1", "São Luís", "XX", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ibgeAusenteEPermitido() {
        // Quando o CEP vem da BrasilAPI, o código IBGE pode não estar disponível.
        assertThat(OpticLocation.of("Rua A, 1", "São Luís", "MA", "").municipalityIbge()).isNull();
    }
}
