package com.acme.checklist.payload;

import com.acme.checklist.constant.MsgCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedResponse<T> {

    @Builder.Default
    private boolean success = false;

    private String message;

    private String code;

    private List<T> data;

    private int totalPages;

    private long totalElements;

    private int index;

    private int size;

    private String error;

    public static <T> PagedResponse<T> success(String code, List<T> data,
                                               int totalPages, long totalElements, int index, int size) {
        return PagedResponse.<T>builder()
                .success(true)
                .code(code)
                .message(MsgCode.valueOf(code).getMessage())
                .data(data)
                .totalPages(totalPages)
                .totalElements(totalElements)
                .index(index)
                .size(size)
                .build();
    }

    public static <T> PagedResponse<T> success(String code, int index, int size) {
        return PagedResponse.<T>builder()
                .success(true)
                .code(code)
                .message(MsgCode.valueOf(code).getMessage())
                .data(Collections.emptyList())
                .totalPages(0)
                .totalElements(0L)
                .index(index)
                .size(size)
                .build();
    }

    public static <T> PagedResponse<T> error(String code) {
        return PagedResponse.<T>builder()
                .success(false)
                .data(null)
                .code(code)
                .message(MsgCode.valueOf(code).getMessage())
                .build();
    }

    public static <T> PagedResponse<T> error(String code, String error) {
        return PagedResponse.<T>builder()
                .success(false)
                .data(null)
                .code(code)
                .error(error)
                .message(MsgCode.valueOf(code).getMessage())
                .build();
    }
}