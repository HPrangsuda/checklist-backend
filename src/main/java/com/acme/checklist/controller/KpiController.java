package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.kpi.KpiDTO;
import com.acme.checklist.payload.kpi.KpiResponseDTO;
import com.acme.checklist.service.KpiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/kpi")
@RequiredArgsConstructor
public class KpiController {
    private final KpiService kpiService;

    @PostMapping("/create")
    public Mono<ApiResponse<Void>> create(@RequestBody KpiDTO dto) {
        return kpiService.create(dto);
    }

    @GetMapping("/all")
    public Mono<PagedResponse<KpiResponseDTO>> getAll(
            @RequestParam String years,
            @RequestParam String months,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size) {
        return kpiService.getKpiByYearAndMonth(years, months, keyword, index, size);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<KpiResponseDTO>> getById(@PathVariable Long id) {
        return kpiService.getById(id);
    }
}