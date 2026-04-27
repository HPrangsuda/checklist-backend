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

    @GetMapping("/get/page")
    public Mono<PagedResponse<CalibrationListDTO>> getPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")  int index,
            @RequestParam(defaultValue = "10") int size) {
        return calibrationService.getWithRole(keyword, index, size);
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
}