package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.maintenance.*;
import com.acme.checklist.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody MaintenanceDTO dto) {
        return maintenanceService.update(dto);
    }

    @GetMapping("/get/page")
    public Mono<PagedResponse<MaintenanceResponseDTO>> getPage(
            @RequestParam(required = false) String  keyword,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String  department,
            @RequestParam(required = false) String  status,
            @RequestParam(defaultValue = "0")  int index,
            @RequestParam(defaultValue = "10") int size) {
        return maintenanceService.getPage(keyword, year, department, status, index, size);
    }

    @GetMapping("/filter-options")
    public Mono<MaintenanceFilterOptionsDTO> getFilterOptions() {
        return maintenanceService.getFilterOptions();
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<MaintenanceResponseDTO>> getById(@PathVariable Long id) {
        return maintenanceService.getById(id);
    }

    @GetMapping("/get/{machineCode}")
    public Mono<ApiResponse<List<MaintenanceResponseDTO>>> getByMachineCode(@PathVariable String machineCode) {
        return maintenanceService.getByMachineCode(machineCode);
    }

    @GetMapping("/department-summary")
    public Flux<MaintenanceDepartmentSummaryDTO> getDepartmentSummary(
            @RequestParam(required = false) Integer year) {
        return maintenanceService.getDepartmentSummaryWithRole(year);
    }

    @GetMapping("/monthly-summary")
    public Flux<MaintenanceMonthlyDTO> getMonthlySummary(
            @RequestParam(required = false) Integer year) {
        return maintenanceService.getMonthlyPlanActualSummary(year);
    }
}