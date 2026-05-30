package com.acme.checklist.payload.checklist;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.member.MemberDTO;
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
    private MemberDTO supervisor;
    private Instant dateSupervisorChecked;
    private MemberDTO manager;
    private Instant dateManagerChecked;
    private String checklistStatus;
    private String reasonNotChecked;
    private String jobDetail;
    private AuditMemberDTO createdBy;
    private AuditMemberDTO updatedBy;
    private Instant createdAt;

    public static ChecklistResponseDTO from(
            ChecklistRecord record,
            AuditMemberDTO createdBy,
            AuditMemberDTO updatedBy,
            MemberDTO supervisor,
            MemberDTO manager) {
        return ChecklistResponseDTO.builder()
                .id(record.getId())
                .recheck(record.getRecheck())
                .machineCode(record.getMachineCode())
                .machineName(record.getMachineName())
                .machineStatus(record.getMachineStatus())
                .machineChecklist(record.getMachineChecklist())
                .machineNote(record.getMachineNote())
                .image(record.getImage())
                .userName(record.getUserName())
                .supervisor(supervisor)
                .dateSupervisorChecked(record.getDateSupervisorChecked())
                .manager(manager)
                .dateManagerChecked(record.getDateManagerChecked())
                .checklistStatus(record.getChecklistStatus())
                .reasonNotChecked(record.getReasonNotChecked())
                .jobDetail(record.getJobDetail())
                .createdBy(createdBy)
                .updatedBy(updatedBy)
                .createdAt(record.getCreatedAt())
                .build();
    }
}