package com.arcbank.cbs.transaccion.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.arcbank.cbs.transaccion.client.SwitchClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/bancos")
@RequiredArgsConstructor
@Tag(name = "Bancos", description = "Consulta de bancos conectados al switch interbancario")
public class BancosController {

    private final SwitchClient switchClient;

    @GetMapping
    @Operation(summary = "Listar bancos disponibles para transferencias interbancarias")
    public ResponseEntity<?> listarBancos() {
        try {
            log.info("Consultando bancos del switch DIGICONECU usando SwitchClient");

            List<Map<String, Object>> bancos = switchClient.obtenerBancos();

            if (bancos == null) {
                bancos = List.of();
            }

            List<Map<String, Object>> bancosExternos = bancos;

            log.info("Bancos disponibles para transferencia: {}", bancosExternos.size());

            return ResponseEntity.ok(Map.of(
                    "bancos", bancosExternos,
                    "total", bancosExternos.size()));

        } catch (Exception e) {
            log.error("Error consultando bancos del switch: {}", e.getMessage());

            return ResponseEntity.ok(Map.of(
                    "bancos", List.of(),
                    "total", 0,
                    "error", "No se pudo conectar al switch interbancario: " + e.getMessage()));
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Verificar conexión con el switch interbancario")
    public ResponseEntity<?> healthCheck() {
        try {
            Map<String, String> health = switchClient.healthCheck();
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "switch", health));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "status", "DOWN",
                    "error", e.getMessage()));
        }
    }
}
