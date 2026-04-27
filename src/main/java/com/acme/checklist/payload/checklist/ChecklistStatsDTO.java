package com.acme.checklist.payload.checklist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistStatsDTO {
    private String department;
    private Integer month;
    private Integer year;
    private Long dailyUse;
    private Long weeklyCheckDone;
    private Long weeklyCheckWaitLeader;
    private Long weeklyCheckWaitManager;
    private Integer weeklyCheckPercent;
    private Integer weeklyApprovePercent;
    private Long notCheckDone;
    private Long notCheckDoneNotCheck;
    private Long notCheckWaitLeader;
    private Long notCheckWaitManager;
    private Integer notCheckApprovePercent;
    private Integer notCheckApprovePercentFinal;
}