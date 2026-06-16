package com.acme.checklist.entity.enums;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum MachineStatus {

    OPERATIONAL("OPERATIONAL"),
    NON_OPERATIONAL("NON-OPERATIONAL"),
    UNDER_MAINTENANCE("UNDER MAINTENANCE");

    private final String dbValue;

    MachineStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static List<String> activeDbValues() {
        return Arrays.stream(values())
                .map(MachineStatus::getDbValue)
                .toList();
    }

    public static String sqlInClause() {
        return activeDbValues().stream()
                .map(v -> "'" + v + "'")
                .collect(Collectors.joining(", "));
    }
}