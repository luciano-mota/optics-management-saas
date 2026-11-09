package br.com.lcmtech.optics.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint público sem qualquer dado de cliente; não equivale a login nem healthcheck de banco.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicStatusController {
    @GetMapping("/status")
    public Status status() {
        return new Status("optics-management", "bootstrap");
    }

    public record Status(String application, String stage) {}
}
