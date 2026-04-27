package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.question.QuestionDTO;
import com.acme.checklist.service.QuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/question")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @PostMapping("/create")
    public Mono<ApiResponse<Void>> create(@RequestBody QuestionDTO dto) {
        log.info("Create question | detail={}", dto.getDetail());
        return questionService.create(dto);
    }

    @PutMapping("/update")
    public Mono<ApiResponse<Void>> update(@RequestBody QuestionDTO dto) {
        log.info("Update question | id={}", dto.getId());
        return questionService.update(dto);
    }

    @DeleteMapping("/delete")
    public Mono<ApiResponse<Void>> delete(@RequestParam List<Long> ids) {
        log.info("Delete questions | ids={}", ids);
        return questionService.delete(ids);
    }

    @GetMapping("/get/page")
    public Mono<PagedResponse<QuestionDTO>> getAllWithPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.debug("Get questions paged | keyword={}, index={}, size={}", keyword, index, size);
        return questionService.getAllWithPage(keyword, index, size);
    }

    @GetMapping("/{id}")
    public Mono<QuestionDTO> getById(@PathVariable Long id) {
        log.debug("Get question by id | id={}", id);
        return questionService.getById(id);
    }
}