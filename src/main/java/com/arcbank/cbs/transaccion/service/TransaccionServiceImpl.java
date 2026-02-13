package com.arcbank.cbs.transaccion.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arcbank.cbs.transaccion.client.CuentaCliente;
import com.arcbank.cbs.transaccion.client.ClienteCliente;
import com.arcbank.cbs.transaccion.client.SwitchClient;
import com.arcbank.cbs.transaccion.dto.SaldoDTO;
import com.arcbank.cbs.transaccion.dto.TransaccionRequestDTO;
import com.arcbank.cbs.transaccion.dto.TransaccionResponseDTO;
import com.arcbank.cbs.transaccion.exception.BusinessException;
import com.arcbank.cbs.transaccion.model.Transaccion;
import com.arcbank.cbs.transaccion.repository.TransaccionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransaccionServiceImpl implements TransaccionService {

    private final TransaccionRepository transaccionRepository;
    private final CuentaCliente cuentaCliente;
    private final ClienteCliente clienteCliente;
    private final SwitchClient switchClient;
    private final SwitchClientService switchClientService;

    @Value("${app.banco.codigo:ARCBANK}")
    private String codigoBanco;

    @Override
    @Transactional
    public TransaccionResponseDTO crearTransaccion(TransaccionRequestDTO request) {
        log.info("Iniciando transacción Tipo: {} | Ref: {}", request.getTipoOperacion(), request.getReferencia());

        String tipoOp = request.getTipoOperacion().toUpperCase();

        String referenciaUtil = request.getReferencia();
        // Garantizar UUID válido (36 chars) para cumplir con estándar del Switch
        if (referenciaUtil == null || referenciaUtil.length() != 36) {
            referenciaUtil = UUID.randomUUID().toString();
        }

        Transaccion trx = Transaccion.builder()
                .referencia(referenciaUtil)
                .tipoOperacion(tipoOp)
                .monto(request.getMonto())
                .descripcion(request.getDescripcion())
                .canal(request.getCanal() != null ? request.getCanal() : "WEB")
                .idSucursal(request.getIdSucursal())
                .cuentaExterna(request.getCuentaExterna())
                .idBancoExterno(request.getIdBancoExterno())
                .idTransaccionReversa(request.getIdTransaccionReversa())
                .estado("PENDIENTE")
                .build();

        try {
            String nuevoEstado = "COMPLETADA"; // Default para operaciones locales

            BigDecimal saldoImpactado = switch (tipoOp) {
                case "DEPOSITO" -> {
                    if (request.getIdCuentaDestino() == null)
                        throw new BusinessException("El DEPOSITO requiere una cuenta destino obligatoria.");

                    trx.setIdCuentaDestino(request.getIdCuentaDestino());
                    trx.setIdCuentaOrigen(null);
                    yield procesarSaldo(trx.getIdCuentaDestino(), request.getMonto());
                }

                case "RETIRO" -> {
                    if (request.getIdCuentaOrigen() == null)
                        throw new BusinessException("El RETIRO requiere una cuenta origen obligatoria.");

                    trx.setIdCuentaOrigen(request.getIdCuentaOrigen());
                    trx.setIdCuentaDestino(null);
                    yield procesarSaldo(trx.getIdCuentaOrigen(), request.getMonto().negate());
                }

                case "TRANSFERENCIA_INTERNA" -> {
                    if (request.getIdCuentaOrigen() == null || request.getIdCuentaDestino() == null) {
                        throw new BusinessException(
                                "La TRANSFERENCIA INTERNA requiere cuenta origen y cuenta destino.");
                    }
                    if (request.getIdCuentaOrigen().equals(request.getIdCuentaDestino())) {
                        throw new BusinessException("No se puede transferir a la misma cuenta.");
                    }

                    trx.setIdCuentaOrigen(request.getIdCuentaOrigen());
                    trx.setIdCuentaDestino(request.getIdCuentaDestino());

                    BigDecimal saldoOrigen = procesarSaldo(trx.getIdCuentaOrigen(), request.getMonto().negate());
                    BigDecimal saldoDestino = procesarSaldo(trx.getIdCuentaDestino(), request.getMonto());
                    trx.setSaldoResultanteDestino(saldoDestino);
                    yield saldoOrigen;
                }

                case "TRANSFERENCIA_SALIDA", "TRANSFERENCIA_INTERBANCARIA" -> {
                    if (request.getIdCuentaOrigen() == null)
                        throw new BusinessException("Falta cuenta origen.");
                    if (request.getCuentaExterna() == null)
                        throw new BusinessException("Falta cuenta destino externa.");

                    trx.setIdCuentaOrigen(request.getIdCuentaOrigen());
                    trx.setIdCuentaDestino(null);
                    trx.setCuentaExterna(request.getCuentaExterna());
                    trx.setIdBancoExterno(request.getIdBancoExterno());

                    BigDecimal saldoOrigen = procesarSaldo(trx.getIdCuentaOrigen(), request.getMonto().negate());

                    Map<String, Object> cuentaOrigenDetalles = obtenerDetallesCuenta(request.getIdCuentaOrigen());
                    String numeroCuentaOrigen = cuentaOrigenDetalles != null
                            && cuentaOrigenDetalles.get("numeroCuenta") != null
                                    ? cuentaOrigenDetalles.get("numeroCuenta").toString()
                                    : String.valueOf(request.getIdCuentaOrigen());

                    String nombreOrigen = "Cliente Arcbank";
                    if (cuentaOrigenDetalles != null && cuentaOrigenDetalles.get("nombreTitular") != null) {
                        nombreOrigen = cuentaOrigenDetalles.get("nombreTitular").toString();
                    }

                    try {
                        log.info("Enviando transferencia al switch: {}", request.getCuentaExterna());

                        com.arcbank.cbs.transaccion.dto.TxRequest txRequest = com.arcbank.cbs.transaccion.dto.TxRequest
                                .builder()
                                .debtorAccount(numeroCuentaOrigen)
                                .debtorName(nombreOrigen)
                                .creditorAccount(request.getCuentaExterna())
                                .creditorName(request.getNombreDestinatario() != null ? request.getNombreDestinatario()
                                        : "Beneficiario")
                                .targetBankId(
                                        request.getIdBancoExterno() != null ? request.getIdBancoExterno() : "UNKNOWN")
                                .amount(request.getMonto())
                                .description(request.getDescripcion())
                                .referenceId(trx.getReferencia())
                                .build();

                        Map<String, Object> respSwitch = switchClientService.enviarTransferencia(txRequest);

                        if (respSwitch != null) {
                            String refCode = (String) respSwitch.get("codigoReferencia");
                            // Fallback check inside data
                            if (refCode == null && respSwitch.get("data") instanceof Map) {
                                refCode = (String) ((Map<?, ?>) respSwitch.get("data")).get("codigoReferencia");
                            }
                            if (refCode != null) {
                                trx.setCodigoReferencia(refCode);
                            }
                        }

                        // --- FIX: Verificar si el POST ya retornó estado final ---
                        boolean confirmado = false;
                        if (respSwitch != null) {
                            String estadoInicial = (String) respSwitch.get("estado");
                            if ("COMPLETED".equalsIgnoreCase(estadoInicial)) {
                                log.info("Switch retornó COMPLETED en respuesta inicial. Omitiendo polling.");
                                nuevoEstado = "COMPLETADA";
                                confirmado = true;
                            } else if ("FAILED".equalsIgnoreCase(estadoInicial)) {
                                String errorMsg = respSwitch.getOrDefault("error", "Rechazo del Switch").toString();
                                throw new RuntimeException(errorMsg);
                            }
                        }

                        // --- Polling Síncrono solo si el estado inicial no es final ---
                        if (!confirmado) {
                            nuevoEstado = "PENDIENTE";

                            // Máx 10 intentos de 1.5s = 15s timeout
                            for (int i = 0; i < 10; i++) {
                                try {
                                    Thread.sleep(1500);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }

                                Map<String, Object> estadoTx = switchClientService.consultarEstado(trx.getReferencia());
                                if (estadoTx != null) {
                                    // FIX: El Switch usa campo "estado", no "status"
                                    String status = (String) estadoTx.get("estado");
                                    if ("COMPLETED".equalsIgnoreCase(status)) {
                                        nuevoEstado = "COMPLETADA";
                                        confirmado = true;
                                        break;
                                    }
                                    if ("FAILED".equalsIgnoreCase(status)) {
                                        String errorMsg = (String) estadoTx.getOrDefault("error", "Rechazo del Switch");
                                        throw new RuntimeException(errorMsg);
                                    }
                                    // Si es PENDING o RECEIVED, seguimos esperando
                                }
                            }

                            if (!confirmado) {
                                log.warn("Transferencia {} en TIMEOUT tras polling.", trx.getReferencia());
                                // Se queda en PENDIENTE
                            }
                        }

                    } catch (Exception e) {
                        log.error("Error/Rechazo Switch: {}. Revertiendo.", e.getMessage());
                        procesarSaldo(trx.getIdCuentaOrigen(), request.getMonto()); // Rollback
                        throw new BusinessException("Transferencia fallida: " + e.getMessage());
                    }

                    yield saldoOrigen;
                }

                case "TRANSFERENCIA_ENTRADA" -> {
                    if (request.getIdCuentaDestino() == null)
                        throw new BusinessException("Falta cuenta destino.");
                    trx.setIdCuentaDestino(request.getIdCuentaDestino());
                    trx.setIdCuentaOrigen(null);
                    yield procesarSaldo(trx.getIdCuentaDestino(), request.getMonto());
                }

                default -> throw new BusinessException("Tipo no soportado: " + tipoOp);
            };

            trx.setSaldoResultante(saldoImpactado);
            trx.setEstado(nuevoEstado);

            Transaccion guardada = transaccionRepository.save(trx);
            log.info("Transacción guardada ID: {}", guardada.getIdTransaccion());

            return mapearADTO(guardada, null);

        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Error técnico procesando transacción: ", e);
            throw e;
        }
    }

    @Override
    public List<TransaccionResponseDTO> obtenerPorCuenta(Integer idCuenta) {
        return transaccionRepository.findPorCuenta(idCuenta).stream()
                .map(t -> mapearADTO(t, idCuenta))
                .collect(Collectors.toList());
    }

    @Override
    public TransaccionResponseDTO obtenerPorId(Integer id) {
        if (id == null) {
            throw new BusinessException("El ID de la transacción no puede ser nulo.");
        }
        Transaccion t = transaccionRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Transacción no encontrada con ID: " + id));
        return mapearADTO(t, null);
    }

    private BigDecimal procesarSaldo(Integer idCuenta, BigDecimal montoCambio) {
        BigDecimal saldoActual;

        try {
            saldoActual = cuentaCliente.obtenerSaldo(idCuenta);
            if (saldoActual == null) {
                throw new BusinessException("La cuenta ID " + idCuenta + " existe pero retornó saldo nulo.");
            }
        } catch (Exception e) {
            log.error("Error conectando con MS Cuentas: {}", e.getMessage());
            throw new BusinessException("No se pudo validar la cuenta ID: " + idCuenta + ". Verifique que exista.");
        }

        BigDecimal nuevoSaldo = saldoActual.add(montoCambio);

        if (nuevoSaldo.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(
                    "Fondos insuficientes en la cuenta ID: " + idCuenta + ". Saldo actual: " + saldoActual);
        }

        try {
            cuentaCliente.actualizarSaldo(idCuenta, new SaldoDTO(nuevoSaldo));
        } catch (Exception e) {
            throw new BusinessException("Error al actualizar el saldo de la cuenta ID: " + idCuenta);
        }

        return nuevoSaldo;
    }

    private TransaccionResponseDTO mapearADTO(Transaccion t, Integer idCuentaVisor) {
        BigDecimal saldoAMostrar = t.getSaldoResultante() != null ? t.getSaldoResultante() : BigDecimal.ZERO;

        log.info("Mapeando Tx: {}, Visor: {}, Dest: {}, SaldoDest: {}",
                t.getIdTransaccion(), idCuentaVisor, t.getIdCuentaDestino(), t.getSaldoResultanteDestino());

        if (idCuentaVisor != null &&
                t.getIdCuentaDestino() != null &&
                t.getIdCuentaDestino().equals(idCuentaVisor) &&
                t.getSaldoResultanteDestino() != null) {

            saldoAMostrar = t.getSaldoResultanteDestino();
        }

        return TransaccionResponseDTO.builder()
                .idTransaccion(t.getIdTransaccion())
                .referencia(t.getReferencia())
                .tipoOperacion(t.getTipoOperacion())
                .idCuentaOrigen(t.getIdCuentaOrigen())
                .idCuentaDestino(t.getIdCuentaDestino())
                .cuentaExterna(t.getCuentaExterna())
                .idBancoExterno(t.getIdBancoExterno())
                .monto(t.getMonto())
                .saldoResultante(saldoAMostrar)
                .fechaCreacion(t.getFechaCreacion())
                .descripcion(t.getDescripcion())
                .canal(t.getCanal())
                .estado(t.getEstado())
                .codigoReferencia(t.getCodigoReferencia())
                .build();
    }

    private Map<String, Object> obtenerDetallesCuenta(Integer idCuenta) {
        try {
            return cuentaCliente.obtenerCuenta(idCuenta);
        } catch (Exception e) {
            log.warn("No se pudo obtener detalles de cuenta para ID {}: {}", idCuenta, e.getMessage());
            return null;
        }
    }

    private String obtenerNumeroCuenta(Integer idCuenta) {
        try {
            Map<String, Object> cuenta = cuentaCliente.obtenerCuenta(idCuenta);
            if (cuenta != null && cuenta.get("numeroCuenta") != null) {
                return cuenta.get("numeroCuenta").toString();
            }
        } catch (Exception e) {
            log.warn("No se pudo obtener número de cuenta para ID {}: {}", idCuenta, e.getMessage());
        }
        return String.valueOf(idCuenta);
    }

    private Integer obtenerIdCuentaPorNumero(String numeroCuenta) {
        try {
            Map<String, Object> cuenta = cuentaCliente.buscarPorNumero(numeroCuenta);
            if (cuenta != null && cuenta.get("idCuenta") != null) {
                return Integer.valueOf(cuenta.get("idCuenta").toString());
            }
        } catch (Exception e) {
            log.error("Error buscando cuenta por número {}: {}", numeroCuenta, e.getMessage());
        }
        return null;
    }

    @Override
    @Transactional
    public void procesarTransferenciaEntrante(String instructionId, String cuentaDestino,
            BigDecimal monto, String bancoOrigen) {
        log.info("📥 Procesando transferencia entrante desde {} a cuenta {}, monto: {}",
                bancoOrigen, cuentaDestino, monto);

        Integer idCuentaDestino = obtenerIdCuentaPorNumero(cuentaDestino);
        if (idCuentaDestino == null) {
            throw new BusinessException("Cuenta destino no encontrada en Arcbank: " + cuentaDestino);
        }

        if (transaccionRepository.findByReferencia(instructionId).isPresent()) {
            log.warn("Transferencia entrante duplicada ignorada: {}", instructionId);
            return;
        }

        BigDecimal nuevoSaldo = procesarSaldo(idCuentaDestino, monto);

        Transaccion trx = Transaccion.builder()
                .referencia(instructionId)
                .tipoOperacion("TRANSFERENCIA_ENTRADA")
                .idCuentaDestino(idCuentaDestino)
                .idCuentaOrigen(null)
                .cuentaExterna(cuentaDestino)
                .monto(monto)

                .saldoResultante(nuevoSaldo)
                .idBancoExterno(bancoOrigen)
                .descripcion("Transferencia recibida desde " + bancoOrigen)
                .canal("SWITCH")
                .estado("COMPLETADA")
                .build();

        Transaccion guardada = transaccionRepository.save(trx);
        if (guardada == null) {
            log.error("Error crítico: La transacción no se pudo guardar.");
            return;
        }
        log.info("✅ Transferencia entrante completada. ID: {}, Nuevo saldo: {}",
                trx.getIdTransaccion(), nuevoSaldo);
    }

    @Override
    @Transactional
    public TransaccionResponseDTO solicitarDevolucion(Integer idTransaccion, String motivo) {
        log.info("Solicitando devolución para Tx ID: {} | Motivo: {}", idTransaccion, motivo);

        Transaccion trx = transaccionRepository.findById(idTransaccion)
                .orElseThrow(() -> new BusinessException("Transacción no encontrada con ID: " + idTransaccion));

        return ejecutarSolicitudDevolucion(trx, motivo);
    }

    @Override
    @Transactional
    public TransaccionResponseDTO solicitarDevolucionPorReferencia(String referencia, String motivo) {
        log.info("Solicitando devolución para Tx Ref: {} | Motivo: {}", referencia, motivo);

        Transaccion trx = transaccionRepository.findByReferencia(referencia)
                .orElseThrow(() -> new BusinessException("Transacción no encontrada con Referencia: " + referencia));

        return ejecutarSolicitudDevolucion(trx, motivo);
    }

    private TransaccionResponseDTO ejecutarSolicitudDevolucion(Transaccion trx, String motivo) {
        if (trx.getFechaCreacion().isBefore(java.time.LocalDateTime.now().minusHours(24))) {
            throw new BusinessException("El tiempo límite de 24h para devoluciones ha expirado.");
        }

        if ("REVERSADA".equals(trx.getEstado()) || "DEVUELTA".equals(trx.getEstado())) {
            throw new BusinessException("Esta transacción ya fue reversada o devuelta.");
        }

        if ("TRANSFERENCIA_SALIDA".equals(trx.getTipoOperacion())
                || "TRANSFERENCIA_INTERBANCARIA".equals(trx.getTipoOperacion())) {

            return procesarReversoSalida(trx, motivo);

        } else if ("TRANSFERENCIA_ENTRADA".equals(trx.getTipoOperacion())) {

            return procesarDevolucionIniciada(trx, motivo);

        } else {
            throw new BusinessException(
                    "Solo se pueden devolver transferencias interbancarias (Entrada o Salida). Tipo actual: "
                            + trx.getTipoOperacion());
        }
    }

    private TransaccionResponseDTO procesarReversoSalida(Transaccion trx, String motivo) {
        String numeroCuentaOrigen = obtenerNumeroCuenta(trx.getIdCuentaOrigen());
        Map<String, Object> cuentaOrigenDetalles = obtenerDetallesCuenta(trx.getIdCuentaOrigen());
        String nombreOrigen = "Cliente Arcbank";
        if (cuentaOrigenDetalles != null && cuentaOrigenDetalles.get("nombreTitular") != null) {
            nombreOrigen = cuentaOrigenDetalles.get("nombreTitular").toString();
        }

        try {
            switchClientService.enviarReverso(
                    trx.getReferencia(),
                    motivo,
                    trx.getMonto(),
                    nombreOrigen,
                    numeroCuentaOrigen,
                    "Beneficiario Externo",
                    trx.getCuentaExterna(),
                    trx.getIdBancoExterno());
        } catch (Exception e) {
            throw new BusinessException("El Switch rechazó la solicitud de reverso: " + e.getMessage());
        }

        procesarSaldo(trx.getIdCuentaOrigen(), trx.getMonto());

        trx.setEstado("REVERSADA");
        Transaccion guardada = transaccionRepository.save(trx);
        return mapearADTO(guardada, null);
    }

    private TransaccionResponseDTO procesarDevolucionIniciada(Transaccion trx, String motivo) {

        try {
            procesarSaldo(trx.getIdCuentaDestino(), trx.getMonto().negate());
        } catch (Exception e) {
            throw new BusinessException("No hay saldo suficiente para devolver la transacción.");
        }

        String numeroCuentaNuestra = obtenerNumeroCuenta(trx.getIdCuentaDestino());

        try {
            switchClientService.enviarReverso(
                    trx.getReferencia(),
                    motivo,
                    trx.getMonto(),
                    "Arcbank Initiate Return",
                    numeroCuentaNuestra,
                    "Banco Origen Original",
                    "UNKNOWN",
                    trx.getIdBancoExterno());

            trx.setEstado("DEVUELTA");
            Transaccion guardada = transaccionRepository.save(trx);
            log.info("Devolución aceptada por Switch y procesada localmente. TxID: {}", trx.getIdTransaccion());
            return mapearADTO(guardada, null);

        } catch (Exception e) {
            log.error("Fallo al enviar devolución al Switch: {}. Haciendo Rollback.", e.getMessage());

            procesarSaldo(trx.getIdCuentaDestino(), trx.getMonto());

            throw new BusinessException("El Switch rechazó la devolución: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void procesarDevolucionEntrante(com.arcbank.cbs.transaccion.dto.SwitchDevolucionRequest request) {
        String originalInstructionId = request.getBody().getOriginalInstructionId();
        // Mapeo: body.returnInstructionId -> Referencia (Unique Key Fix)
        String returnInstructionId = request.getBody().getReturnInstructionId();
        if (returnInstructionId == null || returnInstructionId.isBlank()) {
            // Fallback si por alguna razón no viene (aunque es obligatorio)
            log.warn("returnInstructionId viente vacío, generando uno nuevo para evitar colisión.");
            returnInstructionId = java.util.UUID.randomUUID().toString();
        }

        BigDecimal amount = request.getBody().getReturnAmount().getValue();
        String motivo = request.getBody().getReturnReason();
        // Mapeo: header.originatingBankId -> IdBancoExterno
        String originatingBank = request.getHeader().getOriginatingBankId();

        // Mapeo: header.creationDateTime -> FechaCreacion
        java.time.LocalDateTime fechaCreacion;
        try {
            String dateStr = request.getHeader().getCreationDateTime();
            if (dateStr != null) {
                fechaCreacion = java.time.ZonedDateTime.parse(dateStr).toLocalDateTime();
            } else {
                fechaCreacion = java.time.LocalDateTime.now();
            }
        } catch (Exception e) {
            log.warn("Error parseando fecha creación: {}. Usando fecha actual.",
                    request.getHeader().getCreationDateTime());
            fechaCreacion = java.time.LocalDateTime.now();
        }

        log.info("🔄 Procesando devolución entrante (pacs.004). Original: {}, ReturnID: {}",
                originalInstructionId, returnInstructionId);

        Transaccion trxOriginal = transaccionRepository.findByReferencia(originalInstructionId)
                .orElseThrow(
                        () -> new BusinessException("Transacción original no encontrada: " + originalInstructionId));

        if ("REVERSADA".equals(trxOriginal.getEstado()) || "DEVUELTA".equals(trxOriginal.getEstado())) {
            log.warn("Transacción ya procesada como reversada: {}", trxOriginal.getIdTransaccion());
            return;
        }

        Integer idCuentaAfectada;
        BigDecimal montoImpacto;
        boolean esReversoDeEntrada = false;

        if ("TRANSFERENCIA_SALIDA".equals(trxOriginal.getTipoOperacion()) ||
                "TRANSFERENCIA_INTERBANCARIA".equals(trxOriginal.getTipoOperacion())) {

            idCuentaAfectada = trxOriginal.getIdCuentaOrigen();
            montoImpacto = amount;

        } else if ("TRANSFERENCIA_ENTRADA".equals(trxOriginal.getTipoOperacion())) {

            idCuentaAfectada = trxOriginal.getIdCuentaDestino();
            montoImpacto = amount.negate();
            esReversoDeEntrada = true;

        } else {
            log.warn("Se recibió devolución para una transacción de tipo no soportado: {}",
                    trxOriginal.getTipoOperacion());
            return;
        }

        BigDecimal nuevoSaldo = procesarSaldo(idCuentaAfectada, montoImpacto);

        Transaccion.TransaccionBuilder reversoBuilder = Transaccion.builder()
                .referencia(returnInstructionId) // ✅ SOLUCION ERROR DUPLICATE KEY
                .idTransaccionReversa(trxOriginal.getIdTransaccion()) // ✅ Link a original
                .tipoOperacion("REVERSO") // ✅ Constante
                .estado("COMPLETADA")
                .monto(amount)
                .saldoResultante(nuevoSaldo)
                .idBancoExterno(originatingBank)
                .cuentaExterna(trxOriginal.getCuentaExterna())
                .descripcion("Reverso Switch: " + motivo)
                .canal("SWITCH") // ✅ Default
                .idSucursal(0) // ✅ Default
                .fechaCreacion(fechaCreacion); // ✅ Mapeo Fecha

        if (esReversoDeEntrada) {
            reversoBuilder.idCuentaOrigen(idCuentaAfectada);
            reversoBuilder.idCuentaDestino(null);
        } else {
            reversoBuilder.idCuentaDestino(idCuentaAfectada);
            reversoBuilder.idCuentaOrigen(null);
        }

        Transaccion trxReverso = reversoBuilder.build();
        transaccionRepository.save(trxReverso);

        trxOriginal.setEstado("REVERSADA");
        trxOriginal.setDescripcion(trxOriginal.getDescripcion() + " [R]");
        transaccionRepository.save(trxOriginal);

        log.info("✅ Devolución procesada exitosamente. Nueva TxID: {}", trxReverso.getIdTransaccion());
    }

    @Override
    public List<Map<String, String>> obtenerMotivosDevolucion() {
        return switchClientService.obtenerMotivosDevolucion();
    }

    @Override
    public String consultarEstadoPorInstructionId(String instructionId) {
        return transaccionRepository.findByReferencia(instructionId)
                .map(t -> {
                    String estado = t.getEstado();
                    if ("COMPLETADA".equalsIgnoreCase(estado))
                        return "COMPLETED";
                    if ("PENDIENTE".equalsIgnoreCase(estado))
                        return "PENDING";
                    if ("REVERSADA".equalsIgnoreCase(estado))
                        return "REVERSED";
                    return estado;
                })
                .orElse("NOT_FOUND");
    }

    @Override
    public Map<String, Object> validarCuentaExterna(String targetBankId, String targetAccountNumber) {
        return switchClientService.validarCuenta(targetBankId, targetAccountNumber);
    }

    @Override
    public Map<String, Object> validarCuentaLocal(String numeroCuenta) {
        try {
            Map<String, Object> cuenta = cuentaCliente.buscarPorNumero(numeroCuenta);
            if (cuenta != null) {
                String estado = "ACTIVE"; // Default
                if (cuenta.get("estado") != null) {
                    estado = cuenta.get("estado").toString();
                }

                // Obtener el nombre del titular desde el microservicio de clientes
                String titular = "CLIENTE ARCBANK"; // Fallback
                if (cuenta.get("idCliente") != null) {
                    try {
                        Integer idCliente = Integer.valueOf(cuenta.get("idCliente").toString());
                        Map<String, Object> cliente = clienteCliente.obtenerCliente(idCliente);
                        if (cliente != null && cliente.get("nombreCompleto") != null) {
                            titular = cliente.get("nombreCompleto").toString();
                            log.info("🎯 Nombre del titular obtenido: {} para cuenta {}", titular, numeroCuenta);
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ No se pudo obtener nombre del cliente para cuenta {}: {}", numeroCuenta,
                                e.getMessage());
                    }
                }

                return Map.of(
                        "exists", true,
                        "ownerName", titular,
                        "currency", "USD",
                        "status", estado);
            }
        } catch (Exception e) {
            log.warn("Cuenta no encontrada para validación: {}", numeroCuenta);
        }
        return Map.of("exists", false);
    }

    @Override
    public TransaccionResponseDTO buscarPorReferencia(String referencia) {
        Transaccion tx = transaccionRepository.findByReferencia(referencia)
                .orElseThrow(() -> new BusinessException("Transacción no encontrada con referencia: " + referencia));
        return mapearADTO(tx, null);
    }

    @Override
    public Map<String, Object> buscarPorCodigoReferencia(String codigoReferencia) {
        log.info("Buscando transacción por código de referencia: {}", codigoReferencia);
        Transaccion tx = transaccionRepository.findByCodigoReferencia(codigoReferencia)
                .orElseThrow(() -> new BusinessException(
                        "Transacción no encontrada con código de referencia: " + codigoReferencia));

        // Reutilizamos la lógica completa de detalle (validaciones, switch, cliente)
        return obtenerDetallePorId(tx.getIdTransaccion());
    }

    @Override
    public Map<String, Object> buscarConDetalleSwitch(String referencia) {
        // 1. Buscar transacción local
        Transaccion tx = transaccionRepository.findByReferencia(referencia)
                .orElseThrow(() -> new BusinessException("Transacción no encontrada con referencia: " + referencia));

        // 2. Validar que sea una transacción saliente interbancaria (reversible)
        boolean esReversible = tx.getTipoOperacion() != null &&
                (tx.getTipoOperacion().contains("SALIDA") || tx.getTipoOperacion().contains("INTERBANCARIA"));

        // 3. Validar que esté dentro del rango de 24 horas
        java.time.LocalDateTime ahora = java.time.LocalDateTime.now();
        java.time.LocalDateTime fechaTx = tx.getFechaCreacion();
        long horasTranscurridas = java.time.Duration.between(fechaTx, ahora).toHours();
        boolean dentroDe24H = horasTranscurridas <= 24;

        // 4. Validar estado (no reversada, no devuelta, no fallida)
        boolean estadoValido = tx.getEstado() != null &&
                !tx.getEstado().equals("REVERSADA") &&
                !tx.getEstado().equals("DEVUELTA") &&
                !tx.getEstado().equals("FALLIDA");

        // 5. Construir respuesta con todos los datos
        java.util.Map<String, Object> detalle = new java.util.HashMap<>();
        detalle.put("idTransaccion", tx.getIdTransaccion());
        detalle.put("referencia", tx.getReferencia());
        detalle.put("tipoOperacion", tx.getTipoOperacion());
        detalle.put("monto", tx.getMonto());
        detalle.put("fechaCreacion", tx.getFechaCreacion());
        detalle.put("descripcion", tx.getDescripcion());
        detalle.put("estado", tx.getEstado());
        detalle.put("cuentaExterna", tx.getCuentaExterna());
        detalle.put("bancoDestino", tx.getIdBancoExterno());
        detalle.put("canal", tx.getCanal());
        detalle.put("codigoReferencia", tx.getCodigoReferencia()); // Add reference code to response

        // Info de validación
        detalle.put("esReversible", esReversible);
        detalle.put("dentroDe24Horas", dentroDe24H);
        detalle.put("estadoValido", estadoValido);
        detalle.put("puedeReversarse", esReversible && dentroDe24H && estadoValido);

        // 6. Intentar consultar estado en Switch (si es interbancaria)
        if (tx.getIdBancoExterno() != null && tx.getReferencia() != null) {
            try {
                java.util.Map<String, Object> resultado = switchClientService.consultarEstado(tx.getReferencia());
                String estadoSwitch = resultado != null && resultado.get("status") != null
                        ? resultado.get("status").toString()
                        : "UNKNOWN";
                detalle.put("estadoSwitch", estadoSwitch);
            } catch (Exception e) {
                log.warn("No se pudo consultar estado en Switch: {}", e.getMessage());
                detalle.put("estadoSwitch", "NO_DISPONIBLE");
            }
        }

        return detalle;
    }

    @Override
    public Map<String, Object> obtenerDetallePorId(Integer id) {
        // 1. Buscar transacción por ID numérico
        Transaccion tx = transaccionRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Transacción no encontrada con ID: " + id));

        // 2. Si es una transacción interbancaria con estado PENDIENTE, consultar Switch
        String estadoSwitch = null;
        boolean estadoActualizado = false;
        if (tx.getReferencia() != null && tx.getIdBancoExterno() != null) {
            try {
                Map<String, Object> resultadoSwitch = switchClientService.consultarEstado(tx.getReferencia());
                if (resultadoSwitch != null && resultadoSwitch.get("status") != null) {
                    estadoSwitch = resultadoSwitch.get("status").toString();
                    log.info("Estado en Switch para Tx {}: {}", tx.getIdTransaccion(), estadoSwitch);

                    // Si el Switch dice COMPLETED pero la BD dice PENDIENTE, actualizar
                    if ("COMPLETED".equalsIgnoreCase(estadoSwitch) && "PENDIENTE".equals(tx.getEstado())) {
                        tx.setEstado("COMPLETADA");
                        transaccionRepository.save(tx);
                        estadoActualizado = true;
                        log.info("Estado actualizado a COMPLETADA para Tx {} basado en respuesta del Switch",
                                tx.getIdTransaccion());
                    }
                    // Si el Switch dice FAILED pero la BD no lo refleja
                    else if ("FAILED".equalsIgnoreCase(estadoSwitch) && !"FALLIDA".equals(tx.getEstado())) {
                        tx.setEstado("FALLIDA");
                        transaccionRepository.save(tx);
                        estadoActualizado = true;
                        log.info("Estado actualizado a FALLIDA para Tx {} basado en respuesta del Switch",
                                tx.getIdTransaccion());
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudo consultar estado en Switch para Tx {}: {}", tx.getIdTransaccion(), e.getMessage());
                estadoSwitch = "NO_DISPONIBLE";
            }
        }

        // 3. Validar que sea una transacción saliente interbancaria (reversible)
        boolean esReversible = tx.getTipoOperacion() != null &&
                (tx.getTipoOperacion().contains("SALIDA") || tx.getTipoOperacion().contains("INTERBANCARIA"));

        // 4. Validar que esté dentro del rango de 24 horas
        java.time.LocalDateTime ahora = java.time.LocalDateTime.now();
        java.time.LocalDateTime fechaTx = tx.getFechaCreacion();
        long horasTranscurridas = java.time.Duration.between(fechaTx, ahora).toHours();
        boolean dentroDe24H = horasTranscurridas <= 24;

        // 5. Validar estado (no reversada, no devuelta, no fallida)
        boolean estadoValido = tx.getEstado() != null &&
                !tx.getEstado().equals("REVERSADA") &&
                !tx.getEstado().equals("DEVUELTA") &&
                !tx.getEstado().equals("FALLIDA");

        // 5. Obtener nombre del ordenante (cuenta origen -> cliente)
        String nombreOrdenante = "No disponible";
        String numeroCuentaOrigen = "";
        if (tx.getIdCuentaOrigen() != null) {
            try {
                Map<String, Object> cuentaOrigenDetalles = obtenerDetallesCuenta(tx.getIdCuentaOrigen());
                if (cuentaOrigenDetalles != null) {
                    // Obtener número de cuenta
                    if (cuentaOrigenDetalles.get("numeroCuenta") != null) {
                        numeroCuentaOrigen = cuentaOrigenDetalles.get("numeroCuenta").toString();
                    }
                    // Intentar obtener nombre directamente si existe en cuenta
                    if (cuentaOrigenDetalles.get("nombreTitular") != null) {
                        nombreOrdenante = cuentaOrigenDetalles.get("nombreTitular").toString();
                    } else if (cuentaOrigenDetalles.get("idCliente") != null) {
                        // Si no hay nombreTitular, consultar el microservicio de clientes
                        Integer idCliente = Integer.parseInt(cuentaOrigenDetalles.get("idCliente").toString());
                        try {
                            Map<String, Object> clienteDetalles = clienteCliente.obtenerCliente(idCliente);
                            if (clienteDetalles != null) {
                                String nombre = clienteDetalles.get("nombres") != null
                                        ? clienteDetalles.get("nombres").toString()
                                        : "";
                                String apellido = clienteDetalles.get("apellidos") != null
                                        ? clienteDetalles.get("apellidos").toString()
                                        : "";
                                nombreOrdenante = (nombre + " " + apellido).trim();
                                if (nombreOrdenante.isEmpty()) {
                                    nombreOrdenante = clienteDetalles.get("nombreCompleto") != null
                                            ? clienteDetalles.get("nombreCompleto").toString()
                                            : "Cliente ID: " + idCliente;
                                }
                            }
                        } catch (Exception ce) {
                            log.warn("No se pudo obtener datos del cliente {}: {}", idCliente, ce.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudo obtener datos de cuenta origen: {}", e.getMessage());
            }
        }

        // 6. Obtener nombre del beneficiario (de la descripción)
        String nombreBeneficiario = "No disponible";
        if (tx.getDescripcion() != null) {
            String desc = tx.getDescripcion();
            // Intentar extraer nombre de la descripción si tiene formato conocido
            if (desc.contains(" a ")) {
                nombreBeneficiario = desc.substring(desc.lastIndexOf(" a ") + 3).trim();
            } else if (desc.contains("para ")) {
                nombreBeneficiario = desc.substring(desc.lastIndexOf("para ") + 5).trim();
            } else {
                nombreBeneficiario = desc; // Usar descripción completa como fallback
            }
        }

        // 7. Construir respuesta con todos los datos
        java.util.Map<String, Object> detalle = new java.util.HashMap<>();
        detalle.put("idTransaccion", tx.getIdTransaccion());
        detalle.put("referencia", tx.getReferencia());
        detalle.put("tipoOperacion", tx.getTipoOperacion());
        detalle.put("monto", tx.getMonto());
        detalle.put("fechaCreacion", tx.getFechaCreacion());
        detalle.put("descripcion", tx.getDescripcion());
        detalle.put("estado", tx.getEstado());
        detalle.put("cuentaExterna", tx.getCuentaExterna());
        detalle.put("bancoDestino", tx.getIdBancoExterno());
        detalle.put("canal", tx.getCanal());
        detalle.put("idCuentaOrigen", tx.getIdCuentaOrigen());
        detalle.put("idCuentaDestino", tx.getIdCuentaDestino());

        // Nombres agregados
        detalle.put("nombreOrdenante", nombreOrdenante);
        detalle.put("numeroCuentaOrigen", numeroCuentaOrigen);
        detalle.put("nombreBeneficiario", nombreBeneficiario);

        // Info de validación
        detalle.put("esReversible", esReversible);
        detalle.put("dentroDe24Horas", dentroDe24H);
        detalle.put("estadoValido", estadoValido);
        detalle.put("puedeReversarse", esReversible && dentroDe24H && estadoValido);
        detalle.put("horasTranscurridas", horasTranscurridas);

        // Info del Switch
        detalle.put("estadoSwitch", estadoSwitch);
        detalle.put("estadoActualizadoDesdeSwitch", estadoActualizado);

        return detalle;
    }
    @Override
    public java.util.Map<String, Object> obtenerSaldoTecnico() {
        return switchClientService.obtenerSaldoTecnico();
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> obtenerBancos() {
        return switchClientService.obtenerBancos();
    }
}
