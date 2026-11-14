package br.com.lcmtech.optics.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Dados de cadastro que saem no recibo e nos relatórios: CPF com dígito verificador, nascimento e agenda coerentes. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerAndPatientValidationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private record Shop(UUID optic, String email, UUID clinic, UUID patient) {}

    private Shop seed() {
        UUID optic = UUID.randomUUID(), account = UUID.randomUUID(), clinic = UUID.randomUUID(), patient = UUID.randomUUID();
        String email = account + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Dona',now())",
                account, email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica Cadastro',?)", optic, optic.toString(), email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO optical_partner_clinics(optic_id,id,name) VALUES(?,?,'Clínica')", optic, clinic);
        jdbc.update("INSERT INTO optometry_patients(optic_id,id,full_name,created_by) VALUES(?,?,'Paciente',?)", optic, patient, account);
        return new Shop(optic, email, clinic, patient);
    }

    private int send(String who, String path, String body) throws Exception {
        return mvc.perform(post(path).with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getStatus();
    }

    private static String customer(String cpf, String birth) {
        return "{\"fullName\":\"Ana Cliente\",\"cpf\":\"" + cpf + "\",\"birthDate\":\"" + birth + "\",\"address\":\"Rua A, 1\"}";
    }

    @Test
    void clienteExigeCpfComDigitoVerificadorENascimentoPassado() throws Exception {
        Shop s = seed();
        assertThat(send(s.email(), "/api/v1/customers", customer("111.111.111-11", "1990-01-01"))).isEqualTo(400);
        assertThat(send(s.email(), "/api/v1/customers", customer("390.533.447-06", "1990-01-01"))).isEqualTo(400); // dígito errado
        assertThat(send(s.email(), "/api/v1/customers", customer("390.533.447-05", "2099-01-01"))).isEqualTo(400);
        assertThat(send(s.email(), "/api/v1/customers", customer("390.533.447-05", "1990-01-01"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE optic_id=?", Integer.class, s.optic())).isEqualTo(1);
    }

    @Test
    void pacienteComCpfInvalidoOuNascimentoFuturoERecusadoMasCpfContinuaOpcional() throws Exception {
        Shop s = seed();
        assertThat(send(s.email(), "/api/v1/optometry/patients", "{\"fullName\":\"P\",\"cpf\":\"12345678900\"}")).isEqualTo(400);
        assertThat(send(s.email(), "/api/v1/optometry/patients", "{\"fullName\":\"P\",\"birthDate\":\"2099-01-01\"}")).isEqualTo(400);
        assertThat(send(s.email(), "/api/v1/optometry/patients", "{\"fullName\":\"P\"}")).isEqualTo(200);
    }

    @Test
    void consultaNaoPodeComecarEmDataPassada() throws Exception {
        Shop s = seed();
        LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        String yesterday = today.minusDays(1) + "T10:00:00-03:00", tomorrow = today.plusDays(1) + "T10:00:00-03:00";
        String base = "{\"patientId\":\"" + s.patient() + "\",\"clinicId\":\"" + s.clinic() + "\",";
        assertThat(send(s.email(), "/api/v1/optometry/appointments", base + "\"startsAt\":\"" + yesterday + "\",\"endsAt\":\""
                + yesterday.replace("10:00", "10:30") + "\"}")).isEqualTo(400);
        assertThat(send(s.email(), "/api/v1/optometry/appointments", base + "\"startsAt\":\"" + tomorrow + "\",\"endsAt\":\""
                + tomorrow.replace("10:00", "10:30") + "\"}")).isEqualTo(200);
    }
}
