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
public class QuestionDTO {
    private Long id;
    private String detail;
    private String description;

    public QuestionDTO from(com.acme.checklist.entity.Question q) {
        return QuestionDTO.builder()
                .id(q.getId())
                .detail(q.getDetail())
                .description(q.getDescription())
                .build();
    }
}