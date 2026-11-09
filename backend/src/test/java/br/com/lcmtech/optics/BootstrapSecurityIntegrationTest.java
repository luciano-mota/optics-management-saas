package br.com.lcmtech.optics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BootstrapSecurityIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void statusPublicoFunciona() throws Exception {
        mvc.perform(get("/api/v1/public/status")).andExpect(status().isOk());
    }

    @Test
    void apiPrivadaNaoFicaExpostaDuranteBootstrap() throws Exception {
        mvc.perform(get("/api/v1/optics")).andExpect(status().isUnauthorized());
    }
}
