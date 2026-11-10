package br.com.lcmtech.optics.shop.domain;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Unidades da federação com o prefixo IBGE dos municípios e o fuso horário oficial.
 * Usado para deduzir o fuso da ótica a partir do endereço, sem perguntar ao usuário.
 * Exceções locais (ex.: Fernando de Noronha, oeste do Amazonas) podem ser ajustadas depois.
 */
public enum BrazilianState {
    RO("11", "America/Porto_Velho"), AC("12", "America/Rio_Branco"), AM("13", "America/Manaus"),
    RR("14", "America/Boa_Vista"), PA("15", "America/Belem"), AP("16", "America/Belem"),
    TO("17", "America/Araguaina"), MA("21", "America/Fortaleza"), PI("22", "America/Fortaleza"),
    CE("23", "America/Fortaleza"), RN("24", "America/Fortaleza"), PB("25", "America/Fortaleza"),
    PE("26", "America/Recife"), AL("27", "America/Maceio"), SE("28", "America/Maceio"),
    BA("29", "America/Bahia"), MG("31", "America/Sao_Paulo"), ES("32", "America/Sao_Paulo"),
    RJ("33", "America/Sao_Paulo"), SP("35", "America/Sao_Paulo"), PR("41", "America/Sao_Paulo"),
    SC("42", "America/Sao_Paulo"), RS("43", "America/Sao_Paulo"), MS("50", "America/Campo_Grande"),
    MT("51", "America/Cuiaba"), GO("52", "America/Sao_Paulo"), DF("53", "America/Sao_Paulo");

    /** Fuso usado enquanto a ótica ainda não cadastrou o endereço. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");

    private final String ibgePrefix;
    private final ZoneId zone;

    BrazilianState(String ibgePrefix, String zone) {
        this.ibgePrefix = ibgePrefix;
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() { return zone; }

    /** O código IBGE do município começa com o código da UF (ex.: São Luís 2111300 → MA). */
    public boolean ownsMunicipality(String ibgeCode) {
        return ibgeCode != null && ibgeCode.matches("[0-9]{7}") && ibgeCode.startsWith(ibgePrefix);
    }

    public static Optional<BrazilianState> of(String uf) {
        if (uf == null) return Optional.empty();
        String code = uf.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(state -> state.name().equals(code)).findFirst();
    }
}
