package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.maintenance.*;
import com.acme.checklist.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
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

    @PutMapping(value = "/update", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Mono<ApiResponse<Void>> update(
            @RequestPart("request") String requestJson,
            @RequestPart(value = "files", required = false) List<FilePart> files) {
        return maintenanceService.update(requestJson, files);
    }

    @GetMapping("/get/page")
    public Mono<PagedResponse<MaintenanceListDTO>> getPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")  int index,
            @RequestParam(defaultValue = "10") int size) {
        return maintenanceService.getWithRole(keyword, index, size);
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
    public Flux<MaintenanceDepartmentSummaryDTO> getDepartmentSummary() {
        return maintenanceService.getDepartmentSummaryWithRole();
    }
}