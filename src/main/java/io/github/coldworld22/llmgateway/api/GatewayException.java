package io.github.coldworld22.llmgateway.api;

import org.springframework.http.HttpStatus;

/** An error answered in OpenAI's error format, so existing OpenAI client libraries understand it. */
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String type;

    public GatewayException(HttpStatus status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }
}
