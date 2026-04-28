package com.acme.checklist.payload.checklist;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChecklistResponseDTO {
    private Long id;
    private Boolean recheck;
    private String machineCode;
    private String machineName;
    private String machineStatus;
    private String machineChecklist;
    private String machineNote;
    private String image;
    private String userName;
    private String supervisor;
    private Instant dateSupervisorChecked;
    private String manager;
    private Instant dateManagerChecked;
    private String checklistStatus;
    private String reasonNotChecked;
    private String jobDetail;
    private AuditMemberDTO createdBy;
    private AuditMemberDTO updatedBy;

    public static ChecklistResponseDTO from(ChecklistRecord checklistRecord, AuditMemberDTO createdBy, AuditMemberDTO updatedBy) {
        return ChecklistResponseDTO.builder()
                .id(checklistRecord.getId())
                .recheck(checklistRecord.getRecheck())
                .machineCode(checklistRecord.getMachineCode())
                .machineName(checklistRecord.getMachineName())
                .machineStatus(checklistRecord.getMachineStatus())
                .machineChecklist(checklistRecord.getMachineChecklist())
                .machineNote(checklistRecord.getMachineNote())
                .image(checklistRecord.getImage())
                .userName(checklistRecord.getUserName())
                .supervisor(String.valueOf(checklistRecord.getSupervisor()))
                .dateSupervisorChecked(checklistRecord.getDateSupervisorChecked())
                .manager(String.valueOf(checklistRecord.getManager()))
                .dateManagerChecked(checklistRecord.getDateManagerChecked())
                .checklistStatus(checklistRecord.getChecklistStatus())
                .reasonNotChecked(checklistRecord.getReasonNotChecked())
                .jobDetail(checklistRecord.getJobDetail())
                .createdBy(createdBy)
                .updatedBy(updatedBy)

                .build();
    }
}