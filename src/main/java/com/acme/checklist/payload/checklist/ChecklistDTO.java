package com.acme.checklist.payload.checklist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChecklistDTO {
    private Long id;
    private String checkType;
    private Boolean recheck;
    private String machineCode;
    private String machineName;
    private String machineStatus;
    private String machineChecklist;
    private String machineNote;
    private String image;
    private String userId;
    private String userName;
    private Long memberId;
    private Long supervisor;
    private Instant dateSupervisorChecked;
    private Long manager;
    private Instant dateManagerChecked;
    private String checklistStatus;
    private String reasonNotChecked;
    private String jobDetail;
}
