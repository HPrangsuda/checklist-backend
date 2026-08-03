package com.acme.checklist.payload.question;

import com.acme.checklist.entity.Question;
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
    private Boolean isChoice;

    public static QuestionDTO from(Question q) {  // เพิ่ม static
        if (q == null) return null;
        return QuestionDTO.builder()
                .id(q.getId())
                .detail(q.getDetail())
                .description(q.getDescription())
                .isChoice(q.getIsChoice())
                .build();
    }
}