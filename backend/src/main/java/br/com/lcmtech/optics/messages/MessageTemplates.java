package br.com.lcmtech.optics.messages;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Textos dos avisos automáticos. Campos entre chaves são trocados pelos dados; nunca CPF nem receita. */
public final class MessageTemplates {
    public static final String APPOINTMENT_DEFAULT = "Olá, {nome}! Lembrete da {otica}: sua consulta de vista é amanhã, "
            + "{data}, às {hora}, na {clinica}. Se precisar remarcar, fale com a ótica pelo telefone {telefone}.";
    public static final String BIRTHDAY_DEFAULT = "Feliz aniversário, {nome}! A equipe da {otica} deseja um dia muito "
            + "especial para você. Conte sempre com a gente para cuidar da sua visão.";
    static final java.util.Set<String> APPOINTMENT_FIELDS = java.util.Set.of("nome", "otica", "data", "hora", "clinica", "telefone");
    static final java.util.Set<String> BIRTHDAY_FIELDS = java.util.Set.of("nome", "otica", "telefone");
    private static final Pattern FIELD = Pattern.compile("\\{([a-z]+)}");

    private MessageTemplates() {}

    /** Recusa texto vazio, longo demais ou com campo que não existe para aquele aviso. */
    public static String validate(String template, java.util.Set<String> allowed, String label) {
        if (template == null || template.isBlank()) throw new IllegalArgumentException("Escreva o texto do " + label + ".");
        String text = template.trim();
        if (text.length() > 1000) throw new IllegalArgumentException("O texto do " + label + " deve ter até 1.000 caracteres.");
        Matcher m = FIELD.matcher(text);
        while (m.find())
            if (!allowed.contains(m.group(1)))
                throw new IllegalArgumentException("Campo {" + m.group(1) + "} não existe no " + label + ". Use: "
                        + String.join(", ", allowed.stream().sorted().map(f -> "{" + f + "}").toList()) + ".");
        return text;
    }

    public static String render(String template, Map<String, String> values) {
        Matcher m = FIELD.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        m.appendTail(out);
        return out.toString();
    }

    /** Só o primeiro nome do cliente no texto. */
    public static String firstName(String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }
}
