package br.com.lcmtech.optics.security;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Confere que o ID do endereço é desta ótica antes de listar dados ligados a ele. Registro de outra ótica (ou que não
 * existe) responde 404, sem revelar se existe em outro lugar. A tabela vem sempre do código, nunca do cliente.
 */
public final class Owned {
    private static final Set<String> TABLES = Set.of("customers", "home_visits", "sales", "receipt_share_tokens");

    private Owned() {}

    public static void require(JdbcTemplate jdbc, String table, UUID optic, UUID id, String notFound) {
        if (!TABLES.contains(table)) throw new IllegalStateException("Tabela não permitida: " + table);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE optic_id=? AND id=?", Integer.class, optic, id);
        if (n == null || n == 0) throw new NoSuchElementException(notFound);
    }
}
