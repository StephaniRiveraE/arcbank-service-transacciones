package com.arcbank.cbs.transaccion.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwitchTransferResponse {

    private boolean success;
    private DataBody data;
    private ErrorBody error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataBody {
        private UUID instructionId;
        private String estado;
        private String bancoOrigen;
        private String bancoDestino;
        private BigDecimal monto;
        private LocalDateTime timestamp;
        private LocalDateTime fechaCreacion;
        private String codigoReferencia;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorBody {
        private String code;
        private String message;
    }
}
