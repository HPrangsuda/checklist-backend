package com.acme.checklist.payload.machine;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FilterOptionsDTO {
    private List<Map<String, String>> departments;
    private List<String> machineStatuses;
    private List<String> checkStatuses;
    private List<String> responsiblePersons;
}