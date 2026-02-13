package com.arcbank.cbs.transaccion.client;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.arcbank.cbs.transaccion.dto.SwitchTransferRequest;
import com.arcbank.cbs.transaccion.dto.SwitchTransferResponse;

@FeignClient(name = "apim-switch-gateway", url = "${app.apim.endpoint}", configuration = com.arcbank.cbs.transaccion.config.ApimConfig.class)
public interface SwitchClient {

        @PostMapping("/api/v2/switch/transfers")
        String enviarTransferencia(@RequestBody SwitchTransferRequest request);

        @GetMapping("/api/v1/red/bancos")
        List<Map<String, Object>> obtenerBancos();

        @GetMapping("/api/v2/transfers/health")
        Map<String, String> healthCheck();

        @PostMapping("/api/v2/switch/transfers/return")
        String enviarDevolucion(@RequestBody com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest request);

        @GetMapping("/api/v1/reference/iso20022/errors")
        List<Map<String, String>> obtenerMotivosDevolucion();

        @GetMapping("/api/v2/switch/transfers/{instructionId}")
        Map<String, Object> consultarEstado(@PathVariable("instructionId") String instructionId);

        @PostMapping("/api/v2/switch/accounts/lookup")
        Map<String, Object> validarCuentaExterna(@RequestBody Map<String, Object> request);

        @GetMapping("/funding/{bankId}")
        Map<String, Object> obtenerSaldoTecnico(@PathVariable("bankId") String bankId);
}
