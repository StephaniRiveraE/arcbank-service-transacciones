package com.arcbank.cbs.transaccion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
public class CognitoTokenService {

    @Value("${app.apim.cognito.domain}")
    private String cognitoDomain;

    @Value("${app.apim.cognito.client-id}")
    private String clientId;

    @Value("${app.apim.cognito.client-secret}")
    private String clientSecret;

    @Value("${app.apim.cognito.scope}")
    private String scope;

    private String cachedToken;
    private LocalDateTime tokenExpiration;
    private final RestTemplate restTemplate = new RestTemplate();

    public synchronized String getAccessToken() {
        if (cachedToken != null && tokenExpiration != null && LocalDateTime.now().isBefore(tokenExpiration)) {
            return cachedToken;
        }

        log.info("Obteniendo nuevo token de Cognito...");
        try {
            String tokenUrl = cognitoDomain + "/oauth2/token";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
            map.add("grant_type", "client_credentials");
            map.add("client_id", clientId);
            map.add("client_secret", clientSecret);
            map.add("scope", scope);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                cachedToken = (String) body.get("access_token");
                Integer expiresIn = (Integer) body.get("expires_in");

                // Cachear token con un margen de seguridad de 60 segundos
                tokenExpiration = LocalDateTime.now().plusSeconds(expiresIn - 60);

                log.info("Token de Cognito obtenido exitosamente. Expira en {} segundos", expiresIn);
                return cachedToken;
            } else {
                throw new RuntimeException("Error obteniendo token de Cognito: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Fallo al obtener token de Cognito: {}", e.getMessage());
            throw new RuntimeException("Fallo de autenticación con Cognito", e);
        }
    }
}
