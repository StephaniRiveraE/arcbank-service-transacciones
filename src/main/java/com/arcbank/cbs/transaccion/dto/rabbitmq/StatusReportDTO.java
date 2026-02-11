package com.arcbank.cbs.transaccion.dto.rabbitmq;

import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatusReportDTO {
    private Header header;
    private Body body;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Header {
        private String messageId; // ID único de la respuesta "RESP-" + UUID
        private String creationDateTime; // ISO 8601
        private String respondingBankId; // ARCBANK
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Body {
        private UUID originalInstructionId; // UUID
        private String originalMessageId; // Opcional
        private String status; // COMPLETED / REJECTED
        private String reasonCode; // AC03, etc.
        private String reasonDescription;
        private String processedDateTime; // ISO 8601
    }
}
