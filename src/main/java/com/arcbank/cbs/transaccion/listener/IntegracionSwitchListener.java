package com.arcbank.cbs.transaccion.listener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity; // Import necesario
import org.springframework.http.HttpHeaders; // Import necesario
import org.springframework.http.MediaType; // Import necesario
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.arcbank.cbs.transaccion.dto.rabbitmq.MensajeISO;
import com.arcbank.cbs.transaccion.service.TransaccionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class IntegracionSwitchListener {

    private final TransaccionService transaccionService;
    private final RestTemplate restTemplate;

    @Value("${SWITCH_API_URL:http://34.16.106.7:8000/api/v2/switch/transfers/callback}")
    private String switchCallbackUrl;

    @Value("${app.switch.apikey:ARCBANK_SECRET_KEY_2025_XYZ}")
    private String switchApiKey;

    private static final String MI_BANCO_ID = "ARCBANK";

    @RabbitListener(queues = "${bank.queue.name}")
    public void procesarTransferencia(MensajeISO mensaje) {
        String txId = "UNKNOWN";
        try {
            if (mensaje.getBody() == null || mensaje.getBody().getInstructionId() == null) {
                log.error("❌ Mensaje inválido recibido: {}", mensaje);
                return;
            }

            txId = mensaje.getBody().getInstructionId();
            log.info("💰 Dinero recibido del Switch! ID: {}", txId);

            String cuentaDestino = mensaje.getBody().getCreditor().getAccountId();
            BigDecimal monto = mensaje.getBody().getAmount().getValue();
            String bancoOrigen = (mensaje.getHeader() != null) ? mensaje.getHeader().getOriginatingBankId() : "UNK";

            transaccionService.procesarTransferenciaEntrante(txId, cuentaDestino, monto, bancoOrigen);

            enviarCallback(mensaje, "COMPLETED", null);
            log.info("✅ Transacción procesada y confirmada al Switch.");

        } catch (Exception e) {
            log.error("❌ Fallo al acreditar: {}", e.getMessage());

            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("cuenta") || msg.contains("no existe") || msg.contains("bloqueada")) {
                String codigoError = "AC03";
                if (msg.contains("bloqueada"))
                    codigoError = "AG01";

                enviarCallback(mensaje, "REJECTED", codigoError);

                throw new AmqpRejectAndDontRequeueException(codigoError + " - " + e.getMessage());
            }

            throw new RuntimeException("Error técnico procesando transferencia", e);
        }
    }

    private void enviarCallback(MensajeISO msgOriginal, String estado, String codigoError) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("originalInstructionId", msgOriginal.getBody().getInstructionId());
            body.put("status", estado);
            body.put("processedDateTime", LocalDateTime.now().toString());
            body.put("reasonCode", codigoError != null ? codigoError : "");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("header", Map.of(
                    "messageId", UUID.randomUUID().toString(),
                    "respondingBankId", MI_BANCO_ID));
            requestBody.put("body", body);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("apikey", switchApiKey);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            restTemplate.postForEntity(switchCallbackUrl, requestEntity, String.class);
            log.info("📤 Callback enviado al Switch - Tx: {} Status: {}", msgOriginal.getBody().getInstructionId(),
                    estado);

        } catch (Exception e) {
            log.error("⚠️ Error enviando callback al Switch: {}", e.getMessage());
        }
    }
}
