package com.acme.checklist.service;

import com.acme.checklist.entity.*;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.audit.MemberListDTO;
import com.acme.checklist.payload.member.MemberDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;
    private final PasswordEncoder passwordEncoder;

    // =========================
    // CREATE
    // =========================
    public Mono<ApiResponse<Void>> create(MemberDTO dto) {
        return validateData(dto)
                .flatMap(validatedDTO -> {
                    Member member = buildFromDTO(validatedDTO);
                    return commonService.save(member, Member.class)
                            .thenReturn(ApiResponse.<Void>success("MB001"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to create member", e);
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
    public Mono<ApiResponse<Void>> update(MemberDTO dto) {
        if (dto.getId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "MB016"));
        }
        return validateDataForUpdate(dto)
                .flatMap(validatedDTO -> {
                    Update update = buildUpdateFromDTO(validatedDTO);
                    return commonService.update(dto.getId(), update, Member.class)
                            .thenReturn(ApiResponse.<Void>success("MB017"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to update member", e);
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
                Member.class,
                "MB019",
                "MB020",
                "MB021",
                member -> member.getFirstName() + " " + member.getLastName(),
                names -> Mono.empty()
        );
    }

    // =========================
    // GET ALL (PAGED)
    // =========================
    public Mono<PagedResponse<MemberListDTO>> getAllWithPage(String keyword, int index, int size) {
        Criteria criteria = Criteria.empty();
        if (StringUtils.hasText(keyword)) {
            criteria = Criteria.where("employee_id").like("%" + keyword + "%").ignoreCase(true)
                    .or("first_name").like("%" + keyword + "%").ignoreCase(true)
                    .or("last_name").like("%" + keyword + "%").ignoreCase(true);
        }
        Query query = Query.query(criteria)
                .with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(
                index, size, query, criteria,
                Member.class, this::convertMemberListDTOs
        );
    }

    // =========================
    // GET LIST
    // =========================
    public Mono<ListResponse<List<MemberListDTO>>> getList(
            String keyword, List<Long> ids, int index, int size
    ) {
        Pageable pageable = PageRequest.of(index, size, Sort.by(Sort.Direction.DESC, "id"));
        boolean hasIds = ids != null && !ids.isEmpty();

        return commonService.getSelectedItems(hasIds, ids, index, size, Member.class)
                .flatMap(selectedItems -> {
                    Criteria criteria = Criteria.empty();
                    if (StringUtils.hasText(keyword) && hasIds) {
                        criteria = Criteria
                                .where("first_name").like("%" + keyword + "%").ignoreCase(true)
                                .and("id").notIn(ids);
                    } else if (hasIds) {
                        criteria = Criteria.where("id").notIn(ids);
                    }
                    return commonService.getPagedList(
                            index, size, criteria, selectedItems,
                            pageable, Member.class, this::convertMemberListDTOs
                    );
                });
    }

    // =========================
    // GET BY ID
    // =========================
    public Mono<ApiResponse<MemberDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), Member.class)
                .map(member -> ApiResponse.success("MB013", toDTO(member)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "MB014")))
                .onErrorResume(e -> {
                    log.error("Failed to get member by id", e);
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, msg));
                });
    }

    // =========================
    // VALIDATION — CREATE
    // =========================
    private Mono<MemberDTO> validateData(MemberDTO dto) {
        if (!StringUtils.hasText(dto.getEmployeeId()))
            return Mono.error(new ThrowException("MB003"));
        if (!StringUtils.hasText(dto.getFirstName()))
            return Mono.error(new ThrowException("MB004"));
        if (!StringUtils.hasText(dto.getLastName()))
            return Mono.error(new ThrowException("MB005"));
        if (!StringUtils.hasText(dto.getMobiles()))
            return Mono.error(new ThrowException("MB006"));
        if (!StringUtils.hasText(dto.getUserName()))
            return Mono.error(new ThrowException("MB007"));
        if (!StringUtils.hasText(dto.getPassword()))
            return Mono.error(new ThrowException("MB008"));
        if (dto.getRoleType() == null)
            return Mono.error(new ThrowException("MB009"));

        Criteria duplicateCriteria = Criteria
                .where("employee_id").is(dto.getEmployeeId())
                .or("user_name").is(dto.getUserName());

        if (StringUtils.hasText(dto.getEmail())) {
            duplicateCriteria = duplicateCriteria.or("email").is(dto.getEmail());
        }

        return template.select(Query.query(duplicateCriteria), Member.class)
                .collectList()
                .flatMap(existing -> {
                    if (!existing.isEmpty()) {
                        Member found = existing.get(0);
                        if (dto.getEmployeeId().equals(found.getEmployeeId()))
                            return Mono.error(new ThrowException("MB010"));
                        if (dto.getUserName().equals(found.getUserName()))
                            return Mono.error(new ThrowException("MB011"));
                        return Mono.error(new ThrowException("MB012"));
                    }
                    return Mono.just(dto);
                });
    }

    // =========================
    // VALIDATION — UPDATE
    // =========================
    private Mono<MemberDTO> validateDataForUpdate(MemberDTO dto) {
        if (!StringUtils.hasText(dto.getFirstName()))
            return Mono.error(new ThrowException("MB004"));
        if (!StringUtils.hasText(dto.getLastName()))
            return Mono.error(new ThrowException("MB005"));
        if (!StringUtils.hasText(dto.getMobiles()))
            return Mono.error(new ThrowException("MB006"));
        if (dto.getRoleType() == null)
            return Mono.error(new ThrowException("MB009"));

        // ไม่มีอะไรต้องเช็ค duplicate
        boolean hasUserName = StringUtils.hasText(dto.getUserName());
        boolean hasEmail    = StringUtils.hasText(dto.getEmail());
        if (!hasUserName && !hasEmail) {
            return Mono.just(dto);
        }

        // เช็ค userName และ email ซ้ำกับคนอื่น (ยกเว้น id ตัวเอง)
        // employeeId ไม่อนุญาตให้เปลี่ยนจาก update → ไม่ต้องเช็ค
        Criteria criteria = Criteria.where("id").not(dto.getId());

        if (hasUserName && hasEmail) {
            criteria = criteria.and(
                    Criteria.where("user_name").is(dto.getUserName())
                            .or("email").is(dto.getEmail())
            );
        } else if (hasUserName) {
            criteria = criteria.and("user_name").is(dto.getUserName());
        } else {
            criteria = criteria.and("email").is(dto.getEmail());
        }

        return template.select(Query.query(criteria), Member.class)
                .collectList()
                .flatMap(existing -> {
                    if (!existing.isEmpty()) {
                        Member found = existing.get(0);
                        if (hasEmail && dto.getEmail().equals(found.getEmail()))
                            return Mono.error(new ThrowException("MB012")); // email duplicate
                        return Mono.error(new ThrowException("MB011"));     // username duplicate
                    }
                    return Mono.just(dto);
                });
    }

    // =========================
    // MAPPER
    // =========================
    private Member buildFromDTO(MemberDTO dto) {
        String avatarKey = StringUtils.hasText(dto.getFirstName())
                ? String.valueOf(dto.getFirstName().charAt(0)).toUpperCase()
                : null;

        Member member = new Member();
        member.setEmployeeId(dto.getEmployeeId());
        member.setDepartmentId(dto.getDepartmentId());
        member.setFirstName(dto.getFirstName());
        member.setLastName(dto.getLastName());
        member.setAvatarKey(avatarKey);
        member.setEmail(dto.getEmail());
        member.setMobiles(dto.getMobiles());
        member.setUserName(dto.getUserName());
        member.setPassword(passwordEncoder.encode(dto.getPassword()));
        member.setRoleType(dto.getRoleType());
        member.setLanguages(dto.getLanguages());
        return member;
    }

    private Update buildUpdateFromDTO(MemberDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "employee_id",   dto.getEmployeeId());
        addIfNotNull(params, "user_name",     dto.getUserName());
        addIfNotNull(params, "first_name",    dto.getFirstName());
        addIfNotNull(params, "last_name",     dto.getLastName());
        addIfNotNull(params, "email",         dto.getEmail());
        addIfNotNull(params, "mobiles",       dto.getMobiles());
        addIfNotNull(params, "department_id", dto.getDepartmentId());
        addIfNotNull(params, "role_type",     dto.getRoleType());
        addIfNotNull(params, "languages",     dto.getLanguages());
        if (StringUtils.hasText(dto.getPassword())) {
            params.put(SqlIdentifier.quoted("password"), passwordEncoder.encode(dto.getPassword()));
        }
        if (StringUtils.hasText(dto.getFirstName())) {
            params.put(SqlIdentifier.quoted("avatar_key"),
                    String.valueOf(dto.getFirstName().charAt(0)).toUpperCase());
        }
        return Update.from(params);
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }

    private MemberDTO toDTO(Member member) {
        MemberDTO dto = new MemberDTO();
        dto.setId(member.getId());
        dto.setEmployeeId(member.getEmployeeId());
        dto.setDepartmentId(member.getDepartmentId());
        dto.setFirstName(member.getFirstName());
        dto.setLastName(member.getLastName());
        dto.setAvatarKey(member.getAvatarKey());
        dto.setEmail(member.getEmail());
        dto.setMobiles(member.getMobiles());
        dto.setUserName(member.getUserName());
        dto.setRoleType(member.getRoleType());
        dto.setLanguages(member.getLanguages());
        return dto;
    }

    private Flux<MemberListDTO> convertMemberListDTOs(List<Member> members) {
        return Flux.fromIterable(members).map(MemberListDTO::from);
    }
}