package br.com.lcmtech.optics.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lcmtech.optics.finance.domain.CardFees.Calculation;
import br.com.lcmtech.optics.finance.domain.CardFees.Machine;
import br.com.lcmtech.optics.finance.domain.CardFees.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CardFeesTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    static final Machine MACHINE = new Machine(new BigDecimal("1.99"), new BigDecimal("3.49"),
        Map.of(3, new BigDecimal("4.99")), BigDecimal.ZERO, 1, 30, false);

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    void creditoParceladoSemRepasseDescontaATaxaEGeraUmRecebivelPorParcela() {
        Calculation c = CardFees.calculate(money("600"), Method.CREDITO, 3, MACHINE, false, DAY);
        assertThat(c.feePercent()).isEqualByComparingTo("4.99");
        assertThat(c.chargedAmount()).isEqualByComparingTo("600.00");
        assertThat(c.feeAmount()).isEqualByComparingTo("29.94");
        assertThat(c.netAmount()).isEqualByComparingTo("570.06");
        assertThat(c.receivables()).hasSize(3);
        assertThat(c.receivables().get(0).dueOn()).isEqualTo(DAY.plusDays(30));
        assertThat(c.receivables().get(1).dueOn()).isEqualTo(DAY.plusDays(30).plusMonths(1));
        assertThat(c.receivables().get(2).dueOn()).isEqualTo(DAY.plusDays(30).plusMonths(2));
        assertThat(c.receivables().stream().map(CardFees.Receivable::fee).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("29.94");
        // Centavos que sobram da divisão vão na 1ª parcela; a soma bate com o líquido.
        assertThat(c.receivables().stream().map(CardFees.Receivable::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("570.06");
        assertThat(c.receivables().get(0).amount()).isEqualByComparingTo("190.02");
    }

    @Test
    void parcelaPorMesComCreditoNaHoraCaiNoDiaDaVendaENosMesesSeguintes() {
        // Teste E2E: R$ 300 em 3x, crédito à vista "na hora" e "cai uma parcela por mês" → 18/10, 18/11 e 18/12.
        Machine point = new Machine(new BigDecimal("1.99"), new BigDecimal("4.98"), Map.of(3, new BigDecimal("11.28")),
            BigDecimal.ZERO, 0, 0, false);
        LocalDate sale = LocalDate.of(2026, 10, 18);
        Calculation c = CardFees.calculate(money("300"), Method.CREDITO, 3, point, false, sale);
        assertThat(c.receivables()).extracting(CardFees.Receivable::dueOn)
            .containsExactly(sale, LocalDate.of(2026, 11, 18), LocalDate.of(2026, 12, 18));
        assertThat(c.receivables()).extracting(CardFees.Receivable::amount)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(money("88.72"), money("88.72"), money("88.72"));
        assertThat(c.receivables().stream().map(CardFees.Receivable::fee).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(c.feeAmount());
        // Fim de mês: 31/01 → 28/02 → 31/03.
        Calculation endOfMonth = CardFees.calculate(money("300"), Method.CREDITO, 3, point, false, LocalDate.of(2027, 1, 31));
        assertThat(endOfMonth.receivables()).extracting(CardFees.Receivable::dueOn)
            .containsExactly(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31));
    }

    @Test
    void comRepasseCobraValorDivididoPorUmMenosTaxaEAClinicaRecebeOValorCheio() {
        Calculation c = CardFees.calculate(money("600"), Method.CREDITO, 3, MACHINE, true, DAY);
        assertThat(c.chargedAmount()).isEqualByComparingTo("631.51"); // 600 × 1,0499 daria 629,94 e faltaria dinheiro
        assertThat(c.netAmount()).isEqualByComparingTo("600.00");
        assertThat(c.feeAmount()).isEqualByComparingTo("31.51");
    }

    @Test
    void debitoAntecipadoEDinheiro() {
        Calculation debit = CardFees.calculate(money("90"), Method.DEBITO, 1, MACHINE, false, DAY);
        assertThat(debit.feeAmount()).isEqualByComparingTo("1.79");
        assertThat(debit.receivables()).singleElement().satisfies(r -> assertThat(r.dueOn()).isEqualTo(DAY.plusDays(1)));

        Machine anticipated = new Machine(money("1.99"), money("3.49"), Map.of(6, money("3.49")), money("0.50"), 1, 1, true);
        Calculation credit = CardFees.calculate(money("300"), Method.CREDITO, 6, anticipated, false, DAY);
        assertThat(credit.feePercent()).isEqualByComparingTo("3.49"); // taxa 6x configurada explicitamente
        assertThat(credit.feeAmount()).isEqualByComparingTo("10.97"); // 10,47 + 0,50 fixo
        assertThat(credit.receivables()).singleElement().satisfies(r -> assertThat(r.dueOn()).isEqualTo(DAY.plusDays(1)));

        Calculation cash = CardFees.calculate(money("90"), Method.PIX, 1, null, true, DAY);
        assertThat(cash.netAmount()).isEqualByComparingTo("90.00");
        assertThat(cash.receivables()).isEmpty();
    }

    @Test
    void creditoAVistaEscolheTaxaEDataDeAcordoComPrazo() {
        Machine machine = new Machine(money("1.99"), money("2.00"), Map.of(3, money("4.99")),
            BigDecimal.ZERO, 1, 30, false,
            Map.of(0, money("5.00"), 14, money("3.50"), 30, money("2.00")));
        for (int days : new int[] { 0, 14, 30 }) {
            Calculation c = CardFees.calculate(money("1000"), Method.CREDITO, 1, machine, false, DAY, days);
            assertThat(c.receivables()).singleElement().satisfies(r -> assertThat(r.dueOn()).isEqualTo(DAY.plusDays(days)));
            assertThat(c.netAmount()).isEqualByComparingTo(days == 0 ? "950.00" : days == 14 ? "965.00" : "980.00");
        }
        assertThatThrownBy(() -> CardFees.calculate(money("100"), Method.CREDITO, 1, machine, false, DAY, 7))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não configurado");
        assertThatThrownBy(() -> CardFees.calculate(money("100"), Method.CREDITO, 2, machine, false, DAY))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2x");
        Calculation parcelado = CardFees.calculate(money("600"), Method.CREDITO, 3, machine, false, DAY);
        assertThat(parcelado.feePercent()).isEqualByComparingTo("4.99");
    }
}
