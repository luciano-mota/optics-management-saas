package br.com.lcmtech.optics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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

/** Onboarding de operador independente das óticas, somente por link de e-mail de uso único. */
@SpringBootTest(properties={
    "optics.operator.email=owner-optics-test@example.test",
    "optics.public-base-url=https://optics.example.test"
})
@AutoConfigureMockMvc
@Transactional
class PlatformOwnerOnboardingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean EmailDelivery mail;

    @Test
    void emailValidadoCriaOperadorSemOticaEInvalidaConvite() throws Exception {
        when(mail.available()).thenReturn(true);

        mvc.perform(post("/api/v1/public/platform-owner/request").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner-optics-test@example.test\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(mail,times(1)).send(
                org.mockito.ArgumentMatchers.eq("owner-optics-test@example.test"),
                anyString(),body.capture());

        var match=Pattern.compile("ownerInvite=([A-Za-z0-9_-]+)")
                .matcher(body.getValue());
        assertThat(match.find()).isTrue();
        String token=match.group(1);
        String payload="{\"token\":\""+token+"\",\"fullName\":\"Responsável LCM\","
                +"\"password\":\"UmaSenhaSegura2026!\",\"acceptedTerms\":true}";

        mvc.perform(post("/api/v1/public/platform-owner/complete").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());

        Integer activated=jdbc.queryForObject("""
                SELECT count(*) FROM platform_operators po JOIN accounts a ON a.id=po.account_id
                WHERE a.email='owner-optics-test@example.test' AND a.email_verified_at IS NOT NULL
                """,Integer.class);
        assertThat(activated).isEqualTo(1);

        var profile=mvc.perform(get("/api/v1/auth/me")
                .with(user("owner-optics-test@example.test")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(profile).contains("\"platformOperator\":true");
        assertThat(profile).contains("\"shops\":[]");

        mvc.perform(post("/api/v1/public/platform-owner/complete").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void naoDisparaEmailParaEnderecoQueNaoEhOperador() throws Exception {
        when(mail.available()).thenReturn(true);
        mvc.perform(post("/api/v1/public/platform-owner/request").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"outro@example.test\"}"))
            .andExpect(status().isOk());
        org.mockito.Mockito.verify(mail,org.mockito.Mockito.never())
                .send(anyString(),anyString(),anyString());
    }
}
