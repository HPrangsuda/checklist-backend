package com.acme.checklist.service;

import com.acme.checklist.entity.MachineChecklist;
import com.acme.checklist.entity.Member;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.machineChecklist.MachineChDTO;
import com.acme.checklist.payload.machineChecklist.MachineChResponseDTO;
import com.acme.checklist.payload.machineChecklist.MachineChWithQuestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MachineChService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    // =========================
    // CREATE
    // =========================
    public Mono<ApiResponse<Void>> create(MachineChDTO dto) {
        return validateData(dto)
                .flatMap(validated -> {
                    MachineChecklist entity = buildFromDTO(validated);
                    return commonService.save(entity, MachineChecklist.class)
                            .thenReturn(ApiResponse.<Void>success("MC001"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to create checklist", e);
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
    public Mono<ApiResponse<Void>> update(MachineChDTO dto) {
        if (dto.getId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "MC005"));
        }
        return validateData(dto)
                .flatMap(validated -> {
                    Update update = buildUpdateFromDTO(validated);
                    return commonService.update(dto.getId(), update, MachineChecklist.class)
                            .thenReturn(ApiResponse.<Void>success("MC002"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to update checklist", e);
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
                MachineChecklist.class,
                "MC006",
                "MC007",
                "MC008",
                MachineChecklist::getMachineCode,
                names -> Mono.empty()
        );
    }

    // =========================
    // GET ALL (PAGED)
    // =========================
    public Mono<PagedResponse<MachineChResponseDTO>> getAllWithPage(String keyword, int index, int size) {
        Criteria criteria = Criteria.empty();
        if (StringUtils.hasText(keyword)) {
            criteria = Criteria.where("machine_code").like("%" + keyword + "%").ignoreCase(true);
        }
        Query query = Query.query(criteria)
                .with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(
                index, size, query, criteria,
                MachineChecklist.class, this::convertMachineChListDTOs
        );
    }

    // =========================
    // GET BY MACHINE CODE (with questions joined)
    // =========================
    public Mono<ListResponse<List<MachineChWithQuestionDTO>>> getByMachineCode(String machineCode) {
        Query query = Query.query(Criteria.where("machine_code").is(machineCode))
                .sort(Sort.by(Sort.Direction.ASC, "id"));

        return template.select(query, MachineChecklist.class)
                .collectList()
                .flatMap(this::enrichWithQuestions)
                .map(items -> ListResponse.success("MC009", false, items))
                .onErrorResume(e -> {
                    log.error("Failed to fetch checklist for machineCode={}", machineCode, e);
                    return Mono.error(e);
                });
    }

    // =========================
    // GET GENERAL CHECKLIST
    // =========================
    public Mono<ListResponse<List<MachineChWithQuestionDTO>>> getGeneralChecklist(String machineCode) {
        Query query = Query.query(
                        Criteria.where("machine_code").is(machineCode)
                                .and("reset_time").is("0 0 0 * * 1"))
                .sort(Sort.by(Sort.Direction.ASC, "id"));

        return template.select(query, MachineChecklist.class)
                .collectList()
                .flatMap(this::enrichWithQuestions)
                .map(items -> ListResponse.success("MC009", false, items))
                .onErrorResume(e -> {
                    log.error("Failed to fetch general checklist for machineCode={}", machineCode, e);
                    return Mono.error(e);
                });
    }

    // =========================
    // GET RESPONSIBLE CHECKLIST
    // =========================
    public Mono<ListResponse<List<MachineChWithQuestionDTO>>> getResponsibleChecklist(String machineCode) {
        Query query = Query.query(
                        Criteria.where("machine_code").is(machineCode)
                                .and("check_status").is(false))
                .sort(Sort.by(Sort.Direction.ASC, "id"));

        return template.select(query, MachineChecklist.class)
                .collectList()
                .flatMap(this::enrichWithQuestions)
                .map(items -> ListResponse.success("MC009", false, items))
                .onErrorResume(e -> {
                    log.error("Failed to fetch responsible checklist for machineCode={}", machineCode, e);
                    return Mono.error(e);
                });
    }

    // =========================
    // RESET STATUS
    // =========================
    public Mono<ApiResponse<Void>> resetChecklistStatus(Long id) {
        return template.update(
                        Query.query(Criteria.where("id").is(id)),
                        Update.update("check_status", false),
                        MachineChecklist.class)
                .flatMap(count -> {
                    if (count == 0) {
                        return Mono.just(ApiResponse.<Void>error("MC010", "Checklist item not found"));
                    }
                    return Mono.just(ApiResponse.<Void>success("MC011"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to reset checklist status for id={}", id, e);
                    return Mono.just(ApiResponse.error("MC012", e.getMessage()));
                });
    }

    // =========================
    // VALIDATION
    // =========================
    public Mono<MachineChDTO> validateData(MachineChDTO dto) {
        if (!StringUtils.hasText(dto.getMachineCode())) {
            return Mono.error(new ThrowException("MC003")); // machineCode required
        }
        if (dto.getQuestionId() == null) {
            return Mono.error(new ThrowException("MC004")); // questionId required
        }
        if (dto.getIsChoice() == null) {
            return Mono.error(new ThrowException("MC013")); // isChoice required
        }
        if (!StringUtils.hasText(dto.getResetTime())) {
            return Mono.error(new ThrowException("MC014")); // resetTime required
        }
        return Mono.just(dto);
    }

    // =========================
    // MAPPER
    // =========================
    public MachineChecklist buildFromDTO(MachineChDTO dto) {
        // ไม่เซ็ต id เพื่อให้ DB sequence จัดการ (create)
        return MachineChecklist.builder()
                .machineCode(dto.getMachineCode())
                .questionId(dto.getQuestionId())
                .isChoice(dto.getIsChoice())
                .checkStatus(dto.getCheckStatus())
                .resetTime(dto.getResetTime())
                .build();
    }

    private Update buildUpdateFromDTO(MachineChDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "check_status", dto.getCheckStatus());
        addIfNotNull(params, "machine_code", dto.getMachineCode());
        addIfNotNull(params, "question_id",  dto.getQuestionId());
        addIfNotNull(params, "reset_time",   dto.getResetTime());
        addIfNotNull(params, "is_choice",    dto.getIsChoice());
        return Update.from(params);
    }

    // =========================
    // ENRICH WITH QUESTIONS
    // =========================
    private Mono<List<MachineChWithQuestionDTO>> enrichWithQuestions(List<MachineChecklist> items) {
        if (items.isEmpty()) return Mono.just(List.of());

        List<Long> questionIds = items.stream()
                .map(MachineChecklist::getQuestionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (questionIds.isEmpty()) {
            return Mono.just(MachineChWithQuestionDTO.fromList(items, Map.of()));
        }

        return template.select(
                        Query.query(Criteria.where("id").in(questionIds)),
                        com.acme.checklist.entity.Question.class)
                .collectList()
                .map(questions -> {
                    Map<Long, com.acme.checklist.entity.Question> qMap = questions.stream()
                            .collect(Collectors.toMap(
                                    com.acme.checklist.entity.Question::getId, q -> q));
                    return MachineChWithQuestionDTO.fromList(items, qMap);
                });
    }

    // =========================
    // CONVERT FOR PAGED
    // =========================
    private Flux<MachineChResponseDTO> convertMachineChListDTOs(List<MachineChecklist> list) {
        if (list.isEmpty()) return Flux.empty();

        List<Long> createdByIds = list.stream()
                .map(MachineChecklist::getCreatedBy)
                .filter(Objects::nonNull).distinct().toList();
        List<Long> updatedByIds = list.stream()
                .map(MachineChecklist::getUpdatedBy)
                .filter(Objects::nonNull).distinct().toList();

        return Mono.zip(
                        commonService.fetchMembersByIds(createdByIds),
                        commonService.fetchMembersByIds(updatedByIds))
                .flatMapMany(tuple -> {
                    Map<Long, Member> createdByMap = tuple.getT1();
                    Map<Long, Member> updatedByMap = tuple.getT2();
                    return Flux.fromIterable(list)
                            .map(c -> MachineChResponseDTO.from(
                                    c,
                                    AuditMemberDTO.from(createdByMap.get(c.getCreatedBy())),
                                    AuditMemberDTO.from(updatedByMap.get(c.getUpdatedBy()))
                            ));
                });
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String field, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(field), value);
    }
}