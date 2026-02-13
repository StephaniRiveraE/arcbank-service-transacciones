package com.arcbank.cbs.transaccion.service;

import com.arcbank.cbs.transaccion.client.SwitchClient;
import com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest;
import com.arcbank.cbs.transaccion.dto.SwitchTransferRequest;
import com.arcbank.cbs.transaccion.dto.TxRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SwitchClientService {

        private final SwitchClient switchClient;

        @Value("${app.banco.codigo:ARCBANK}")
        private String bancoCodigo;

        public Map<String, Object> enviarTransferencia(TxRequest request) {
                log.info("Iniciando envío de transferencia interbancaria via Feign: {} -> {}",
                                request.getDebtorAccount(), request.getCreditorAccount());

                SwitchTransferRequest isoRequest = SwitchTransferRequest.builder()
                                .header(SwitchTransferRequest.Header.builder()
                                                .messageId("MSG-" + UUID.randomUUID().toString().substring(0, 8))
                                                .creationDateTime(java.time.Instant.now()
                                                                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                                                .toString())
                                                .originatingBankId(bancoCodigo)
                                                .build())
                                .body(SwitchTransferRequest.Body.builder()
                                                .instructionId(request.getReferenceId() != null
                                                                ? request.getReferenceId()
                                                                : UUID.randomUUID().toString())
                                                .endToEndId("E2E-" + UUID.randomUUID().toString().substring(0, 8))
                                                .amount(SwitchTransferRequest.Amount.builder()
                                                                .currency("USD")
                                                                .value(request.getAmount())
                                                                .build())
                                                .debtor(SwitchTransferRequest.Party.builder()
                                                                .name(request.getDebtorName())
                                                                .accountId(request.getDebtorAccount())
                                                                .accountType("AHORROS")
                                                                .bankId(bancoCodigo)
                                                                .build())
                                                .creditor(SwitchTransferRequest.Party.builder()
                                                                .name(request.getCreditorName())
                                                                .accountId(request.getCreditorAccount())
                                                                .accountType("AHORROS")
                                                                .bankId(request.getTargetBankId() != null
                                                                                ? request.getTargetBankId()
                                                                                : "BANTEC")
                                                                .build())
                                                .remittanceInformation(request.getDescription())
                                                .build())
                                .build();

                try {
                        log.info("JSON enviado al Switch: {}", new com.fasterxml.jackson.databind.ObjectMapper()
                                        .writeValueAsString(isoRequest));
                        String responseStr = switchClient.enviarTransferencia(isoRequest);

                        if (responseStr == null || responseStr.isBlank()) {
                                responseStr = "{\"status\": \"SUCCESS\", \"message\": \"Transferencia enviada correctamente\", \"codigoReferencia\": \"000000\"}";
                        }

                        log.info("Respuesta del Switch recibida: {}", responseStr);

                        try {
                                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(responseStr,
                                                Map.class);
                        } catch (Exception e) {
                                log.warn("No se pudo parsear respuesta JSON del switch, retornando map vacío:String. {}",
                                                e.getMessage());
                                return java.util.Collections.emptyMap();
                        }

                } catch (feign.FeignException e) {
                        log.error("Switch retornó error HTTP {}: {}", e.status(), e.contentUTF8());
                        String errorMsg = e.contentUTF8();

                        // Intentar mapear a un código ISO conocido para UX
                        String isoCode = "MS03"; // Default: Error Técnico

                        if (errorMsg != null) {
                                if (errorMsg.contains("AC01"))
                                        isoCode = "AC01";
                                else if (errorMsg.contains("AC04"))
                                        isoCode = "AC04";
                                else if (errorMsg.contains("AC06"))
                                        isoCode = "AC06";
                                else if (errorMsg.contains("AG01"))
                                        isoCode = "AG01";
                                else if (errorMsg.contains("AM04"))
                                        isoCode = "AM04";
                                else if (errorMsg.contains("CH03"))
                                        isoCode = "CH03";
                                else if (errorMsg.contains("AM05") || errorMsg.contains("DUPL"))
                                        isoCode = "MD01";
                                else if (errorMsg.contains("RC01"))
                                        isoCode = "RC01";
                        }

                        String finalMsg = isoCode.equals("MS03") ? "Error técnico en Switch/Banco Destino" : errorMsg;
                        throw new RuntimeException(isoCode + " - " + finalMsg);

                } catch (Exception e) {
                        log.error("Error técnico comunicación Switch: {}", e.getMessage());
                        throw new RuntimeException("Error de comunicación: " + e.getMessage());
                }
        }

        public String enviarReverso(String originalInstructionId, String returnReason, BigDecimal amount,
                        String debtorName, String debtorAccount,
                        String creditorName, String creditorAccount, String targetBankId) {
                log.info("Iniciando solicitud de reverso para Tx: {}", originalInstructionId);

                SwitchDevolucionRequest isoRequest = SwitchDevolucionRequest.builder()
                                .header(SwitchDevolucionRequest.Header.builder()
                                                .messageId(UUID.randomUUID().toString())
                                                .creationDateTime(java.time.Instant.now()
                                                                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                                                .toString())
                                                .originatingBankId(bancoCodigo)
                                                .build())
                                .body(SwitchDevolucionRequest.Body.builder()
                                                .returnInstructionId(UUID.randomUUID().toString())
                                                .originalInstructionId(originalInstructionId != null
                                                                ? originalInstructionId.trim()
                                                                : null)
                                                .returnReason(mapearErrorIso(returnReason))
                                                .returnAmount(SwitchDevolucionRequest.ReturnAmount.builder()
                                                                .currency("USD")
                                                                .value(amount)
                                                                .build())
                                                .build())
                                .build();

                try {
                        String response = switchClient.enviarDevolucion(isoRequest);
                        log.info("Respuesta de Devolución del Switch (200 OK): {}", response);
                        return response;
                } catch (Exception e) {
                        log.error("Error al solicitar reverso (Switch rechazó): {}", e.getMessage());
                        throw new RuntimeException("Switch rechazó el reverso: " + e.getMessage());
                }
        }

        public java.util.List<java.util.Map<String, Object>> obtenerBancos() {
                try {
                        return switchClient.obtenerBancos();
                } catch (Exception e) {
                        log.error("Error al obtener bancos del Switch: {}", e.getMessage());
                        return java.util.Collections.emptyList();
                }
        }

        public java.util.List<java.util.Map<String, String>> obtenerMotivosDevolucion() {
                // Endpoint no existe en APIM según análisis. Retornamos lista fija de ISO
                // 20022.
                return java.util.List.of(
                                java.util.Map.of("code", "AC03", "description", "Cuenta Inválida"),
                                java.util.Map.of("code", "AM04", "description", "Fondos Insuficientes"),
                                java.util.Map.of("code", "AC04", "description", "Cuenta Cerrada"),
                                java.util.Map.of("code", "AC06", "description", "Cuenta Bloqueada"),
                                java.util.Map.of("code", "AG01", "description", "Transacción Prohibida"),
                                java.util.Map.of("code", "MD01", "description", "Transacción Duplicada"),
                                java.util.Map.of("code", "MS03", "description", "Error Técnico"));
        }

        private static String mapearErrorIso(String internalCode) {
                if (internalCode == null)
                        return "MS03";
                String code = internalCode.toUpperCase().trim();
                return switch (code) {
                        case "TECH", "ERROR_TECNICO", "MS03" -> "MS03";
                        case "CUENTA_INVALIDA", "AC03" -> "AC03";
                        case "SALDO_INSUFICIENTE", "AM04" -> "AM04";
                        case "CUENTA_CERRADA", "AC04" -> "AC04";
                        case "CUENTA_BLOQUEADA", "AC06" -> "AC06";
                        case "OPERACION_PROHIBIDA", "AG01" -> "AG01";
                        case "DUPLICADO", "DUPL", "AM05", "MD01" -> "AM05";
                        case "FRAUDE", "FRAD", "FR01" -> "FR01";
                        default -> code.matches("^[A-Z0-9]{4}$") ? code : "MS03";
                };
        }

        public java.util.Map<String, Object> consultarEstado(String instructionId) {
                try {
                        return switchClient.consultarEstado(instructionId);
                } catch (Exception e) {
                        log.warn("Error consultando estado de Tx {}: {}", instructionId, e.getMessage());
                        return null;
                }
        }

        public java.util.Map<String, Object> validarCuenta(String targetBankId, String targetAccountNumber) {
                // Según la Guía v2.0, el request debe estar envuelto en header y body
                java.util.Map<String, Object> header = java.util.Map.of(
                                "originatingBankId", bancoCodigo,
                                "messageId", "VAL-" + UUID.randomUUID().toString().substring(0, 8));

                java.util.Map<String, Object> body = java.util.Map.of(
                                "targetBankId", targetBankId,
                                "targetAccountNumber", targetAccountNumber);

                java.util.Map<String, Object> request = java.util.Map.of("header", header, "body", body);

                log.info("🔍 Enviando Account Lookup (acmt.023) via APIM: {}", request);
                try {
                        Map<String, Object> response = switchClient.validarCuentaExterna(request);
                        log.info("✅ Respuesta de validación recibida: {}", response);

                        // El Switch v2.0 responde con { status: "SUCCESS", data: { ... } }
                        if (response.containsKey("data")) {
                                return (Map<String, Object>) response.get("data");
                        }
                        if (response.containsKey("body")) {
                                return (Map<String, Object>) response.get("body");
                        }

                        return response;
                } catch (feign.FeignException e) {
                        log.error("❌ Error API APIM ms-directorio (status {}): {}", e.status(), e.contentUTF8());
                        throw e; // Re-lanzar para que GlobalExceptionHandler maneje el detalle
                } catch (Exception e) {
                        log.error("❌ Error de comunicación con ms-directorio: {}", e.getMessage());
                        throw new RuntimeException("Error de comunicación con la red interbancaria: " + e.getMessage());
                }
        }

        public java.util.Map<String, Object> obtenerSaldoTecnico() {
                try {
                        // BIC = bancoCodigo, Monto default = 0.0 para solo consulta
                        // Intentamos primero el GET de disponibilidad (v1)
                        log.info("💰 Consultando saldo técnico (Disponibilidad) en Switch para: {}", bancoCodigo);
                        return switchClient.verificarDisponibilidad(bancoCodigo, 0.0);
                } catch (Exception e) {
                        log.warn("⚠️ Falló consulta v1, intentando POST v2/switch/funding: {}", e.getMessage());
                        try {
                                return switchClient.recargarFondeo(Map.of("bic", bancoCodigo, "queryOnly", true));
                        } catch (Exception e2) {
                                log.error("❌ No se pudo obtener saldo técnico por ninguna ruta: {}", e2.getMessage());
                                return java.util.Map.of("error", e2.getMessage(), "bankId", bancoCodigo, "status",
                                                "ERROR");
                        }
                }
        }
}
