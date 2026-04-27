package com.acme.checklist.payload.machineChecklist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MachineChDTO {
    private Long id;
    private String machineCode;
    private Long questionId;
    private Boolean isChoice;
    private Boolean checkStatus;
    private String resetTime;

    private String  detail;
    private String  description;
}
