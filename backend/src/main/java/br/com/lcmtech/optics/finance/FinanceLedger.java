package br.com.lcmtech.optics.finance;

import br.com.lcmtech.optics.shop.OpticClock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Baixas de lançamentos: receber (ou pagar) parte ou o todo, e estornar uma baixa com data efetiva. Nada é apagado:
 * a baixa estornada continua no histórico. finance_entries.settled_on = data em que o lançamento ficou quitado.
 */
@Service
public class FinanceLedger {
    /** Soma das baixas ativas de um lançamento (alias e = finance_entries). */
    public static final String ACTIVE_PAID = "(SELECT coalesce(sum(st.amount),0) FROM finance_settlements st "
            + "WHERE st.optic_id=e.optic_id AND st.entry_id=e.id AND st.reversed_at IS NULL)";

    private final JdbcTemplate jdbc;
    private final OpticClock clock;

    public FinanceLedger(JdbcTemplate jdbc, OpticClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Result(UUID settlementId, BigDecimal paid, BigDecimal open, boolean settled) {}

    /**
     * Registra uma baixa. Sem valor, baixa o saldo inteiro. Recebível de cartão (com taxa) é baixado de uma vez,
     * porque a maquininha paga a parcela inteira; assim a taxa nunca é dividida nem duplicada.
     */
    @Transactional
    public Result settle(UUID optic, UUID actor, UUID entryId, BigDecimal amount, LocalDate paidOn, String note) {
        Map<String, Object> entry = lock(optic, entryId);
        if (entry.get("reversed_at") != null) throw new IllegalArgumentException("Lançamento estornado não recebe baixa.");
        BigDecimal net = (BigDecimal) entry.get("net_amount"), paid = paid(optic, entryId), open = net.subtract(paid);
        if (open.signum() <= 0) throw new IllegalArgumentException("Este lançamento já está quitado.");
        BigDecimal value = amount == null ? open : amount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (value.signum() <= 0) throw new IllegalArgumentException("Informe um valor maior que zero.");
        if (value.compareTo(open) > 0)
            throw new IllegalArgumentException("O valor é maior que o saldo em aberto (" + money(open) + ").");
        if (((BigDecimal) entry.get("fee_amount")).signum() > 0 && value.compareTo(open) != 0)
            throw new IllegalArgumentException("Recebimento de cartão é registrado de uma vez, pelo valor da parcela.");
        LocalDate today = clock.today(optic);
        LocalDate day = paidOn == null ? today : paidOn;
        if (day.isAfter(today)) throw new IllegalArgumentException("A data do recebimento não pode ser futura.");
        if (note != null && note.length() > 200) throw new IllegalArgumentException("Observação muito longa.");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO finance_settlements(optic_id,id,entry_id,amount,paid_on,note,created_by) VALUES(?,?,?,?,?,?,?)",
                optic, id, entryId, value, day, note == null || note.isBlank() ? null : note.trim(), actor);
        BigDecimal remaining = open.subtract(value);
        if (remaining.signum() == 0)
            jdbc.update("UPDATE finance_entries SET settled_on=? WHERE optic_id=? AND id=?", day, optic, entryId);
        return new Result(id, paid.add(value), remaining, remaining.signum() == 0);
    }

    /** Estorna uma baixa (o dinheiro voltou ou foi lançado errado). O lançamento volta a ter saldo em aberto. */
    @Transactional
    public Result reverse(UUID optic, UUID actor, UUID settlementId, LocalDate reversedOn, String reason) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT entry_id,paid_on,reversed_at FROM finance_settlements WHERE optic_id=? AND id=? FOR UPDATE",
                optic, settlementId);
        if (found.isEmpty()) throw new NoSuchElementException("Recebimento não encontrado.");
        if (found.get(0).get("reversed_at") != null) throw new IllegalArgumentException("Este recebimento já foi estornado.");
        LocalDate today = clock.today(optic);
        LocalDate day = reversedOn == null ? today : reversedOn;
        if (day.isAfter(today)) throw new IllegalArgumentException("A data do estorno não pode ser futura.");
        if (day.isBefore(((java.sql.Date) found.get(0).get("paid_on")).toLocalDate()))
            throw new IllegalArgumentException("O estorno não pode ser anterior ao recebimento.");
        if (reason != null && reason.length() > 200) throw new IllegalArgumentException("Motivo muito longo.");
        UUID entryId = (UUID) found.get(0).get("entry_id");
        jdbc.update("UPDATE finance_settlements SET reversed_at=now(),reversed_on=?,reversed_by=?,reversal_reason=? "
                + "WHERE optic_id=? AND id=?", day, actor, reason == null || reason.isBlank() ? null : reason.trim(), optic, settlementId);
        jdbc.update("UPDATE finance_entries SET settled_on=NULL WHERE optic_id=? AND id=?", optic, entryId);
        BigDecimal net = jdbc.queryForObject("SELECT net_amount FROM finance_entries WHERE optic_id=? AND id=?",
                BigDecimal.class, optic, entryId);
        BigDecimal paid = paid(optic, entryId);
        return new Result(settlementId, paid, net.subtract(paid), false);
    }

    public List<Map<String, Object>> history(UUID optic, UUID entryId) {
        lockless(optic, entryId);
        return jdbc.queryForList("""
                SELECT st.id, st.amount, st.paid_on, st.note, st.created_at, a.full_name AS created_by_name,
                       st.reversed_at, st.reversed_on, st.reversal_reason, r.full_name AS reversed_by_name
                FROM finance_settlements st JOIN accounts a ON a.id=st.created_by
                LEFT JOIN accounts r ON r.id=st.reversed_by
                WHERE st.optic_id=? AND st.entry_id=? ORDER BY st.paid_on, st.created_at
                """, optic, entryId);
    }

    private Map<String, Object> lock(UUID optic, UUID entryId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT net_amount,fee_amount,reversed_at FROM finance_entries WHERE optic_id=? AND id=? FOR UPDATE",
                optic, entryId);
        if (rows.isEmpty()) throw new NoSuchElementException("Lançamento não encontrado.");
        return rows.get(0);
    }

    private void lockless(UUID optic, UUID entryId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE optic_id=? AND id=?", Integer.class,
                optic, entryId);
        if (n == null || n == 0) throw new NoSuchElementException("Lançamento não encontrado.");
    }

    private BigDecimal paid(UUID optic, UUID entryId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM finance_settlements "
                + "WHERE optic_id=? AND entry_id=? AND reversed_at IS NULL", BigDecimal.class, optic, entryId);
    }

    private static String money(BigDecimal v) {
        return "R$ " + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
