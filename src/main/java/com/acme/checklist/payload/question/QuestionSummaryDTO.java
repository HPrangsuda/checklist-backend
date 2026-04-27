package com.acme.checklist.payload.question;

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
public class QuestionSummaryDTO {

    private Long id;
    private String detail;
    private String description;

    public static QuestionSummaryDTO from(com.acme.checklist.entity.Question q) {
        return QuestionSummaryDTO.builder()
                .id(q.getId())
                .detail(q.getDetail())
                .description(q.getDescription())
                .build();
    }
}