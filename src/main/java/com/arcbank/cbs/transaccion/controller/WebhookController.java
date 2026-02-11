package com.arcbank.cbs.transaccion.controller;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.arcbank.cbs.transaccion.dto.SwitchTransferRequest;
import com.arcbank.cbs.transaccion.service.TransaccionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WebhookController {

        private final TransaccionService transaccionService;
        private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

        @PostMapping("/api/core/transferencias/recepcion")
        @SuppressWarnings("unchecked")
        public ResponseEntity<?> recibirWebhookUnificado(@RequestBody Map<String, Object> payload) {
                try {
                        Map<String, Object> header = (Map<String, Object>) payload.get("header");
                        Map<String, Object> body = (Map<String, Object>) payload.get("body");

                        // 1. Detección de Account Verification (acmt.023)
                        if (header != null && "acmt.023.001.02".equals(header.get("messageNamespace"))) {
                                log.info("🔍 Webhook detectado como CONSULTA DE CUENTA (acmt.023)");
                                String accountId = null;

                                // Segun instruccion Switch: Usamos el campo 'creditor' por compatibilidad
                                if (body != null && body.get("creditor") != null) {
                                        Map<String, Object> creditor = (Map<String, Object>) body.get("creditor");
                                        accountId = (String) creditor.get("accountId");
                                }

                                if (accountId == null) {
                                        log.warn("Solicitud acmt.023 recibida sin 'creditor.accountId'.");
                                        return ResponseEntity.ok(Map.of(
                                                        "status", "FAILED",
                                                        "data", Map.of("mensaje",
                                                                        "Formato inválido: Falta creditor.accountId")));
                                }

                                Map<String, Object> result = transaccionService.validarCuentaLocal(accountId);

                                if (Boolean.TRUE.equals(result.get("exists"))) {
                                        return ResponseEntity.ok(Map.of("status", "SUCCESS", "data", result));
                                } else {
                                        return ResponseEntity.ok(Map.of(
                                                        "status", "FAILED",
                                                        "data",
                                                        Map.of("exists", false, "mensaje", "Cuenta no encontrada")));
                                }
                        }

                        // 2. Detección de Devoluciones (pacs.004)
                        if (body != null && (body.containsKey("originalInstructionId")
                                        || body.containsKey("returnReason"))) {
                                log.info("🔄 Webhook detectado como DEVOLUCIÓN (pacs.004)");
                                com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest req = objectMapper.convertValue(
                                                payload, com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest.class);
                                return recibirDevolucion(req);
                        } else {
                                // 3. Detección de Transferencia (pacs.008) por descarte
                                log.info("📥 Webhook detectado como TRANSFERENCIA (pacs.008)");
                                SwitchTransferRequest req = objectMapper.convertValue(payload,
                                                SwitchTransferRequest.class);
                                log.info("Processing transfer ID: {}", req.getBody().getInstructionId());
                                return procesarTransferencia(req);
                        }
                } catch (Exception e) {
                        log.error("❌ Error en webhook unificado: {}", e.getMessage());
                        return ResponseEntity.status(422).body(Map.of("status", "NACK", "error",
                                        "Error procesando payload unificado: " + e.getMessage()));
                }
        }

        @PostMapping("/api/incoming/return")
        public ResponseEntity<?> recibirDevolucion(
                        @RequestBody com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest request) {
                log.info("🔄 Webhook Devolución V3.0 recibido (Confirmación Asíncrona): {}",
                                request.getBody().getOriginalInstructionId());
                try {
                        transaccionService.procesarDevolucionEntrante(request);
                        return ResponseEntity.ok(Map.of("status", "ACK", "message", "Devolución confirmada"));
                } catch (Exception e) {
                        log.error("❌ Error procesando confirmación de devolución: {}", e.getMessage());
                        return ResponseEntity.badRequest().body(Map.of("status", "NACK", "error", e.getMessage()));
                }
        }

        private ResponseEntity<?> procesarTransferencia(SwitchTransferRequest request) {
                try {
                        if (request.getHeader() == null || request.getBody() == null) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of("status", "NACK", "error", "Formato inválido"));
                        }

                        String instructionId = request.getBody().getInstructionId();
                        String cuentaDestino = request.getBody().getCreditor() != null
                                        ? request.getBody().getCreditor().getAccountId()
                                        : null;
                        String bancoOrigen = request.getHeader().getOriginatingBankId() != null
                                        ? request.getHeader().getOriginatingBankId()
                                        : "DESCONOCIDO";

                        BigDecimal monto = BigDecimal.ZERO;
                        if (request.getBody().getAmount() != null && request.getBody().getAmount().getValue() != null) {
                                monto = request.getBody().getAmount().getValue();
                        }

                        if (instructionId == null || cuentaDestino == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of("status", "NACK", "error", "Datos incompletos"));
                        }

                        transaccionService.procesarTransferenciaEntrante(instructionId, cuentaDestino, monto,
                                        bancoOrigen);

                        return ResponseEntity.ok(Map.of(
                                        "status", "ACK",
                                        "message", "Acreditación exitosa en Arcbank",
                                        "instructionId", instructionId));

                } catch (Exception e) {
                        log.error("❌ Error procesando abono: {}", e.getMessage());

                        if (e.getMessage() != null && e.getMessage().contains("Cuenta destino no encontrada")) {
                                return ResponseEntity.status(422).body(Map.of(
                                                "codigo", "AC01",
                                                "mensaje", "La cuenta destino no existe en nuestros registros"));
                        }

                        if (e.getMessage() != null && e.getMessage().contains("Cuenta cerrada")) {
                                return ResponseEntity.status(422).body(Map.of(
                                                "codigo", "AC04",
                                                "mensaje", "Cuenta cerrada o inactiva"));
                        }

                        return ResponseEntity.status(422).body(Map.of("status", "NACK", "error", e.getMessage()));
                }
        }

        @org.springframework.web.bind.annotation.GetMapping("/api/core/transferencias/recepcion/status/{instructionId}")
        public ResponseEntity<?> consultarEstado(
                        @org.springframework.web.bind.annotation.PathVariable String instructionId) {
                String estado = transaccionService.consultarEstadoPorInstructionId(instructionId);

                Map<String, String> response = new java.util.HashMap<>();
                response.put("estado", estado);

                if ("NOT_FOUND".equals(estado)) {
                        return ResponseEntity.status(404).body(response);
                }

                return ResponseEntity.ok(response);
        }
}