package br.com.lcmtech.optics.calendar;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feriados estaduais de data fixa (datas magnas, emancipação, criação do estado e afins), por UF.
 * Entram no calendário como feriado estadual (loja fechada por padrão); o Master revisa no início do mês se a
 * loja abre. Estados sem feriado próprio fixo (ou com regra variável) ficam fora e são marcados
 * manualmente. Revisar a lista anualmente com a legislação estadual.
 */
public final class StateHolidayCatalog {
    private StateHolidayCatalog() {}

    private record Entry(MonthDay day, String name) {}

    private static Entry on(int month, int day, String name) { return new Entry(MonthDay.of(month, day), name); }

    private static final Map<String, List<Entry>> BY_STATE = Map.ofEntries(
        Map.entry("AC", List.of(on(1, 23, "Dia do Evangélico"), on(6, 15, "Aniversário do Acre"),
            on(8, 6, "Início da Revolução Acreana"), on(9, 5, "Dia da Amazônia"),
            on(11, 17, "Assinatura do Tratado de Petrópolis"))),
        Map.entry("AL", List.of(on(6, 24, "São João"), on(6, 29, "São Pedro"),
            on(9, 16, "Emancipação Política de Alagoas"))),
        Map.entry("AM", List.of(on(9, 5, "Elevação do Amazonas à categoria de Província"))),
        Map.entry("AP", List.of(on(3, 19, "Dia de São José"), on(9, 13, "Criação do Território Federal do Amapá"))),
        Map.entry("BA", List.of(on(7, 2, "Independência da Bahia"))),
        Map.entry("CE", List.of(on(3, 19, "Dia de São José"), on(3, 25, "Data Magna do Ceará"))),
        Map.entry("DF", List.of(on(11, 30, "Dia do Evangélico"))),
        Map.entry("MA", List.of(on(7, 28, "Adesão do Maranhão à Independência do Brasil"))),
        Map.entry("MS", List.of(on(10, 11, "Criação do Estado de Mato Grosso do Sul"))),
        Map.entry("PA", List.of(on(8, 15, "Adesão do Pará à Independência do Brasil"))),
        Map.entry("PB", List.of(on(8, 5, "Fundação do Estado da Paraíba"))),
        Map.entry("PE", List.of(on(3, 6, "Data Magna de Pernambuco"))),
        Map.entry("PI", List.of(on(10, 19, "Dia do Piauí"))),
        Map.entry("PR", List.of(on(12, 19, "Emancipação Política do Paraná"))),
        Map.entry("RJ", List.of(on(4, 23, "Dia de São Jorge"))),
        Map.entry("RN", List.of(on(10, 3, "Mártires de Cunhaú e Uruaçu"))),
        Map.entry("RO", List.of(on(1, 4, "Criação do Estado de Rondônia"), on(6, 18, "Dia do Evangélico"))),
        Map.entry("RR", List.of(on(10, 5, "Criação do Estado de Roraima"))),
        Map.entry("RS", List.of(on(9, 20, "Revolução Farroupilha"))),
        Map.entry("SE", List.of(on(7, 8, "Emancipação Política de Sergipe"))),
        Map.entry("SP", List.of(on(7, 9, "Revolução Constitucionalista de 1932"))),
        Map.entry("TO", List.of(on(3, 18, "Autonomia do Estado do Tocantins"), on(9, 8, "Nossa Senhora da Natividade"),
            on(10, 5, "Criação do Estado do Tocantins")))
    );

    /** Feriados estaduais fixos da UF no ano; vazio quando a UF não tem feriado próprio de data fixa. */
    public static Map<LocalDate, String> forState(String uf, int year) {
        if (uf == null) return Map.of();
        Map<LocalDate, String> days = new LinkedHashMap<>();
        for (Entry entry : BY_STATE.getOrDefault(uf.toUpperCase(), List.of()))
            days.put(entry.day().atYear(year), entry.name());
        return Collections.unmodifiableMap(days);
    }

    public static boolean covers(String uf) {
        return uf != null && BY_STATE.containsKey(uf.toUpperCase());
    }
}
