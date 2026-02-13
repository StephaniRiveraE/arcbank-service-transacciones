package com.arcbank.cbs.transaccion.dto;

import lombok.*;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwitchTransferRequest {

    private Header header;
    private Body body;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String messageId;
        private String creationDateTime;
        private String originatingBankId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Body {
        private String instructionId;
        private String endToEndId;
        private Amount amount;
        private Party debtor;
        private Party creditor;
        private String remittanceInformation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Amount {
        private String currency;
        private BigDecimal value;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Party {
        private String name;
        private String accountId;
        private String accountType;
        private String bankId;
    }
}
