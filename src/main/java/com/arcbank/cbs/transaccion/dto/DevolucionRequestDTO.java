package com.arcbank.cbs.transaccion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevolucionRequestDTO {
    private Integer idTransaccion; // ID local de la transacción
    private String motivo; // FRAD, TECH, DUPL
}
