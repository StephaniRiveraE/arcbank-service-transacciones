package com.arcbank.cbs.transaccion.client;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.arcbank.cbs.transaccion.dto.SwitchTransferRequest;

@FeignClient(name = "apim-switch-gateway", url = "${app.apim.endpoint}", configuration = com.arcbank.cbs.transaccion.config.ApimConfig.class)
public interface SwitchClient {

        // RF-01: Inicio de transferencia
        @PostMapping("/api/v2/switch/transfers")
        String enviarTransferencia(@RequestBody SwitchTransferRequest request);

        // RF-04: Consulta de estado
        @GetMapping("/api/v2/switch/transfers/{instructionId}")
        Map<String, Object> consultarEstado(@PathVariable("instructionId") String instructionId);

        // RF-07: Devoluciones / Reversos
        @PostMapping("/api/v2/switch/returns")
        String enviarDevolucion(@RequestBody com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest request);

        // Account Lookup (Sincronizado con ms-directorio via APIM)
        @GetMapping("/api/v2/switch/account-lookup")
        Map<String, Object> validarCuentaExterna(
                        @org.springframework.cloud.openfeign.SpringQueryMap Map<String, Object> request);

        // Health Check (Sincronizado con APIM)
        @GetMapping("/api/v2/switch/health")
        Map<String, String> healthCheck();

        // Listar Bancos (Ruta real en ms-directorio via APIM si está expuesta, o
        // fallback a /api/v1/instituciones)
        @GetMapping("/api/v1/instituciones")
        List<Map<String, Object>> obtenerBancos();

        // Funding / Disponibilidad (Ruta real en ms-contabilidad)
        @PostMapping("/api/v2/switch/funding")
        Map<String, Object> recargarFondeo(@RequestBody Map<String, Object> request);

        @GetMapping("/api/v1/funding/available/{bic}/{monto}")
        Map<String, Object> verificarDisponibilidad(@PathVariable("bic") String bic,
                        @PathVariable("monto") Double monto);

        // Compensación
        @PostMapping("/api/v2/compensation/upload")
        Map<String, Object> subirCompensacion(@RequestBody Map<String, Object> request);
}
