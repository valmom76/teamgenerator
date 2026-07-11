package com.boraver.teamgenerator.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.boraver.teamgenerator.exception.LimitExceededException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        if (isSseRequest(request)) {
            log.warn("Erro em requisição SSE: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("error", Objects.toString(ex.getMessage(), "Requisição inválida"));

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> unauthorized(
            SecurityException ex,
            HttpServletRequest request
    ) {
        if (isSseRequest(request)) {
            log.warn("Erro de segurança em requisição SSE: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("error", Objects.toString(ex.getMessage(), "Não autorizado"));

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> generic(
            Exception ex,
            HttpServletRequest request
    ) {
        if (isSseRequest(request)) {
            log.warn("Erro genérico em requisição SSE: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        log.error("Erro interno na API", ex);

        Map<String, Object> body = new HashMap<>();
        body.put("error", Objects.toString(ex.getMessage(), "Erro interno do servidor"));

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<?> handleLimitExceeded(
            LimitExceededException ex,
            HttpServletRequest request
    ) {
        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).build();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("error", Objects.toString(ex.getMessage(), "Limite excedido"));

        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED) // 402
                .body(body);
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        String uri = request.getRequestURI();

        return (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE))
                || (uri != null && uri.matches("^/championships/[^/]+/stream$"));
    }
}