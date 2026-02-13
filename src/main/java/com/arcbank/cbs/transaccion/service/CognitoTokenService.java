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

        log.info("🔐 Solicitando nuevo token de acceso a Cognito para Client ID: {}", clientId);
        try {
            String tokenUrl = cognitoDomain + "/oauth2/token";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // OAuth2 Standard: Basic Auth for client_id:client_secret
            String auth = clientId + ":" + clientSecret;
            byte[] encodedAuth = java.util.Base64.getEncoder().encode(auth.getBytes());
            String authHeader = "Basic " + new String(encodedAuth);
            headers.set("Authorization", authHeader);

            MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
            map.add("grant_type", "client_credentials");
            if (scope != null && !scope.isBlank()) {
                map.add("scope", scope);
            }

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

            log.debug("Enviando POST a {} con scope {}", tokenUrl, scope);
            ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                cachedToken = (String) body.get("access_token");

                // Manejar tanto Integer como Long para expires_in
                Number expiresIn = (Number) body.get("expires_in");
                long seconds = expiresIn != null ? expiresIn.longValue() : 3600;

                // Cachear token con un margen de seguridad de 5 minutos (300s) para evitar
                // fallos cercanos al vencimiento
                tokenExpiration = LocalDateTime.now().plusSeconds(seconds - 300);

                log.info("✅ Token de Cognito obtenido exitosamente. Válido por {} seg. Caché hasta: {}", seconds,
                        tokenExpiration);
                return cachedToken;
            } else {
                log.error("❌ Error en respuesta de Cognito: Status={}, Body={}", response.getStatusCode(),
                        response.getBody());
                throw new RuntimeException("Error en respuesta de Cognito: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("🚫 Error crítico obteniendo token de Cognito: {}", e.getMessage());
            // Limpiar caché en caso de error para reintentar limpiamente en la próxima
            // llamada
            clearCache();
            throw new RuntimeException("Fallo de autenticación con la red interbancaria (Cognito)", e);
        }
    }

    public synchronized void clearCache() {
        log.warn("🧹 Limpiando caché de token de Cognito por solicitud o error.");
        this.cachedToken = null;
        this.tokenExpiration = null;
    }
}
