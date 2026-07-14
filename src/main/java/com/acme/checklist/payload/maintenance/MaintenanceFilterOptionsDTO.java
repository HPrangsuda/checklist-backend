package com.acme.checklist.payload.maintenance;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MaintenanceFilterOptionsDTO {

    private List<Integer>          years;
    private List<DepartmentOption> departments;
    private List<String>           statuses;

    @Data
    @Builder
    public static class DepartmentOption {
        private String code;
        private String name;
    }
}