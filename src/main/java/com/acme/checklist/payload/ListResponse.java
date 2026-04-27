package com.acme.checklist.payload;

import com.acme.checklist.constant.MsgCode;
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
public class ListResponse<T> {
    @Builder.Default
    private boolean success = false;

    private String message;

    private String code;

    private T data;

    private String error;

    private Boolean hasMore;

    public static <T> ListResponse<T> success(String code, Boolean hasMore) {
        return ListResponse.<T>builder()
                .success(true)
                .code(code)
                .message(MsgCode.valueOf(code).getMessage())
                .data(null)
                .hasMore(hasMore)
                .build();
    }

    public static <T> ListResponse<T> success(String code, Boolean hasMore, T data) {
        return ListResponse.<T>builder()
                .success(true)
                .code(code)
                .message(MsgCode.valueOf(code).getMessage())
                .data(data)
                .hasMore(hasMore)
                .build();
    }

    public static <T> ListResponse<T> error(String code) {
        return ListResponse.<T>builder()
                .success(false)
                .code(code)
                .message(MsgCode.valueOf(code).getMessage())
                .hasMore(false)
                .build();
    }
}