package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.register.RegisterDTO;
import com.acme.checklist.payload.register.RegisterListDTO;
import com.acme.checklist.payload.register.RegisterResponseDTO;
import com.acme.checklist.service.RegisterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/register")
@RequiredArgsConstructor
public class RegisterController {
    private final RegisterService registerService;

    @PostMapping("/create")
    public Mono<ApiResponse<Void>> create(@RequestBody RegisterDTO dto) {
        log.info("Register request: {}", dto);
        return registerService.create(dto);
    }

    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody RegisterDTO dto) {
        return registerService.update(dto);
    }

    @DeleteMapping("/delete")
    public Mono<ApiResponse<Void>> delete(@RequestParam List<Long> ids) {
        return registerService.delete(ids);
    }

    @GetMapping("/get/page")
    public Mono<PagedResponse<RegisterListDTO>> getPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")  int index,
            @RequestParam(defaultValue = "10") int size) {
        return registerService.getWithRole(keyword, index, size);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<RegisterResponseDTO>> getById(@PathVariable Long id) {
        return registerService.getById(id);
    }
}