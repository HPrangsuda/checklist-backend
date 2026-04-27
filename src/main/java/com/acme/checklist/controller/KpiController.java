package com.acme.checklist.controller;

import com.acme.checklist.entity.Kpi;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.kpi.KpiDTO;
import com.acme.checklist.payload.kpi.KpiResponseDTO;
import com.acme.checklist.service.KpiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

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
    public Mono<List<Kpi>> getAll(
            @RequestParam String years,
            @RequestParam String months) {
        return kpiService.getKpiByYearAndMonth(years, months);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<KpiResponseDTO>> getById(@PathVariable Long id) {
        return kpiService.getById(id);
    }
}