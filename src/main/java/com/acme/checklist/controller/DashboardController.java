package com.acme.checklist.controller;

import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.dashboard.CalibrationStatsDTO;
import com.acme.checklist.payload.dashboard.MaintenanceStatsDTO;
import com.acme.checklist.payload.dashboard.SoonDTO;
import com.acme.checklist.payload.dashboard.SummaryDTO;
import com.acme.checklist.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/get/summary")
    public Mono<ResponseEntity<SummaryDTO>> getSummary() {
        return dashboardService.getSummary()
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/get/soon")
    Mono<ListResponse<List<SoonDTO>>> getSoon() {
        return dashboardService.getSoon();
    }

    @GetMapping("/get/maintenance-stats")
    public Mono<ListResponse<List<MaintenanceStatsDTO>>> getMaintenanceStats() {
        return dashboardService.getMaintenanceStats();
    }

    @GetMapping("/get/calibration-stats")
    public Mono<ListResponse<List<CalibrationStatsDTO>>> getCalibrationStats() {
        return dashboardService.getCalibrationStats();
    }
}
