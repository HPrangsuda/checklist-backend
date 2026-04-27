package com.acme.checklist.payload.question;

import com.acme.checklist.entity.Question;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionResponseDTO {
    private Long id;
    private String detail;
    private String description;
    private AuditMemberDTO createdBy;
    private AuditMemberDTO updatedBy;

    public static QuestionResponseDTO from(Question question, AuditMemberDTO createdBy, AuditMemberDTO updatedBy) {
        if (question == null) {
            return null;
        }

        return QuestionResponseDTO.builder()
                .id(question.getId())
                .detail(question.getDetail())
                .description(question.getDescription())
                .build();
    }
}
