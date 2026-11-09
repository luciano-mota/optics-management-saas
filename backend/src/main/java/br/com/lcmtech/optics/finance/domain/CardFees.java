package br.com.lcmtech.optics.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cálculo puro das taxas, valor líquido e recebíveis das maquininhas. */
public final class CardFees {
    private CardFees() {}

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Method { DINHEIRO, PIX, DEBITO, CREDITO }

    /** A taxa de 1x varia por prazo; as taxas de parcelamento variam por quantidade de parcelas. */
    public record Machine(BigDecimal debitFee, BigDecimal creditFee, Map<Integer, BigDecimal> installmentFees,
                          BigDecimal fixedFee, int debitDays, int creditDays, boolean anticipated,
                          Map<Integer, BigDecimal> creditTerms) {
        /** Construtor usado por clientes internos legados; preserva o prazo padrão. */
        public Machine(BigDecimal debitFee, BigDecimal creditFee, Map<Integer, BigDecimal> installmentFees,
                       BigDecimal fixedFee, int debitDays, int creditDays, boolean anticipated) {
            this(debitFee, creditFee, installmentFees, fixedFee, debitDays, creditDays, anticipated,
                Map.of(creditDays, creditFee));
        }
    }

    /** Depósito da maquininha: {@code amount} é o líquido que cai; {@code fee} é a parte da taxa desta parcela. */
    public record Receivable(int installment, LocalDate dueOn, BigDecimal amount, BigDecimal fee) {}

    public record Calculation(BigDecimal feePercent, BigDecimal chargedAmount, BigDecimal feeAmount, BigDecimal netAmount,
                              List<Receivable> receivables) {}

    public static Calculation calculate(BigDecimal amount, Method method, int installments, Machine machine,
                                        boolean passFee, LocalDate paidOn) {
        return calculate(amount, method, installments, machine, passFee, paidOn, null);
    }

    /** settlementDays é válido apenas no crédito à vista; null usa o prazo padrão. */
    public static Calculation calculate(BigDecimal amount, Method method, int installments, Machine machine,
                                        boolean passFee, LocalDate paidOn, Integer settlementDays) {
        if (installments < 1 || installments > 12) throw new IllegalArgumentException("Número de parcelas inválido");
        if (method != Method.CREDITO && settlementDays != null) {
            throw new IllegalArgumentException("Prazo de crédito informado para outra forma de pagamento");
        }
        if (method != Method.CREDITO && installments > 1) {
            throw new IllegalArgumentException("Parcelamento só no crédito");
        }
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (method == Method.DINHEIRO || method == Method.PIX) {
            return new Calculation(BigDecimal.ZERO.setScale(2), value, BigDecimal.ZERO.setScale(2), value, List.of());
        }
        if (machine == null) throw new IllegalArgumentException("Escolha a maquininha");
        int days = machine.creditDays();
        BigDecimal percent;
        if (method == Method.DEBITO) {
            percent = machine.debitFee();
        } else if (installments == 1) {
            days = settlementDays == null ? machine.creditDays() : settlementDays;
            percent = machine.creditTerms().get(days);
            if (percent == null) throw new IllegalArgumentException("Prazo de recebimento não configurado para esta maquininha");
        } else {
            if (settlementDays != null) {
                throw new IllegalArgumentException("Prazo de recebimento individual só vale para crédito à vista");
            }
            percent = machine.installmentFees().get(installments);
            if (percent == null) {
                throw new IllegalArgumentException("Taxa de crédito em " + installments + "x não configurada para esta maquininha");
            }
        }
        BigDecimal rate = percent.divide(HUNDRED, 10, RoundingMode.HALF_UP);
        BigDecimal fixed = machine.fixedFee().setScale(2, RoundingMode.HALF_UP);

        BigDecimal charged;
        BigDecimal fee;
        if (passFee) {
            charged = value.add(fixed).divide(BigDecimal.ONE.subtract(rate), 2, RoundingMode.HALF_UP);
            fee = charged.subtract(value);
        } else {
            charged = value;
            fee = value.multiply(rate).add(fixed).setScale(2, RoundingMode.HALF_UP).min(value);
        }
        BigDecimal net = charged.subtract(fee);

        List<Receivable> receivables = new ArrayList<>();
        if (method == Method.DEBITO) {
            receivables.add(new Receivable(1, paidOn.plusDays(machine.debitDays()), net, fee));
        } else if (installments == 1) {
            receivables.add(new Receivable(1, paidOn.plusDays(days), net, fee));
        } else if (machine.anticipated()) {
            receivables.add(new Receivable(1, paidOn.plusDays(machine.creditDays()), net, fee));
        } else {
            // Uma parcela por mês: a 1ª no prazo do crédito à vista da maquininha, as seguintes um mês depois da outra
            // (prazo "na hora" em 18/10 → 18/10, 18/11, 18/12; prazo de 30 dias → 17/11, 17/12, 17/01). A 1ª parcela
            // fica com os centavos que sobram da divisão, do líquido e da taxa.
            BigDecimal part = net.divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
            BigDecimal first = net.subtract(part.multiply(BigDecimal.valueOf(installments - 1)));
            BigDecimal feePart = fee.divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
            BigDecimal firstFee = fee.subtract(feePart.multiply(BigDecimal.valueOf(installments - 1)));
            LocalDate firstDue = paidOn.plusDays(machine.creditDays());
            for (int i = 1; i <= installments; i++) {
                receivables.add(new Receivable(i, firstDue.plusMonths(i - 1), i == 1 ? first : part, i == 1 ? firstFee : feePart));
            }
        }
        return new Calculation(percent.setScale(2, RoundingMode.HALF_UP), charged, fee, net, receivables);
    }
}
