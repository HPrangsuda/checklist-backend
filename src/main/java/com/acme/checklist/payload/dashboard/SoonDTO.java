package com.acme.checklist.payload.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoonDTO {
    private String id;
    private String machineCode;
    private String machineName;
    private String type;
    private LocalDate dueDate;
    private String assignee;
}