package com.arcbank.cbs.transaccion.config;

import com.arcbank.cbs.transaccion.service.CognitoTokenService;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Retryer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ApimConfig {

    private final CognitoTokenService tokenService;

    @Bean
    public RequestInterceptor apimRequestInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                try {
                    String token = tokenService.getAccessToken();
                    template.header("Authorization", "Bearer " + token);

                    // Agregar Trace ID único para cada petición
                    String traceId = UUID.randomUUID().toString();
                    template.header("X-Trace-ID", traceId);

                    log.debug("Injecting Auth Token and Trace-ID: {}", traceId);
                } catch (Exception e) {
                    log.error("Failed to inject APIM headers: {}", e.getMessage());
                    // No lanzamos excepción aquí para permitir que Feign maneje el error,
                    // aunque probablemente fallará con 401/403
                }
            }
        };
    }

    @Bean
    public Retryer retryer() {
        // Retry cada 100ms, incrementando hasta 1s, max 3 intentos
        return new Retryer.Default(100L, TimeUnit.SECONDS.toMillis(1L), 3);
    }
}
