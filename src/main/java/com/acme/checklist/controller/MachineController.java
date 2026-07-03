package com.acme.checklist.controller;

import com.acme.checklist.entity.Machine;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.machine.FilterOptionsDTO;
import com.acme.checklist.payload.machine.MachineDTO;
import com.acme.checklist.payload.machine.MachineListDTO;
import com.acme.checklist.payload.machine.MachineResponseDTO;
import com.acme.checklist.payload.machine.MachineSummaryDTO;
import com.acme.checklist.payload.machine.ChangeResponsiblePersonRequest;
import com.acme.checklist.service.LarkService;
import com.acme.checklist.service.MachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/machine")
@RequiredArgsConstructor
public class MachineController {

    private final MachineService machineService;
    private final LarkService larkService;

    // FIX: เปลี่ยน Void → Map<String, Object> ให้ตรงกับ MachineService.create()
    @PostMapping("/create")
    public Mono<ApiResponse<Map<String, Object>>> create(@RequestBody MachineDTO dto) {
        return machineService.create(dto);
    }

    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody MachineDTO dto) {
        return machineService.update(dto);
    }

    @DeleteMapping("/delete")
    public Mono<ApiResponse<Void>> delete(@RequestParam List<Long> ids) {
        return machineService.delete(ids);
    }

    @GetMapping("/get/page")
    public Mono<PagedResponse<MachineListDTO>> getByRole(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) String checkStatus,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String machineStatus,
            @RequestParam(required = false) String responsiblePersonName) {
        return machineService.getByRole(
                keyword, index, size, mine,
                checkStatus, department, machineStatus, responsiblePersonName);
    }

    @GetMapping("/get/list")
    public Mono<ListResponse<List<MachineListDTO>>> getList(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "ids",     required = false) List<Long> ids,
            @RequestParam(defaultValue = "0")  int index,
            @RequestParam(defaultValue = "10") int size) {
        return machineService.getList(keyword, ids, index, size);
    }

    @GetMapping("/filter-options")
    public Mono<ApiResponse<FilterOptionsDTO>> getFilterOptions() {
        return machineService.getFilterOptions();
    }

    @GetMapping("/machine-code/{machineCode}")
    public Mono<ApiResponse<Machine>> getByMachineCode(@PathVariable String machineCode) {
        return machineService.getByMachineCode(machineCode);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<MachineResponseDTO>> getById(@PathVariable Long id) {
        return machineService.getById(id);
    }

    @GetMapping("/department-summary")
    public Flux<MachineSummaryDTO> getDepartmentSummary() {
        return machineService.getDepartmentSummaryWithRole();
    }

    @PatchMapping("/{machineCode}/responsible-person")
    public Mono<ApiResponse<Void>> changeResponsiblePerson(
            @PathVariable String machineCode,
            @RequestBody ChangeResponsiblePersonRequest req) {
        return machineService.changeResponsiblePerson(machineCode, Long.valueOf(req.newPersonId()));
    }

    @PostMapping("/{id}/sync-to-lark")
    public Mono<ApiResponse<Void>> syncToLark(@PathVariable Long id) {
        return machineService.getMachineById(id)
                .flatMap(larkService::upsertMachineRecord)
                .then(Mono.just(ApiResponse.<Void>success("MS001")));
    }

    @PostMapping("/sync-all-to-lark")
    public Mono<ApiResponse<Void>> syncAllToLark() {
        return machineService.getAll()
                .delayElements(Duration.ofMillis(200))
                .flatMap(machine -> larkService.upsertMachineRecord(machine)
                        .onErrorResume(e -> {
                            log.error("Skip machine {} ({}): {}",
                                    machine.getMachineCode(),
                                    machine.getId(),
                                    e.getMessage());
                            return Mono.empty();
                        }))
                .then(Mono.just(ApiResponse.<Void>success("MS001")));
    }
}