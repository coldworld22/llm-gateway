package io.github.coldworld22.llmgateway.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> gateway(GatewayException e) {
        return body(e.status(), e.type(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_request_error", "The request body is invalid.");
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("type", type, "message", message)));
    }
}
