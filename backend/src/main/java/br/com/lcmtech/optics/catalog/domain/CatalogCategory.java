package br.com.lcmtech.optics.catalog.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Listas fechadas de tipo de lente e categoria de armação (iguais ao CHECK da migração V22). */
public final class CatalogCategory {
    public static final String NOT_INFORMED = "Não informado";

    public static final Map<String, String> LENS = ordered(
            "MONOFOCAL", "Monofocal (visão simples)", "MULTIFOCAL", "Multifocal (progressiva)",
            "BIFOCAL", "Bifocal", "OCUPACIONAL", "Ocupacional", "OUTRA", "Outro tipo");
    public static final Map<String, String> FRAME = ordered(
            "GRAU", "Grau (receituário)", "SOLAR", "Solar", "INFANTIL", "Infantil",
            "ESPORTIVA", "Esportiva", "OUTRA", "Outra categoria");

    private CatalogCategory() {}

    public static Map<String, String> of(String kind) {
        return "LENS".equals(kind) ? LENS : FRAME;
    }

    /** Valida a categoria para o tipo de produto; vazio vira "não informado" (null). */
    public static String require(String kind, String category) {
        if (category == null || category.isBlank()) return null;
        if (!of(kind).containsKey(category))
            throw new IllegalArgumentException("LENS".equals(kind) ? "Tipo de lente inválido." : "Categoria de armação inválida.");
        return category;
    }

    public static String label(String kind, String category) {
        return category == null ? NOT_INFORMED : of(kind).getOrDefault(category, category);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }
}
