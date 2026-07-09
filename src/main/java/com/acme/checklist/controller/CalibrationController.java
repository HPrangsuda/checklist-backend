package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.calibration.*;
import com.acme.checklist.service.CalibrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/calibration")
@RequiredArgsConstructor
public class CalibrationController {

    private final CalibrationService calibrationService;

    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody CalibrationDTO dto) {
        return calibrationService.update(dto);
    }

    /**
     * Paginated list with optional filters:
     *   keyword, year, department, results, calibrationStatus
     * Sorted by due_date DESC NULLS LAST.
     */
    @GetMapping("/get/page")
    public Mono<PagedResponse<CalibrationResponseDTO>> getPage(
            @RequestParam(required = false) String  keyword,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String  department,
            @RequestParam(required = false) String  results,
            @RequestParam(required = false) String  calibrationStatus,
            @RequestParam(defaultValue = "0")  int index,
            @RequestParam(defaultValue = "10") int size) {
        return calibrationService.getPage(keyword, year, department, results, calibrationStatus, index, size);
    }

    @GetMapping("/filter-options")
    public Mono<CalibrationFilterOptionsDTO> getFilterOptions() {
        return calibrationService.getFilterOptions();
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<CalibrationResponseDTO>> getById(@PathVariable Long id) {
        return calibrationService.getById(id);
    }

    @GetMapping("/get/{machineCode}")
    public Mono<ApiResponse<List<CalibrationResponseDTO>>> getByMachineCode(@PathVariable String machineCode) {
        return calibrationService.getByMachineCode(machineCode);
    }

    @GetMapping("/department-summary")
    public Flux<CalibrationDepartmentSummaryDTO> getDepartmentSummary() {
        return calibrationService.getDepartmentSummaryWithRole();
    }

    @GetMapping("/monthly-summary")
    public Flux<CalibrationMonthlyDTO> getMonthlySummary(
            @RequestParam(required = false) Integer year) {
        return calibrationService.getMonthlyPlanActualSummary(year);
    }
}