package com.acme.checklist.payload.machineChecklist;

import com.acme.checklist.entity.MachineChecklist;
import com.acme.checklist.entity.Question;
import com.acme.checklist.payload.question.QuestionDTO;
import com.acme.checklist.payload.question.QuestionSummaryDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MachineChWithQuestionDTO {
    private Long id;
    private String machineCode;
    private Boolean isChoice;
    private Boolean checkStatus;
    private String resetTime;
    private QuestionSummaryDTO question;

    public static MachineChWithQuestionDTO from(
            MachineChecklist item,
            com.acme.checklist.entity.Question q
    ) {
        return MachineChWithQuestionDTO.builder()
                .id(item.getId())
                .machineCode(item.getMachineCode())
                .isChoice(item.getIsChoice())
                .checkStatus(item.getCheckStatus())
                .resetTime(item.getResetTime())
                .question(q != null ? QuestionSummaryDTO.from(q) : null)
                .build();
    }

    public static List<MachineChWithQuestionDTO> fromList(
            List<MachineChecklist> items,
            Map<Long, Question> questionMap
    ) {
        return items.stream()
                .map(item -> from(
                        item,
                        item.getQuestionId() != null ? questionMap.get(item.getQuestionId()) : null
                ))
                .collect(Collectors.toList());
    }
}