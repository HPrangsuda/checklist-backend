package com.acme.checklist.exception;

import lombok.Getter;

@Getter
public class ThrowException extends RuntimeException {
    private final String code;
    private final String msgValue;
    private final boolean isError;

    public ThrowException(String code) {
        this.code = code;
        this.msgValue = null;
        this.isError = false;
    }

    public ThrowException(String code, String msgValue) {
        this.code = code;
        this.msgValue = msgValue;
        this.isError = false;
    }

    public ThrowException(String code, Boolean isError) {
        this.code = code;
        this.msgValue = null;
        this.isError = isError;
    }
}
