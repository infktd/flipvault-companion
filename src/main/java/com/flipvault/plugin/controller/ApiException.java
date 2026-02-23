package com.flipvault.plugin.controller;

import lombok.Getter;

@Getter
public class ApiException extends Exception {
    private final int statusCode;
    private final String responseBody;

    public ApiException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }
}
