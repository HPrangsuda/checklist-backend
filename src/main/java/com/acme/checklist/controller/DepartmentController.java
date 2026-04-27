package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.department.DepartmentDTO;
import com.acme.checklist.payload.department.DepartmentListDTO;
import com.acme.checklist.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;


@Slf4j
@RestController
@RequestMapping("/api/department")
@RequiredArgsConstructor
public class DepartmentController {
    private final DepartmentService departmentService;

    @PostMapping("/create")
    public Mono<ApiResponse<Void>> create(@RequestBody DepartmentDTO dto) {
        return departmentService.create(dto);
    }

    @GetMapping("/get/list")
    public Mono<ListResponse<List<DepartmentListDTO>>> getList(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size) {

        return departmentService.getList(keyword, ids, index, size);
    }
}
