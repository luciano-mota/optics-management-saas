package br.com.lcmtech.optics.publicsite.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lcmtech.optics.publicsite.domain.SiteContent.Faq;
import br.com.lcmtech.optics.publicsite.domain.SiteContent.Service;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SiteContentTest {
    private SiteContent content(String template, String instagram, List<Service> services, boolean visits, String visitTitle) {
        return new SiteContent("#5b4cf0", "#1e1b4b", template, "suave", "moderna", "  Bem-vindo  ", null, null, null,
                instagram, true, services, List.of(), List.of(new Faq("Pergunta?", "Resposta")), visits, visitTitle, null);
    }

    @Test
    void normalizaTextosEInstagram() {
        SiteContent site = content("classico", "https://instagram.com/otica.visao/", List.of(new Service(" Óculos ", "")), false, null);
        assertThat(site.headline()).isEqualTo("Bem-vindo");
        assertThat(site.instagram()).isEqualTo("otica.visao");
        assertThat(site.services().get(0).name()).isEqualTo("Óculos");
        assertThat(site.services().get(0).description()).isNull();
    }

    @Test
    void recusaModeloForaDaListaEServicoSemNome() {
        assertThatThrownBy(() -> content("inexistente", null, List.of(), false, null)).hasMessageContaining("modelo");
        assertThatThrownBy(() -> content("classico", null, List.of(new Service(" ", "x")), false, null))
                .hasMessageContaining("nome");
    }

    @Test
    void limitaQuantidadeDeServicos() {
        List<Service> many = Collections.nCopies(13, new Service("Óculos", null));
        assertThatThrownBy(() -> content("classico", null, many, false, null)).hasMessageContaining("12");
    }

    @Test
    void campanhaDeVisitaPrecisaDeTitulo() {
        assertThatThrownBy(() -> content("classico", null, List.of(), true, " ")).hasMessageContaining("domicílio");
        assertThat(content("classico", null, List.of(), true, "Vamos até você").homeVisitsEnabled()).isTrue();
    }
}
