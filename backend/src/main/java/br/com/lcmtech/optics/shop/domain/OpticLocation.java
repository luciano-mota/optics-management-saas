package br.com.lcmtech.optics.shop.domain;

import java.time.ZoneId;

/**
 * Endereço da ótica e a localização derivada dele (cidade, UF e município IBGE).
 * É a única fonte para feriados locais e para o fuso horário da ótica.
 */
public record OpticLocation(String address, String city, BrazilianState state, String municipalityIbge) {

    public static final int MAX_ADDRESS = 1000;
    public static final int MAX_CITY = 160;

    public OpticLocation {
        if (address == null || address.isBlank())
            throw new IllegalArgumentException("Informe o endereço da ótica.");
        if (address.length() > MAX_ADDRESS)
            throw new IllegalArgumentException("Endereço da ótica muito longo.");
        if (city == null || city.isBlank() || city.length() > MAX_CITY)
            throw new IllegalArgumentException("Informe a cidade da ótica.");
        if (state == null)
            throw new IllegalArgumentException("Informe a UF da ótica.");
        if (municipalityIbge != null && !state.ownsMunicipality(municipalityIbge))
            throw new IllegalArgumentException("A cidade não corresponde à UF informada. Confira o CEP.");
        address = address.trim();
        city = city.trim();
    }

    /** Monta a localização a partir do que o formulário de endereço enviou. */
    public static OpticLocation of(String address, String city, String uf, String ibge) {
        BrazilianState state = BrazilianState.of(uf)
                .orElseThrow(() -> new IllegalArgumentException("Informe uma UF válida para a ótica."));
        String code = ibge == null || ibge.isBlank() ? null : ibge.trim();
        return new OpticLocation(address, city, state, code);
    }

    public ZoneId zone() { return state.zone(); }
}
