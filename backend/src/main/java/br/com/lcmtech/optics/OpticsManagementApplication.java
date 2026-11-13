package br.com.lcmtech.optics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class OpticsManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpticsManagementApplication.class, args);
    }
}
