package com.arcbank.cbs.transaccion;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

import jakarta.annotation.PostConstruct;

@SpringBootApplication
@EnableFeignClients
public class TransaccionApplication {

    public static void main(String[] args) {
        // Establecer zona horaria de Ecuador antes de iniciar
        TimeZone.setDefault(TimeZone.getTimeZone("America/Guayaquil"));
        SpringApplication.run(TransaccionApplication.class, args);
    }

    @PostConstruct
    public void init() {
        // Reforzar zona horaria después de inicialización
        TimeZone.setDefault(TimeZone.getTimeZone("America/Guayaquil"));
    }
}