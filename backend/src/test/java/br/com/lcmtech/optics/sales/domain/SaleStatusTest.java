package br.com.lcmtech.optics.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SaleStatusTest {
    @Test
    void entregaRequerPassarPorPronto() {
        assertThat(SaleStatus.AWAITING_PRODUCTION.mayTransitionTo(SaleStatus.DELIVERED)).isFalse();
        assertThat(SaleStatus.AWAITING_PRODUCTION.transitionTo(SaleStatus.READY_FOR_DELIVERY))
                .isEqualTo(SaleStatus.READY_FOR_DELIVERY);
        assertThat(SaleStatus.READY_FOR_DELIVERY.transitionTo(SaleStatus.DELIVERED))
                .isEqualTo(SaleStatus.DELIVERED);
        assertThatThrownBy(() -> SaleStatus.DELIVERED.transitionTo(SaleStatus.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelamentoEhEstadoFinal() {
        assertThat(SaleStatus.AWAITING_PRODUCTION.mayTransitionTo(SaleStatus.CANCELLED)).isTrue();
        assertThat(SaleStatus.CANCELLED.mayTransitionTo(SaleStatus.READY_FOR_DELIVERY)).isFalse();
    }
}
