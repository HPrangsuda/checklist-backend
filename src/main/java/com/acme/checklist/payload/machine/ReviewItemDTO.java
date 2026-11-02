package com.acme.checklist.payload.machine;

import java.time.LocalDate;

public record ReviewItemDTO(
        Long      id,
        String    machineCode,
        String    machineName,
        LocalDate lastReview,
        String    reviewBy,
        String    reviewByName
) {}