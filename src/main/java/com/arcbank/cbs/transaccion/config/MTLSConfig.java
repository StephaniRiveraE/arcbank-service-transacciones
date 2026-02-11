package com.arcbank.cbs.transaccion.config;

import feign.Client;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.security.KeyStore;

import feign.RequestInterceptor;

@Configuration
@Slf4j
public class MTLSConfig {

    @Value("${app.mtls.keystore.path:classpath:certs/arcbank-keystore.p12}")
    private Resource keystoreResource;

    @Value("${app.mtls.keystore.password:arcbank123}")
    private String keystorePassword;

    @Value("${app.mtls.truststore.path:classpath:certs/arcbank-truststore.p12}")
    private Resource truststoreResource;

    @Value("${app.mtls.truststore.password:arcbank123}")
    private String truststorePassword;

    @Value("${app.mtls.enabled:false}")
    private boolean mtlsEnabled;

    @Value("${app.switch.apikey:}")
    private String apiKey;

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            if (apiKey != null && !apiKey.isBlank()) {
                if (!requestTemplate.headers().containsKey("apikey")) {
                    log.debug("Adding 'apikey' header to request: {}", apiKey.substring(0, 5) + "...");
                    requestTemplate.header("apikey", apiKey);
                }
            } else {
                log.warn("⚠️ API Key is missing or empty in MTLSConfig!");
            }
        };
    }

    @Bean
    public Client feignClient() throws Exception {
        if (!mtlsEnabled) {
            return new Client.Default(null, null);
        }

        if (!keystoreResource.exists() || !truststoreResource.exists()) {
            log.error(
                    "⚠️ [CRITICAL] Certificados mTLS no encontrados. Desactivando mTLS para evitar crash de la aplicación.");
            log.error("Expectativa Keystore: {}", keystoreResource);
            log.error("Expectativa Truststore: {}", truststoreResource);

            return new Client.Default(null, null);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream keyStoreStream = keystoreResource.getInputStream()) {
            keyStore.load(keyStoreStream, keystorePassword.toCharArray());
        }

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream trustStoreStream = truststoreResource.getInputStream()) {
            trustStore.load(trustStoreStream, truststorePassword.toCharArray());
        }

        SSLContext sslContext = SSLContextBuilder.create()
                .loadKeyMaterial(keyStore, keystorePassword.toCharArray())
                .loadTrustMaterial(trustStore, null)
                .build();

        SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);

        HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        return new feign.hc5.ApacheHttp5Client(httpClient);
    }
}
