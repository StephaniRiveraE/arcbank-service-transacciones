package com.arcbank.cbs.transaccion.controller;

import com.arcbank.cbs.transaccion.dto.TransaccionRequestDTO;
import com.arcbank.cbs.transaccion.dto.TransaccionResponseDTO;
import com.arcbank.cbs.transaccion.service.TransaccionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transacciones")
@RequiredArgsConstructor
@Tag(name = "Transacciones", description = "Gestión de movimientos y cumplimiento de lógica financiera")
public class Controller {

    private final TransaccionService transaccionService;

    @PostMapping
    @Operation(summary = "Ejecutar transacción financiera")
    public ResponseEntity<TransaccionResponseDTO> crear(@Valid @RequestBody TransaccionRequestDTO request) {
        return new ResponseEntity<>(transaccionService.crearTransaccion(request), HttpStatus.CREATED);
    }

    @GetMapping("/cuenta/{idCuenta}")
    @Operation(summary = "Historial por cuenta (Origen o Destino)")
    public ResponseEntity<List<TransaccionResponseDTO>> listarPorCuenta(@PathVariable Integer idCuenta) {
        return ResponseEntity.ok(transaccionService.obtenerPorCuenta(idCuenta));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener transacción por ID numérico")
    public ResponseEntity<?> obtenerPorId(@PathVariable Integer id) {
        try {
            return ResponseEntity.ok(transaccionService.obtenerPorId(id));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(java.util.Map.of("error", "Transacción no encontrada con ID: " + id));
        }
    }

    @GetMapping("/{id}/detalle")
    @Operation(summary = "Obtener transacción por ID con detalle completo para devolución")
    public ResponseEntity<?> obtenerDetallePorId(@PathVariable Integer id) {
        try {
            return ResponseEntity.ok(transaccionService.obtenerDetallePorId(id));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(java.util.Map.of("error", "Transacción no encontrada: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/devolucion")
    @Operation(summary = "Solicitar devolución de transacción (Reverso) por ID numérico")
    public ResponseEntity<TransaccionResponseDTO> solicitarDevolucion(
            @PathVariable Integer id,
            @RequestBody com.arcbank.cbs.transaccion.dto.DevolucionRequestDTO request) {
        return ResponseEntity.ok(transaccionService.solicitarDevolucion(id, request.getMotivo()));
    }

    @PostMapping("/referencia/{referencia}/devolucion")
    @Operation(summary = "Solicitar devolución de transacción (Reverso) por UUID/Referencia")
    public ResponseEntity<TransaccionResponseDTO> solicitarDevolucionPorReferencia(
            @PathVariable String referencia,
            @RequestBody com.arcbank.cbs.transaccion.dto.DevolucionRequestDTO request) {
        return ResponseEntity.ok(transaccionService.solicitarDevolucionPorReferencia(referencia, request.getMotivo()));
    }

    @GetMapping("/motivos-devolucion")
    @Operation(summary = "Obtener catálogo de motivos de devolución desde el Switch")
    public ResponseEntity<List<java.util.Map<String, String>>> obtenerMotivosDevolucion() {
        return ResponseEntity.ok(transaccionService.obtenerMotivosDevolucion());
    }

    @PostMapping("/validar-cuenta")
    @Operation(summary = "Validar cuenta externa en otro banco (Account Lookup)")
    public ResponseEntity<?> validarCuentaExterna(@RequestBody java.util.Map<String, String> request) {
        String targetBankId = request.get("targetBankId");
        String targetAccountNumber = request.get("targetAccountNumber");

        if (targetBankId == null || targetAccountNumber == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Faltan datos: targetBankId o targetAccountNumber"));
        }

        // CORRECCIÓN: El Switch espera el BIC (BANTEC), no códigos numéricos.
        // Mapeamos cualquier variante conocida (100050, 200100) al BIC oficial.
        if (targetBankId != null && (targetBankId.equalsIgnoreCase("BANTEC") ||
                targetBankId.equals("100050") || targetBankId.equals("200100"))) {
            targetBankId = "BANTEC";
        }

        return ResponseEntity.ok(transaccionService.validarCuentaExterna(targetBankId, targetAccountNumber));
    }

    @GetMapping("/buscar/{referencia}")
    @Operation(summary = "Buscar transacción por referencia/instructionId")
    public ResponseEntity<?> buscarPorReferencia(@PathVariable String referencia) {
        try {
            return ResponseEntity.ok(transaccionService.buscarPorReferencia(referencia));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(java.util.Map.of("error", "Transacción no encontrada: " + e.getMessage()));
        }
    }

    @GetMapping("/buscar/{referencia}/detalle-switch")
    @Operation(summary = "Buscar transacción con detalle completo del Switch")
    public ResponseEntity<?> buscarConDetalleSwitch(@PathVariable String referencia) {
        try {
            return ResponseEntity.ok(transaccionService.buscarConDetalleSwitch(referencia));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(java.util.Map.of("error", "No se pudo obtener detalle: " + e.getMessage()));
        }
    }

    @GetMapping("/buscar-codigo/{codigoReferencia}")
    @Operation(summary = "Buscar transacción por Código de Referencia (6 dígitos) con detalle completo")
    public ResponseEntity<?> buscarPorCodigoReferencia(@PathVariable String codigoReferencia) {
        try {
            return ResponseEntity.ok(transaccionService.buscarPorCodigoReferencia(codigoReferencia));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(java.util.Map.of("error", "No se pudo obtener detalle: " + e.getMessage()));
        }
    }

    @GetMapping("/saldo-tecnico")
    @Operation(summary = "Obtener el saldo técnico del banco en el Switch (Funding)")
    public ResponseEntity<?> obtenerSaldoTecnico() {
        try {
            return ResponseEntity.ok(transaccionService.obtenerSaldoTecnico());
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(java.util.Map.of("error", "No se pudo obtener saldo técnico: " + e.getMessage()));
        }
    }

    @GetMapping("/red/bancos")
    @Operation(summary = "Obtener listado de bancos habilitados en la red")
    public ResponseEntity<?> obtenerBancos() {
        try {
            return ResponseEntity.ok(transaccionService.obtenerBancos());
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(java.util.Map.of("error", "No se pudo obtener catálogo de bancos: " + e.getMessage()));
        }
    }
}