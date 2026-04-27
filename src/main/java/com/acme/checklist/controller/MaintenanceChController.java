package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.machineChecklist.MachineChDTO;
import com.acme.checklist.payload.maintenance.MaintenanceChDTO;
import com.acme.checklist.payload.maintenance.MaintenanceDTO;
import com.acme.checklist.service.MaintenanceChService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/maintenance-checklist")
@RequiredArgsConstructor
public class MaintenanceChController {

    private final MaintenanceChService maintenanceChService;

    @GetMapping("/get/{id}")
    public Mono<ApiResponse<MaintenanceDTO>> getById(@PathVariable Long id) {
        log.debug("Get maintenance checklist | id={}", id);
        return maintenanceChService.getById(id);
    }

    @GetMapping("/by-machine")
    public Mono<List<MaintenanceChDTO>> getByMachineCode(
            @RequestParam String machineCode
    ) {
        log.debug("Get maintenance checklist by machine | machineCode={}", machineCode);
        return maintenanceChService.getByMachineCode(machineCode);
    }

    @PostMapping
    public Mono<ApiResponse<Void>> create(@RequestBody MachineChDTO dto) {
        log.info("Create maintenance checklist | machineCode={}, questionId={}", dto.getMachineCode(), dto.getQuestionId());
        return maintenanceChService.createItem(dto);
    }

    @DeleteMapping
    public Mono<ApiResponse<Void>> delete(@RequestBody List<Long> ids) {
        log.info("Delete maintenance checklist | ids={}", ids);
        return maintenanceChService.deleteItems(ids);
    }

    @PostMapping(value = "/save", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<Void>> save(
            @RequestPart("request") String requestJson,
            @RequestPart(value = "file", required = false) FilePart file
    ) {
        log.info("Save maintenance checklist");
        return maintenanceChService.save(requestJson, file);
    }
}