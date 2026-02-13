package com.arcbank.cbs.transaccion.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        log.warn("Error de negocio: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.builder()
                .mensaje(ex.getMessage())
                .codigo("BUSINESS_ERROR")
                .fecha(LocalDateTime.now())
                .build();
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(NoResourceFoundException ex) {
        log.debug("Recurso no encontrado: {}", ex.getResourcePath());
        ErrorResponse error = ErrorResponse.builder()
                .mensaje("El recurso solicitado no existe.")
                .codigo("NOT_FOUND")
                .fecha(LocalDateTime.now())
                .build();
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(feign.FeignException.class)
    public ResponseEntity<ErrorResponse> handleFeignException(feign.FeignException ex) {
        log.error("Error en llamada externa (Feign): Status={}, Content={}", ex.status(), ex.contentUTF8());

        String remoteMessage = ex.contentUTF8();
        String mensaje = (remoteMessage != null && !remoteMessage.isBlank())
                ? remoteMessage
                : "Error en comunicación con la red interbancaria (Status: " + ex.status() + ")";

        ErrorResponse error = ErrorResponse.builder()
                .mensaje(mensaje)
                .codigo("EXTERNAL_SERVICE_ERROR")
                .fecha(LocalDateTime.now())
                .build();
        return new ResponseEntity<>(error, HttpStatus.valueOf(ex.status() > 0 ? ex.status() : 500));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        log.error("Error interno no controlado: ", ex);

        String mensajeReal = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();

        ErrorResponse error = ErrorResponse.builder()
                .mensaje("Error: " + mensajeReal)
                .codigo("INTERNAL_SERVER_ERROR")
                .fecha(LocalDateTime.now())
                .build();
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}