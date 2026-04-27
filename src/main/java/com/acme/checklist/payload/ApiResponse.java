package com.acme.checklist.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    @Builder.Default
    private boolean success = false;

    private String message;

    private String code;

    private T data;

    private String error;

    public static <T> ApiResponse<T> success(String code, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code(code)
                .message(code)
                .data(data)
                .build();
    }

    public static ApiResponse<Void> success(String code) {
        return ApiResponse.<Void>builder()
                .success(true)
                .code(code)
                .message(code)
                .build();
    }

    public static <T> ApiResponse<T> error(String code) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(code)
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String error) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(code)
                .error(error)
                .build();
    }
}