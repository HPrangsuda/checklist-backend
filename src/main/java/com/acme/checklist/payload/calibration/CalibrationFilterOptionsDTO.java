package com.acme.checklist.payload.calibration;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CalibrationFilterOptionsDTO {

    private List<Integer>         years;
    private List<DepartmentOption> departments;
    private List<String>          results;
    private List<String>          calibrationStatuses;

    @Data
    @Builder
    public static class DepartmentOption {
        private String code;
        private String name;
    }
}