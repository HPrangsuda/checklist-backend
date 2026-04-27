package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.machineType.MachineTypeDTO;
import com.acme.checklist.payload.machineType.MachineTypeListDTO;
import com.acme.checklist.service.MachineTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/type")
@RequiredArgsConstructor
@Slf4j
public class MachineTypeController {

    private final MachineTypeService machineTypeService;

    // =========================
    // CREATE
    // =========================
    @PostMapping("/create")
    public Mono<ApiResponse<Void>> create(@RequestBody MachineTypeDTO dto) {
        log.info(
                "Create machine type | groupId={}, groupName={}, typeName={}",
                dto.getMachineGroupId(),
                dto.getMachineGroupName(),
                dto.getMachineTypeName()
        );
        return machineTypeService.create(dto);
    }

    // =========================
    // UPDATE
    // =========================
    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody MachineTypeDTO dto) {
        log.info("Update machine type | id={}", dto.getId());
        return machineTypeService.update(dto);
    }

    // =========================
    // PAGED LIST (ALL) — รองรับ filter by group
    // =========================
    @GetMapping("/list")
    public Mono<PagedResponse<MachineTypeListDTO>> getAllWithPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> groupIds,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.debug("Get all machine types | keyword={}, groupIds={}, index={}, size={}",
                keyword, groupIds, index, size);
        return machineTypeService.getAllWithPage(keyword, groupIds, index, size);
    }

    // =========================
    // LIST (SELECTED / EXCLUDE)
    // =========================
    @GetMapping("/get/list")
    public Mono<ListResponse<List<MachineTypeListDTO>>> getList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.debug("Get machine type list | keyword={}, ids={}", keyword, ids);
        return machineTypeService.getList(keyword, ids, index, size);
    }

    // =========================
    // DISTINCT GROUPS
    // =========================
    @GetMapping("/groups/distinct")
    public Mono<List<MachineTypeListDTO>> getDistinctGroups(
            @RequestParam(required = false) String keyword
    ) {
        log.debug("Get distinct machine groups | keyword={}", keyword);
        return machineTypeService.getDistinctMachineGroups(keyword);
    }

    // =========================
    // TYPES BY GROUP
    // =========================
    @GetMapping("/types/by-group")
    public Mono<ListResponse<List<MachineTypeListDTO>>> getMachineTypesByGroupId(
            @RequestParam String machineGroupId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.debug(
                "Get machine types by group | groupId={}, keyword={}, index={}, size={}",
                machineGroupId, keyword, index, size
        );
        return machineTypeService.getMachineTypesByGroupId(
                machineGroupId, keyword, ids, index, size
        );
    }

    // =========================
    // GET BY ID
    // =========================
    @GetMapping("/{id}")
    public Mono<MachineTypeListDTO> getById(@PathVariable Long id) {
        log.debug("Get machine type by id | id={}", id);
        return machineTypeService.getById(id);
    }
}