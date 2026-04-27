package com.acme.checklist.payload.maintenance;

import com.acme.checklist.payload.question.QuestionDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MaintenanceChDTO {
    private Long id;
    private String machineCode;
    private Long questionId;
    private Boolean isChoice;
    private Boolean checkStatus;
    private String resetTime;
    private QuestionDTO question;
}

