package br.com.lcmtech.optics.stock;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Saída automática de estoque na venda e devolução no cancelamento (só para produtos que controlam estoque). */
@Service
public class StockService {
    private final JdbcTemplate jdbc;

    public StockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Baixa 1 unidade da lente e da armação vendidas. Estoque pode ficar negativo (aviso na tela), sem travar a venda. */
    public void saleOut(UUID optic, UUID sale, UUID actor, UUID... items) {
        for (UUID item : items)
            jdbc.update("""
                    INSERT INTO stock_movements(optic_id,id,item_id,kind,quantity,sale_id,note,created_by)
                    SELECT ?,?,id,'SAIDA_VENDA',-1,?,'Venda',? FROM catalog_items WHERE optic_id=? AND id=? AND track_stock""",
                    optic, UUID.randomUUID(), sale, actor, optic, item);
    }

    /** Venda cancelada: devolve ao estoque o que saiu por ela (uma vez só). */
    public void saleReturn(UUID optic, UUID sale, UUID actor) {
        jdbc.update("""
                INSERT INTO stock_movements(optic_id,id,item_id,kind,quantity,sale_id,note,created_by)
                SELECT m.optic_id, gen_random_uuid(), m.item_id, 'DEVOLUCAO', -m.quantity, m.sale_id, 'Venda cancelada', ?
                FROM stock_movements m WHERE m.optic_id=? AND m.sale_id=? AND m.kind='SAIDA_VENDA'
                  AND NOT EXISTS (SELECT 1 FROM stock_movements d WHERE d.optic_id=m.optic_id AND d.sale_id=m.sale_id
                                  AND d.item_id=m.item_id AND d.kind='DEVOLUCAO')""", actor, optic, sale);
    }
}
