package com.acme.checklist.payload.checklist;

import com.acme.checklist.entity.ChecklistRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChecklistListDTO {
    private Long id;
    private String machineCode;
    private String machineName;
    private String createdAt;
    private String machineStatus;
    private String checklistStatus;
    private String userName;

    public static ChecklistListDTO from(ChecklistRecord checklistRecord) {
        return ChecklistListDTO.builder()
                .id(checklistRecord.getId())
                .machineCode(checklistRecord.getMachineCode())
                .machineName(checklistRecord.getMachineName())
                .createdAt(String.valueOf(checklistRecord.getCreatedAt()))
                .machineStatus(checklistRecord.getMachineStatus())
                .checklistStatus(checklistRecord.getChecklistStatus())
                .userName(checklistRecord.getUserName())
                .build();
    }
}
