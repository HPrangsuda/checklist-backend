package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.checklist.ChecklistDTO;
import com.acme.checklist.payload.checklist.ChecklistListDTO;
import com.acme.checklist.payload.checklist.ChecklistResponseDTO;
import com.acme.checklist.payload.checklist.ChecklistStatsDTO;
import com.acme.checklist.service.ChecklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/checklist")
@RequiredArgsConstructor
public class ChecklistController {
    private final ChecklistService checklistService;

    @PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<Void>> create(
            @RequestPart("request") String requestJson,
            @RequestPart(value = "file", required = false) FilePart file) {
        return checklistService.create(requestJson, file);
    }

    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody ChecklistDTO dto) {
        return checklistService.update(dto);
    }

    @DeleteMapping("/delete")
    public Mono<ApiResponse<Void>> delete(@RequestParam List<Long> ids) {
        return checklistService.delete(ids);
    }

    @GetMapping("/get/page")
    public Mono<PagedResponse<ChecklistListDTO>> getAllWithPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size) {
        return checklistService.getAllWithPage(keyword, index, size);
    }

    @GetMapping("/get/personal/{id}")
    public Mono<PagedResponse<ChecklistListDTO>> getPersonalWithPage(
            @PathVariable Long id,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size) {
        return checklistService.getPersonalWithPage(id, keyword, index, size);
    }

    @GetMapping("/get/role")
    public Mono<PagedResponse<ChecklistListDTO>> getWithRole(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size) {
        return checklistService.getWithRole(keyword, index, size);
    }

    @GetMapping("/pending")
    public Mono<ListResponse<List<ChecklistListDTO>>> getPendingApprovals() {
        return checklistService.getPendingApprovals();
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<ChecklistResponseDTO>> getById(@PathVariable Long id) {
        return checklistService.getById(id);
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<List<ChecklistStatsDTO>>> getChecklistStats(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String department) {
        return checklistService.getChecklistStats(year, department)
                .map(data -> ApiResponse.success("MS020", data))
                .onErrorResume(e -> {
                    log.error("Failed to fetch checklist stats: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS021", e.getMessage()));
                });
    }
}
