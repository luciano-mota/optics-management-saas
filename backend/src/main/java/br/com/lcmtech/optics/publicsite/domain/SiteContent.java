package br.com.lcmtech.optics.publicsite.domain;

import java.util.List;
import java.util.Set;

/**
 * Conteúdo editável do site da ótica (modelo do PhysioEvo), validado antes de gravar.
 * Listas fechadas para modelo, clima e fontes, iguais às do frontend.
 */
public record SiteContent(
        String primaryColor, String secondaryColor, String template, String mood, String fonts,
        String headline, String subtitle, String about, String hours, String instagram, boolean showMap,
        List<Service> services, List<Testimonial> testimonials, List<Faq> faqs,
        boolean homeVisitsEnabled, String homeVisitTitle, String homeVisitDescription) {

    public record Service(String name, String description) {}
    public record Testimonial(String author, String text) {}
    public record Faq(String question, String answer) {}

    public static final Set<String> TEMPLATES = Set.of("classico", "imersivo", "editorial", "acolhedor");
    public static final Set<String> MOODS = Set.of("claro", "suave", "escuro");
    public static final Set<String> FONTS = Set.of("elegante", "classica", "moderna", "geometrica", "suave", "profissional");

    public SiteContent {
        if (!hex(primaryColor) || !hex(secondaryColor)) throw new IllegalArgumentException("Informe cores válidas (#RRGGBB).");
        if (!TEMPLATES.contains(template)) throw new IllegalArgumentException("Escolha um modelo de site disponível.");
        if (!MOODS.contains(mood)) throw new IllegalArgumentException("Escolha um clima disponível.");
        if (!FONTS.contains(fonts)) throw new IllegalArgumentException("Escolha um par de fontes disponível.");
        headline = required(headline, 180, "Informe o título principal do site (até 180 caracteres).");
        subtitle = optional(subtitle, 600, "Subtítulo até 600 caracteres.");
        about = optional(about, 2000, "Texto \"Sobre a ótica\" até 2000 caracteres.");
        hours = optional(hours, 300, "Horários até 300 caracteres.");
        instagram = optional(instagram, 60, "Instagram inválido.");
        if (instagram != null) {
            instagram = instagram.replaceFirst("^https?://(www\\.)?instagram\\.com/", "").replaceAll("[@/]", "");
            if (!instagram.matches("[A-Za-z0-9._]{1,30}")) throw new IllegalArgumentException("Informe o Instagram como @usuario.");
        }
        services = list(services, 12, "Até 12 serviços.").stream()
                .map(s -> new Service(required(s.name(), 80, "Cada serviço precisa de nome (até 80 caracteres)."),
                        optional(s.description(), 300, "Descrição do serviço até 300 caracteres.")))
                .toList();
        testimonials = list(testimonials, 12, "Até 12 depoimentos.").stream()
                .map(t -> new Testimonial(required(t.author(), 80, "Informe quem deu o depoimento."),
                        required(t.text(), 500, "Depoimento até 500 caracteres.")))
                .toList();
        faqs = list(faqs, 15, "Até 15 perguntas frequentes.").stream()
                .map(f -> new Faq(required(f.question(), 200, "Cada pergunta precisa de texto (até 200 caracteres)."),
                        required(f.answer(), 800, "Cada pergunta precisa de resposta (até 800 caracteres).")))
                .toList();
        homeVisitTitle = optional(homeVisitTitle, 140, "Título do atendimento em domicílio até 140 caracteres.");
        homeVisitDescription = optional(homeVisitDescription, 600, "Texto do atendimento em domicílio até 600 caracteres.");
        if (homeVisitsEnabled && homeVisitTitle == null)
            throw new IllegalArgumentException("Informe o título da campanha de atendimento em domicílio.");
    }

    private static boolean hex(String value) { return value != null && value.matches("#[A-Fa-f0-9]{6}"); }

    private static <T> List<T> list(List<T> value, int max, String message) {
        if (value == null) return List.of();
        if (value.size() > max) throw new IllegalArgumentException(message);
        return value.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static String required(String value, int max, String message) {
        String text = optional(value, max, message);
        if (text == null) throw new IllegalArgumentException(message);
        return text;
    }

    private static String optional(String value, int max, String message) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.length() > max) throw new IllegalArgumentException(message);
        return text;
    }
}
