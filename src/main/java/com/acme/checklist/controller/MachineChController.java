package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.machineChecklist.MachineChDTO;
import com.acme.checklist.payload.machineChecklist.MachineChResponseDTO;
import com.acme.checklist.payload.machineChecklist.MachineChWithQuestionDTO;
import com.acme.checklist.service.MachineChService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/machine-checklist")
@RequiredArgsConstructor
public class MachineChController {

    private final MachineChService machineChecklistService;

    @PostMapping
    public Mono<ApiResponse<Void>> create(@RequestBody MachineChDTO dto) {
        log.info("Create checklist | machineCode={}, questionId={}", dto.getMachineCode(), dto.getQuestionId());
        return machineChecklistService.create(dto);
    }

    @PutMapping
    public Mono<ApiResponse<Void>> update(@RequestBody MachineChDTO dto) {
        log.info("Update checklist | id={}", dto.getId());
        return machineChecklistService.update(dto);
    }

    @DeleteMapping
    public Mono<ApiResponse<Void>> delete(@RequestBody List<Long> ids) {
        log.info("Delete checklists | ids={}", ids);
        return machineChecklistService.delete(ids);
    }

    @GetMapping
    public Mono<PagedResponse<MachineChResponseDTO>> getAllWithPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.debug("Get all checklists paged | keyword={}, index={}, size={}", keyword, index, size);
        return machineChecklistService.getAllWithPage(keyword, index, size);
    }

    // ── ดึงทุก checklist ของ machine (พร้อม question detail) ──────────────
    @GetMapping("/by-machine")
    public Mono<ListResponse<List<MachineChWithQuestionDTO>>> getByMachineCode(
            @RequestParam String machineCode
    ) {
        log.debug("Get checklist by machine | machineCode={}", machineCode);
        return machineChecklistService.getByMachineCode(machineCode);
    }

    @GetMapping("/general/{machineCode}")
    public Mono<ListResponse<List<MachineChWithQuestionDTO>>> getGeneralChecklist(
            @PathVariable String machineCode
    ) {
        log.debug("Get general checklist | machineCode={}", machineCode);
        return machineChecklistService.getGeneralChecklist(machineCode);
    }

    @GetMapping("/responsible/{machineCode}")
    public Mono<ListResponse<List<MachineChWithQuestionDTO>>> getResponsibleChecklist(
            @PathVariable String machineCode
    ) {
        log.debug("Get responsible checklist | machineCode={}", machineCode);
        return machineChecklistService.getResponsibleChecklist(machineCode);
    }

    @PatchMapping("/{id}/reset")
    public Mono<ApiResponse<Void>> resetChecklistStatus(@PathVariable Long id) {
        log.info("Reset checklist status | id={}", id);
        return machineChecklistService.resetChecklistStatus(id);
    }
}