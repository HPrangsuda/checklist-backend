package com.acme.checklist.service;

import com.acme.checklist.entity.Question;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.question.QuestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    // =========================
    // CREATE
    // =========================
    public Mono<ApiResponse<Void>> create(QuestionDTO dto) {
        return validateData(dto, null)
                .flatMap(validated -> {
                    Question question = buildFromDTO(validated);
                    return commonService.save(question, Question.class)
                            .thenReturn(ApiResponse.<Void>success("QB001"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to create question", e);
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    if (e instanceof ThrowException te) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, te.getCode()));
                    }
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, msg));
                });
    }

    // =========================
    // UPDATE
    // =========================
    public Mono<ApiResponse<Void>> update(QuestionDTO dto) {
        if (dto.getId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "QB005"));
        }
        return validateData(dto, dto.getId())
                .flatMap(validated -> {
                    Update update = buildUpdateFromDTO(validated);
                    return commonService.update(dto.getId(), update, Question.class)
                            .thenReturn(ApiResponse.<Void>success("QB002"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to update question", e);
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    if (e instanceof ThrowException te) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, te.getCode()));
                    }
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, msg));
                });
    }

    // =========================
    // DELETE
    // =========================
    public Mono<ApiResponse<Void>> delete(List<Long> ids) {
        return commonService.deleteEntitiesByIds(
                ids,
                Question.class,
                "QB006",
                "QB007",
                "QB008",
                Question::getDetail,
                names -> Mono.empty()
        );
    }

    // =========================
    // GET ALL (PAGED)
    // =========================
    public Mono<PagedResponse<QuestionDTO>> getAllWithPage(String keyword, int index, int size) {
        Criteria criteria = Criteria.empty();
        if (StringUtils.hasText(keyword)) {
            criteria = Criteria.where("detail").like("%" + keyword + "%").ignoreCase(true)
                    .or("description").like("%" + keyword + "%").ignoreCase(true);
        }
        Query query = Query.query(criteria)
                .with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(
                index, size, query, criteria,
                Question.class, list -> reactor.core.publisher.Flux.fromIterable(list).map(this::toDTO)
        );
    }

    // =========================
    // GET BY ID
    // =========================
    public Mono<QuestionDTO> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), Question.class)
                .map(this::toDTO)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "QB009")));
    }

    // =========================
    // VALIDATION
    // =========================
    private Mono<QuestionDTO> validateData(QuestionDTO dto, Long excludeId) {
        if (!StringUtils.hasText(dto.getDetail())) {
            return Mono.error(new ThrowException("QB003")); // detail required
        }

        // เช็ค detail ซ้ำ (ยกเว้น id ตัวเอง ตอน update)
        Criteria criteria = Criteria.where("detail").is(dto.getDetail().trim());
        if (excludeId != null) {
            criteria = criteria.and("id").not(excludeId);
        }

        return template.select(Query.query(criteria).limit(1), Question.class)
                .hasElements()
                .flatMap(exists -> {
                    if (exists) return Mono.error(new ThrowException("QB004")); // detail duplicate
                    return Mono.just(dto);
                });
    }

    // =========================
    // MAPPER
    // =========================
    private Question buildFromDTO(QuestionDTO dto) {
        return Question.builder()
                .detail(dto.getDetail().trim())
                .description(dto.getDescription())
                .build();
    }

    private Update buildUpdateFromDTO(QuestionDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "detail",      dto.getDetail());
        addIfNotNull(params, "description", dto.getDescription());
        return Update.from(params);
    }

    private QuestionDTO toDTO(Question q) {
        QuestionDTO dto = new QuestionDTO();
        dto.setId(q.getId());
        dto.setDetail(q.getDetail());
        dto.setDescription(q.getDescription());
        return dto;
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }
}