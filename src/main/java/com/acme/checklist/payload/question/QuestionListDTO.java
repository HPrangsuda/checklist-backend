package com.acme.checklist.payload.question;

import com.acme.checklist.entity.Question;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuestionListDTO {
    private Long id;
    private String detail;

    public static QuestionListDTO from(Question question) {
        if (question == null) {
            return null;
        }

        return QuestionListDTO.builder()
                .id(question.getId())
                .detail(question.getDetail())
                .build();
    }
}
