package br.com.lcmtech.optics.shop;

import br.com.lcmtech.optics.security.OpticContext;
import br.com.lcmtech.optics.shop.domain.OpticLocation;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Dados cadastrais da ótica ativa (hoje: endereço e localização derivada). */
@RestController
@RequestMapping("/api/v1/optic-profile")
public class OpticProfileController {
    private final OpticContext context;
    private final OpticLocationService locations;

    public OpticProfileController(OpticContext context, OpticLocationService locations) {
        this.context = context;
        this.locations = locations;
    }

    /** Endereço vindo do formulário com CEP: texto completo + cidade, UF e IBGE do CEP. */
    public record AddressInput(String address, String city, String uf, String ibge) {
        public OpticLocation toLocation() { return OpticLocation.of(address, city, uf, ibge); }
    }

    @GetMapping
    public Map<String, Object> get(Authentication auth, HttpSession session) {
        return locations.describe(context.optic(auth, session));
    }

    @PutMapping("/address")
    public Map<String, Boolean> updateAddress(@RequestBody AddressInput input,
                                              Authentication auth, HttpSession session) {
        context.requireMaster(auth, session);
        if (input == null) throw new IllegalArgumentException("Informe o endereço da ótica.");
        UUID optic = context.optic(auth, session);
        locations.save(optic, context.accountId(auth), input.toLocation());
        return Map.of("saved", true);
    }
}
