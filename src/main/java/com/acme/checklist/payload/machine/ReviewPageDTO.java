package com.acme.checklist.payload.machine;

import java.util.List;

public record ReviewPageDTO(
        List<ReviewItemDTO> items,
        int                 index,
        int                 size,
        long                total,
        int                 totalPages
) {
    public static ReviewPageDTO of(List<ReviewItemDTO> items, int index, int size, long total) {
        int pages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new ReviewPageDTO(items, index, size, total, pages);
    }
}