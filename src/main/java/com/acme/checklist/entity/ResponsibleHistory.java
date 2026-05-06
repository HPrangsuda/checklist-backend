package com.acme.checklist.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("responsible_history")
public class ResponsibleHistory {
    private String machineCode;
    private Long responsiblePersonId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}