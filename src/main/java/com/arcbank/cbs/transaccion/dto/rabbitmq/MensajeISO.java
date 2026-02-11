package com.arcbank.cbs.transaccion.dto.rabbitmq;

import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MensajeISO {
    private Header header;
    private Body body;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Header {
        private String messageId; // ID único del mensaje
        private String creationDateTime; // Timestamp ISO 8601
        private String originatingBankId; // BIC del banco origen
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Body {
        private String instructionId; // UUID de la instrucción
        private String endToEndId; // Referencia del cliente
        private Amount amount;
        private Debtor debtor; // Ordenante
        private Creditor creditor; // Beneficiario
        private String remittanceInformation; // Concepto
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Amount {
        private String currency; // "USD"
        private BigDecimal value; // Monto
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Creditor {
        private String name;
        private String accountId;
        private String accountType;
        private String targetBankId; // ROUTING KEY
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Debtor {
        private String name;
        private String accountId;
        private String accountType;
    }
}
