package br.com.lcmtech.optics.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.lcmtech.optics.identity.EmailDelivery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "optics.messages.scheduler-enabled=false")
@AutoConfigureMockMvc
@Transactional
class AutomaticMessagesIntegrationTest {
    /** 20/11/2026 às 09:00 em São Luís (UTC-3). */
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AutomaticMessages messages;
    @MockBean EmailDelivery mail;

    private record Shop(UUID optic, String master, String seller, UUID clinic, UUID otherClinic, UUID patient, UUID appointment,
                        UUID customer) {}

    private UUID account(UUID optic, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Pessoa',now())",
                id, id + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)", optic, id, role);
        return id;
    }

    private UUID patient(UUID optic, UUID by, String name, String email, boolean optIn) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO optometry_patients(optic_id,id,full_name,cpf,phone,email,reminder_opt_in,created_by) "
                + "VALUES(?,?,?,?,'98911112222',?,?,?)", optic, id, name, optIn ? "39053344705" : null, email, optIn, by);
        return id;
    }

    private UUID appointment(UUID optic, UUID patient, UUID clinic, String startUtc, String status, UUID by) {
        UUID id = UUID.randomUUID();
        Instant start = Instant.parse(startUtc);
        jdbc.update("INSERT INTO optical_appointments(optic_id,id,patient_id,clinic_id,starts_at,ends_at,status,created_by) "
                + "VALUES(?,?,?,?,?,?,?,?)", optic, id, patient, clinic, java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(start.plusSeconds(1800)), status, by);
        return id;
    }

    private Shop seed(boolean enable) {
        UUID optic = UUID.randomUUID();
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,contact_phone) VALUES(?,?,'Ótica Aviso','a@x.test','98933334444')",
                optic, optic.toString());
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO optic_business_calendar(optic_id,state_uf,municipality_ibge,municipality_name,open_weekdays,time_zone) "
                + "VALUES(?,'MA','2111300','São Luís','1,2,3,4,5,6','America/Fortaleza')", optic);
        UUID master = account(optic, "MASTER"), seller = account(optic, "SELLER");
        UUID clinic = UUID.randomUUID(), other = UUID.randomUUID();
        jdbc.update("INSERT INTO optical_partner_clinics(optic_id,id,name) VALUES(?,?,'Clínica Visão')", optic, clinic);
        jdbc.update("INSERT INTO optical_partner_clinics(optic_id,id,name) VALUES(?,?,'Clínica Outra')", optic, other);
        UUID maria = patient(optic, master, "Maria Souza Lima", "maria@example.test", true);
        UUID noConsent = patient(optic, master, "João Sem Aviso", "joao@example.test", false);
        UUID appt = appointment(optic, maria, clinic, "2026-11-21T13:00:00Z", "CONFIRMED", master); // amanhã 10:00
        appointment(optic, noConsent, clinic, "2026-11-21T14:00:00Z", "SCHEDULED", master);
        appointment(optic, maria, clinic, "2026-11-22T13:00:00Z", "SCHEDULED", master); // depois de amanhã
        appointment(optic, maria, other, "2026-11-21T15:00:00Z", "CANCELLED", master);
        UUID customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,cpf,birth_date,email,email_opt_in) "
                + "VALUES(?,?,'Carla Aniversário','12345678909','1990-11-20','carla@example.test',true)", optic, customer);
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,birth_date,email,email_opt_in) "
                + "VALUES(?,?,'Dora Sem Aviso','1985-11-20','dora@example.test',false)", optic, UUID.randomUUID());
        if (enable)
            jdbc.update("INSERT INTO optic_message_settings(optic_id,appointment_reminder_enabled,birthday_email_enabled,"
                    + "appointment_template,birthday_template) VALUES(?,true,true,?,?)", optic,
                    MessageTemplates.APPOINTMENT_DEFAULT, MessageTemplates.BIRTHDAY_DEFAULT);
        return new Shop(optic, master + "@example.test", seller + "@example.test", clinic, other, maria, appt, customer);
    }

    private String outbox(UUID optic, String kind, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM message_outbox WHERE optic_id=? AND kind=?", String.class, optic, kind);
    }

    @Test
    void geraUmLembreteEUmAniversarioSoParaQuemAutorizouSemDuplicar() {
        Shop s = seed(true);
        assertThat(messages.enqueue(NOW)).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_outbox WHERE optic_id=?", Integer.class, s.optic())).isEqualTo(2);
        assertThat(messages.enqueue(NOW)).isZero();
        String reminder = outbox(s.optic(), "APPOINTMENT_REMINDER", "body");
        assertThat(reminder).contains("Olá, Maria!").contains("21/11").contains("10:00").contains("Clínica Visão")
                .contains("(98) 93333-4444").doesNotContain("39053344705").doesNotContain("Souza");
        assertThat(outbox(s.optic(), "BIRTHDAY", "subject")).isEqualTo("Feliz aniversário, Carla!");
    }

    @Test
    void enviaComLinkDeDescadastroEODescadastroRetiraAAutorizacao() throws Exception {
        Shop s = seed(true);
        when(mail.available()).thenReturn(true);
        messages.enqueue(NOW);
        assertThat(messages.deliver(NOW)).isGreaterThanOrEqualTo(2);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("carla@example.test"), eq("Feliz aniversário, Carla!"), body.capture());
        assertThat(outbox(s.optic(), "BIRTHDAY", "status")).isEqualTo("SENT");
        Matcher link = Pattern.compile("/api/v1/public/messages/unsubscribe/([A-Za-z0-9_-]+)").matcher(body.getValue());
        assertThat(link.find()).isTrue();
        String path = "/api/v1/public/messages/unsubscribe/" + link.group(1);
        // Abrir o link (leitor de e-mail) não descadastra; o botão sim.
        mvc.perform(get(path));
        assertThat(jdbc.queryForObject("SELECT email_opt_in FROM customers WHERE id=?", Boolean.class, s.customer())).isTrue();
        String page = mvc.perform(post(path)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("Ótica Aviso");
        assertThat(jdbc.queryForObject("SELECT email_opt_in FROM customers WHERE id=?", Boolean.class, s.customer())).isFalse();
        assertThat(mvc.perform(post("/api/v1/public/messages/unsubscribe/invalido")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("Link inválido");
    }

    @Test
    void consultaCanceladaNaoEnviaEFalhasTentamDeNovoAteDesistir() {
        Shop s = seed(true);
        when(mail.available()).thenReturn(true);
        messages.enqueue(NOW);
        jdbc.update("UPDATE optical_appointments SET status='CANCELLED' WHERE id=?", s.appointment());
        doThrow(new IllegalStateException("fora do ar")).when(mail).send(eq("carla@example.test"), anyString(), anyString());
        messages.deliver(NOW);
        verify(mail, never()).send(eq("maria@example.test"), anyString(), anyString());
        assertThat(outbox(s.optic(), "APPOINTMENT_REMINDER", "status")).isEqualTo("CANCELLED");
        assertThat(outbox(s.optic(), "BIRTHDAY", "status")).isEqualTo("PENDING");
        for (int i = 0; i < AutomaticMessages.MAX_ATTEMPTS; i++) messages.deliver(NOW.plusSeconds(86400L * (i + 1)));
        assertThat(outbox(s.optic(), "BIRTHDAY", "status")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM message_outbox WHERE optic_id=? AND kind='BIRTHDAY'", Integer.class,
                s.optic())).isEqualTo(AutomaticMessages.MAX_ATTEMPTS);
    }

    @Test
    void semEmailConfiguradoFicaRegistradoComoFalha() {
        Shop s = seed(true);
        when(mail.available()).thenReturn(false);
        messages.enqueue(NOW);
        messages.deliver(NOW);
        assertThat(outbox(s.optic(), "BIRTHDAY", "last_error")).contains("não configurado");
        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void masterConfiguraComVersoesEValidaCampos() throws Exception {
        Shop s = seed(false);
        String valid = "{\"appointmentReminderEnabled\":true,\"birthdayEmailEnabled\":false,"
                + "\"appointmentTemplate\":\"Oi {nome}, consulta {data} às {hora}.\",\"birthdayTemplate\":\"Parabéns, {nome}!\"}";
        assertThat(mvc.perform(put("/api/v1/messages/settings").with(user(s.seller())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andReturn().getResponse().getStatus()).isEqualTo(403);
        String bad = valid.replace("{hora}", "{cpf}");
        assertThat(mvc.perform(put("/api/v1/messages/settings").with(user(s.master())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(bad)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("{cpf}");
        for (int i = 0; i < 2; i++)
            assertThat(mvc.perform(put("/api/v1/messages/settings").with(user(s.master())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(valid)).andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode settings = json.readTree(mvc.perform(get("/api/v1/messages/settings").with(user(s.master())))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(settings.path("version").asInt()).isEqualTo(2);
        assertThat(settings.path("optedInPatients").asInt()).isEqualTo(1);
        assertThat(json.readTree(mvc.perform(get("/api/v1/messages/settings/history").with(user(s.master())))
                .andReturn().getResponse().getContentAsString())).hasSize(2);
        messages.enqueue(NOW);
        assertThat(outbox(s.optic(), "APPOINTMENT_REMINDER", "body")).isEqualTo("Oi Maria, consulta 21/11 às 10:00.");
    }

    @Test
    void consentimentoNoCadastroEIsolamentoEntreOticas() throws Exception {
        Shop a = seed(true), b = seed(true);
        assertThat(mvc.perform(put("/api/v1/optometry/patients/" + a.patient() + "/reminder").with(user(b.master())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@x.test\",\"reminderOptIn\":true}"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(put("/api/v1/optometry/patients/" + a.patient() + "/reminder").with(user(a.master())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"reminderOptIn\":true}"))
                .andReturn().getResponse().getContentAsString()).contains("\"reminderOptIn\":false");
        assertThat(mvc.perform(put("/api/v1/optometry/patients/" + a.patient() + "/reminder").with(user(a.master())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nao-e-email\",\"reminderOptIn\":true}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(put("/api/v1/customers/" + a.customer()).with(user(b.master())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"X\",\"cpf\":\"12345678909\",\"birthDate\":\"1990-01-01\","
                        + "\"address\":\"Rua\",\"email\":\"x@x.test\",\"emailOptIn\":true}"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        messages.enqueue(NOW);
        JsonNode outboxB = json.readTree(mvc.perform(get("/api/v1/messages/outbox").with(user(b.master())))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        java.util.List<String> idsA = jdbc.queryForList("SELECT id::text FROM message_outbox WHERE optic_id=?", String.class, a.optic());
        assertThat(idsA).isNotEmpty();
        for (JsonNode row : outboxB) assertThat(idsA).doesNotContain(row.path("id").asText());
        assertThat(outboxB.size()).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM message_outbox WHERE optic_id=?",
                Integer.class, b.optic()));
    }

    // ------------------------------------------------------------ agenda da clínica

    @Test
    void agendaDaClinicaMostraSoAsConsultasDelaComNomeCurtoERevoga() throws Exception {
        Shop s = seed(false);
        jdbc.update("UPDATE optical_appointments SET starts_at=now()+interval '1 day', ends_at=now()+interval '1 day 30 minutes' "
                + "WHERE optic_id=?", s.optic());
        String url = json.readTree(mvc.perform(post("/api/v1/optometry/clinics/" + s.clinic() + "/calendar-feed")
                .with(user(s.master())).with(csrf())).andReturn().getResponse().getContentAsString()).path("url").asText();
        String path = url.substring(url.indexOf("/api/"));
        var response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("text/calendar");
        String ics = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").contains("SUMMARY:Consulta - Maria L.")
                .contains("SUMMARY:Consulta - João A.").doesNotContain("Souza").doesNotContain("39053344705")
                .doesNotContain("98911112222").doesNotContain("maria@example.test").doesNotContain("Clínica Outra");
        assertThat(ics.split("BEGIN:VEVENT").length - 1).isEqualTo(3); // a cancelada é da outra clínica
        for (String line : ics.split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        // Gerar de novo invalida o link anterior; revogar desliga.
        String second = json.readTree(mvc.perform(post("/api/v1/optometry/clinics/" + s.clinic() + "/calendar-feed")
                .with(user(s.master())).with(csrf())).andReturn().getResponse().getContentAsString()).path("url").asText();
        assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(404);
        String secondPath = second.substring(second.indexOf("/api/"));
        assertThat(mvc.perform(get(secondPath)).andReturn().getResponse().getStatus()).isEqualTo(200);
        mvc.perform(post("/api/v1/optometry/clinics/" + s.clinic() + "/calendar-feed/revoke").with(user(s.master())).with(csrf()));
        assertThat(mvc.perform(get(secondPath)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void outraOticaNaoGeraAgendaDaClinica() throws Exception {
        Shop a = seed(false), b = seed(false);
        assertThat(mvc.perform(post("/api/v1/optometry/clinics/" + a.clinic() + "/calendar-feed").with(user(b.master())).with(csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/optometry/clinics/" + a.clinic() + "/calendar-feed").with(user(b.master())))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/v1/optometry/clinics/" + a.clinic() + "/calendar-feed").with(user(a.seller())).with(csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void dobraLinhasLongasDoIcal() {
        StringBuilder out = new StringBuilder();
        br.com.lcmtech.optics.optometry.ClinicCalendarControllerAccess.line(out, "DESCRIPTION:" + "á".repeat(80));
        for (String line : out.toString().split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        assertThat(out.toString().replace("\r\n ", "")).isEqualTo("DESCRIPTION:" + "á".repeat(80) + "\r\n");
    }
}
