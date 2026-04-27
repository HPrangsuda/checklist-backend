package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.audit.MemberListDTO;
import com.acme.checklist.payload.member.MemberDTO;
import com.acme.checklist.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/create")
    public Mono<ApiResponse<Void>> create(@RequestBody MemberDTO dto) {
        log.info("Create member | employeeId={}, userName={}", dto.getEmployeeId(), dto.getUserName());
        return memberService.create(dto);
    }

    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody MemberDTO dto) {
        log.info("Update member | id={}", dto.getId());
        return memberService.update(dto);
    }

    @DeleteMapping("/delete")
    public Mono<ApiResponse<Void>> delete(@RequestParam List<Long> ids) {
        log.info("Delete members | ids={}", ids);
        return memberService.delete(ids);
    }

    @GetMapping("/get/page")
    public Mono<PagedResponse<MemberListDTO>> getAllWithPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.debug("Get members paged | keyword={}, index={}, size={}", keyword, index, size);
        return memberService.getAllWithPage(keyword, index, size);
    }

    @GetMapping("/get/list")
    public Mono<ListResponse<List<MemberListDTO>>> getList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.debug("Get members list | keyword={}, ids={}", keyword, ids);
        return memberService.getList(keyword, ids, index, size);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<MemberDTO>> getById(@PathVariable Long id) {
        log.debug("Get member by id | id={}", id);
        return memberService.getById(id);
    }
}