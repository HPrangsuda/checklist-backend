package com.acme.checklist.payload.machineChecklist;

import com.acme.checklist.entity.MachineChecklist;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MachineChResponseDTO {
    private String machineCode;
    private Long questionId;
    private Boolean isChoice;
    private Boolean checkStatus;
    private String resetTime;

    private AuditMemberDTO createdBy;
    private AuditMemberDTO updatedBy;

    public static MachineChResponseDTO from(MachineChecklist machineChecklist, AuditMemberDTO createdBy, AuditMemberDTO updatedBy) {
        if (machineChecklist == null) {
            return null;
        }

        return MachineChResponseDTO.builder()
                .machineCode(machineChecklist.getMachineCode())
                .questionId(machineChecklist.getQuestionId())
                .isChoice(machineChecklist.getIsChoice())
                .checkStatus(machineChecklist.getCheckStatus())
                .resetTime(machineChecklist.getResetTime())
                .createdBy(createdBy)
                .updatedBy(updatedBy)
                .build();
    }
}
