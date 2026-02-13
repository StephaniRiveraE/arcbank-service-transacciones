package com.arcbank.cbs.transaccion.service;

import java.math.BigDecimal;
import java.util.List;

import com.arcbank.cbs.transaccion.dto.TransaccionRequestDTO;
import com.arcbank.cbs.transaccion.dto.TransaccionResponseDTO;

public interface TransaccionService {

        TransaccionResponseDTO crearTransaccion(TransaccionRequestDTO request);

        List<TransaccionResponseDTO> obtenerPorCuenta(Integer idCuenta);

        TransaccionResponseDTO obtenerPorId(Integer id);

        void procesarTransferenciaEntrante(String instructionId, String cuentaDestino,
                        BigDecimal monto, String bancoOrigen);

        TransaccionResponseDTO solicitarDevolucion(Integer idTransaccion, String motivo);

        TransaccionResponseDTO solicitarDevolucionPorReferencia(String referencia, String motivo);

        void procesarDevolucionEntrante(com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest request);

        List<java.util.Map<String, String>> obtenerMotivosDevolucion();

        String consultarEstadoPorInstructionId(String instructionId);

        java.util.Map<String, Object> validarCuentaExterna(String targetBankId, String targetAccountNumber);

        java.util.Map<String, Object> validarCuentaLocal(String numeroCuenta);

        TransaccionResponseDTO buscarPorReferencia(String referencia);

        java.util.Map<String, Object> buscarConDetalleSwitch(String referencia);

        java.util.Map<String, Object> buscarPorCodigoReferencia(String codigoReferencia);

        java.util.Map<String, Object> obtenerDetallePorId(Integer id);

        java.util.Map<String, Object> obtenerSaldoTecnico();
}