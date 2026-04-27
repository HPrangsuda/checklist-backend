package com.acme.checklist.service;

import com.acme.checklist.entity.MachineType;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.machineType.MachineTypeDTO;
import com.acme.checklist.payload.machineType.MachineTypeListDTO;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class MachineTypeService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    // =========================
    // CREATE
    // =========================
    public Mono<ApiResponse<Void>> create(MachineTypeDTO dto) {
        return validateCreate(dto)
                .flatMap(this::generateIds)
                .map(this::buildNewEntity)
                .flatMap(entity ->
                        commonService.save(entity, MachineType.class)
                                .thenReturn(ApiResponse.<Void>success("MT001"))
                )
                .onErrorResume(e -> {
                    log.error("Create machine type failed", e);
                    // ถ้าเป็น ResponseStatusException อยู่แล้ว ให้ผ่านต่อเลย
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    // validation error → 400 Bad Request พร้อม code
                    if (e instanceof ThrowException te) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, te.getCode()));
                    }
                    // unexpected error → 500
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, msg));
                });
    }

    // =========================
    // UPDATE
    // =========================
    public Mono<ApiResponse<Void>> update(MachineTypeDTO dto) {
        return validateUpdate(dto)
                .flatMap(valid ->
                        commonService.update(
                                dto.getId(),
                                buildUpdateFromDTO(valid),
                                MachineType.class
                        ).thenReturn(ApiResponse.<Void>success("MT008"))
                )
                .onErrorResume(e -> {
                    log.error("Update machine type failed", e);
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    if (e instanceof ThrowException te) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, te.getCode()));
                    }
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, msg));
                });
    }

    // =========================
    // VALIDATION — CREATE
    // =========================

    /**
     * Case 1 — NEW_GROUP (machineGroupId is blank):
     *   - machineGroupName must be provided
     *   - machineGroupName must NOT already exist
     *   - machineTypeName must be provided
     *
     * Case 2 — ADD_TYPE (machineGroupId is present):
     *   - machineGroupId must resolve to an existing group
     *   - machineTypeName must be provided
     *   - typeName must NOT already exist in that group
     */
    public Mono<MachineTypeDTO> validateCreate(MachineTypeDTO dto) {

        if (!StringUtils.hasText(dto.getMachineTypeName())) {
            return Mono.error(new ThrowException("MT004")); // type name required
        }

        // ─── Case 2: ADD_TYPE ───────────────────────────────────────────
        if (isValidGroupId(dto.getMachineGroupId())) {
            return findGroupById(dto.getMachineGroupId())
                    .switchIfEmpty(Mono.error(new ThrowException("MT006"))) // group not found
                    .flatMap(group -> {
                        // เอา groupName จาก DB มาเซ็ตทับ เพื่อให้ downstream ใช้ค่าที่ถูกต้อง
                        dto.setMachineGroupName(group.getMachineGroupName());

                        return checkTypeExistsInGroup(
                                group.getMachineGroupName(),
                                dto.getMachineTypeName(),
                                null
                        ).flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new ThrowException("MT005")); // type duplicate in group
                            }
                            return Mono.just(dto);
                        });
                    });
        }

        // ─── Case 1: NEW_GROUP ──────────────────────────────────────────
        if (!StringUtils.hasText(dto.getMachineGroupName())) {
            return Mono.error(new ThrowException("MT003")); // group name required
        }

        return checkGroupExistsByName(dto.getMachineGroupName())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new ThrowException("MT007")); // group name duplicate
                    }
                    return Mono.just(dto);
                });
    }

    // =========================
    // VALIDATION — UPDATE
    // =========================
    public Mono<MachineTypeDTO> validateUpdate(MachineTypeDTO dto) {

        if (dto.getId() == null || dto.getId() <= 0) {
            return Mono.error(new ThrowException("MT010")); // id required
        }

        if (!StringUtils.hasText(dto.getStatus())) {
            return Mono.error(new ThrowException("MT011")); // status required
        }

        return Mono.just(dto);
    }

    // =========================
    // ID GENERATOR
    // =========================
    private Mono<MachineTypeDTO> generateIds(MachineTypeDTO dto) {

        // Case 2: ADD_TYPE — group มีอยู่แล้ว หา typeId ถัดไปในกลุ่มนั้น
        if (isValidGroupId(dto.getMachineGroupId())) {
            return getNextTypeIdForGroup(dto.getMachineGroupId())
                    .map(nextTypeId -> {
                        dto.setMachineTypeId(nextTypeId);
                        return dto;
                    });
        }

        // Case 1: NEW_GROUP — สร้าง groupId ใหม่ + typeId = 01
        return getNextGroupId()
                .map(nextGroupId -> {
                    dto.setMachineGroupId(nextGroupId);
                    dto.setMachineTypeId("01");
                    return dto;
                });
    }

    // =========================
    // GROUP LOOKUP
    // =========================

    /** groupId ที่ valid ต้องมีค่า ไม่ใช่ "0" หรือ blank */
    private boolean isValidGroupId(String groupId) {
        return StringUtils.hasText(groupId) && !groupId.trim().equals("0");
    }

    /** หา row ใดก็ได้ที่มี groupId นี้ เพื่ออ่าน canonical groupName */
    private Mono<MachineType> findGroupById(String groupId) {
        Criteria criteria = Criteria.where("machine_group_id").is(groupId.trim());
        return template.select(Query.query(criteria).limit(1), MachineType.class).next();
    }

    /** เช็คว่า groupName มีอยู่แล้วหรือไม่ */
    private Mono<Boolean> checkGroupExistsByName(String groupName) {
        Criteria criteria = Criteria.where("machine_group_name").is(groupName.trim());
        return template.select(Query.query(criteria).limit(1), MachineType.class).hasElements();
    }

    /** เช็คว่า typeName ซ้ำในกลุ่มเดียวกันหรือไม่ (optionally ยกเว้น id ตัวเอง) */
    private Mono<Boolean> checkTypeExistsInGroup(String groupName, String typeName, Long excludeId) {
        Criteria criteria = Criteria
                .where("machine_group_name").is(groupName.trim())
                .and("machine_type_name").is(typeName.trim());

        if (excludeId != null && excludeId > 0) {
            criteria = criteria.and("id").not(excludeId);
        }

        return template.select(Query.query(criteria).limit(1), MachineType.class).hasElements();
    }

    // =========================
    // RUNNING ID
    // =========================
    private Mono<String> getNextGroupId() {
        String sql = """
                SELECT MAX(CAST(machine_group_id AS INTEGER)) AS max_id
                FROM machine_type
                WHERE machine_group_id ~ '^[0-9]+$'
                """;

        return template.getDatabaseClient()
                .sql(sql)
                .map((row, meta) -> row.get("max_id", Integer.class))
                .first()
                .defaultIfEmpty(0)
                .map(max -> String.format("%02d", (max == null ? 0 : max) + 1));
    }

    private Mono<String> getNextTypeIdForGroup(String groupId) {
        String sql = """
                SELECT MAX(CAST(machine_type_id AS INTEGER)) AS max_id
                FROM machine_type
                WHERE machine_group_id = :groupId
                  AND machine_type_id ~ '^[0-9]+$'
                """;

        return template.getDatabaseClient()
                .sql(sql)
                .bind("groupId", groupId)
                .map((row, meta) -> row.get("max_id", Integer.class))
                .first()
                .defaultIfEmpty(0)
                .map(max -> String.format("%02d", (max == null ? 0 : max) + 1));
    }

    // =========================
    // QUERY / LIST
    // =========================
    public Mono<PagedResponse<MachineTypeListDTO>> getAllWithPage(
            String keyword,
            List<String> groupIds,
            int index,
            int size
    ) {
        boolean hasKeyword  = StringUtils.hasText(keyword);
        boolean hasGroupIds = groupIds != null && !groupIds.isEmpty();

        Criteria criteria;

        // keyword criteria: ค้นทั้ง group_name และ type_name (OR)
        Criteria keywordCriteria = hasKeyword
                ? Criteria.where("machine_group_name").like("%" + keyword + "%").ignoreCase(true)
                .or("machine_type_name").like("%" + keyword + "%").ignoreCase(true)
                : null;

        // group filter criteria
        Criteria groupCriteria = hasGroupIds
                ? Criteria.where("machine_group_id").in(groupIds)
                : null;

        if (keywordCriteria != null && groupCriteria != null) {
            criteria = groupCriteria.and(keywordCriteria);
        } else if (keywordCriteria != null) {
            criteria = keywordCriteria;
        } else if (groupCriteria != null) {
            criteria = groupCriteria;
        } else {
            criteria = Criteria.empty();
        }

        Sort sort = Sort.by(Sort.Direction.ASC, "machine_group_id")
                .and(Sort.by(Sort.Direction.ASC, "machine_type_id"));

        Query query = Query.query(criteria)
                .with(PageRequest.of(index, size, sort));

        return commonService.executePagedQuery(
                index, size, query, criteria,
                MachineType.class, this::convertMachineTypeListDTOs
        );
    }

    public Mono<MachineTypeListDTO> getById(Long id) {
        return commonService.getById(id, MachineType.class)
                .map(MachineTypeListDTO::from);
    }

    public Mono<ListResponse<List<MachineTypeListDTO>>> getList(
            String keyword,
            List<Long> ids,
            int index,
            int size
    ) {
        Pageable pageable = PageRequest.of(index, size, Sort.by(Sort.Direction.ASC, "id"));
        boolean hasIds = ids != null && !ids.isEmpty();

        return commonService.getSelectedItems(hasIds, ids, index, size, MachineType.class)
                .flatMap(selectedItems -> {
                    Criteria criteria = Criteria.empty();

                    if (StringUtils.hasText(keyword) && hasIds) {
                        criteria = Criteria
                                .where("machine_type_name").like("%" + keyword + "%").ignoreCase(true)
                                .or("machine_group_name").like("%" + keyword + "%").ignoreCase(true)
                                .and("id").notIn(ids);
                    } else if (StringUtils.hasText(keyword)) {
                        criteria = Criteria
                                .where("machine_type_name").like("%" + keyword + "%").ignoreCase(true)
                                .or("machine_group_name").like("%" + keyword + "%").ignoreCase(true);
                    } else if (hasIds) {
                        criteria = Criteria.where("id").notIn(ids);
                    }

                    return commonService.getPagedList(
                            index, size, criteria, selectedItems,
                            pageable, MachineType.class, this::convertMachineTypeListDTOs
                    );
                });
    }

    public Mono<List<MachineTypeListDTO>> getDistinctMachineGroups(String keyword) {
        log.debug("Fetching distinct machine groups | keyword={}", keyword);

        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT machine_group_id, machine_group_name
                FROM machine_type
                WHERE machine_group_id IS NOT NULL
                  AND machine_group_name IS NOT NULL
                  AND machine_group_name <> ''
                  AND status = 'ACTIVE'
                """);

        if (StringUtils.hasText(keyword)) {
            sql.append("AND LOWER(machine_group_name) LIKE LOWER(:keyword) ");
        }

        sql.append("ORDER BY machine_group_id");

        var query = template.getDatabaseClient().sql(sql.toString());

        if (StringUtils.hasText(keyword)) {
            query = query.bind("keyword", "%" + keyword + "%");
        }

        return query
                .map((row, meta) -> {
                    String groupId   = row.get("machine_group_id",   String.class);
                    String groupName = row.get("machine_group_name", String.class);
                    if (!StringUtils.hasText(groupId) || !StringUtils.hasText(groupName)) return null;
                    MachineTypeListDTO dto = new MachineTypeListDTO();
                    dto.setMachineGroupId(groupId);
                    dto.setMachineGroupName(groupName);
                    return dto;
                })
                .all()
                .filter(Objects::nonNull)
                .collectList();
    }

    public Mono<ListResponse<List<MachineTypeListDTO>>> getMachineTypesByGroupId(
            String machineGroupId,
            String keyword,
            List<Long> ids,
            int index,
            int size
    ) {
        log.debug("Fetching machine types | groupId={}, keyword={}", machineGroupId, keyword);

        if (!StringUtils.hasText(machineGroupId)) {
            return Mono.just(ListResponse.<List<MachineTypeListDTO>>builder()
                    .success(true)
                    .data(List.of())
                    .hasMore(false)
                    .build());
        }

        Pageable pageable = PageRequest.of(index, size, Sort.by(Sort.Direction.ASC, "machine_type_id"));
        boolean hasIds = ids != null && !ids.isEmpty();

        return commonService.getSelectedItems(hasIds, ids, index, size, MachineType.class)
                .flatMap(selectedItems -> {
                    Criteria criteria = Criteria
                            .where("machine_group_id").is(machineGroupId)
                            .and("status").is("ACTIVE");

                    if (StringUtils.hasText(keyword)) {
                        criteria = criteria.and("machine_type_name")
                                .like("%" + keyword + "%")
                                .ignoreCase(true);
                    }

                    if (hasIds) {
                        criteria = criteria.and("id").notIn(ids);
                    }

                    return commonService.getPagedList(
                            index, size, criteria, selectedItems,
                            pageable, MachineType.class, this::convertMachineTypeListDTOs
                    );
                });
    }

    // =========================
    // MAPPER
    // =========================

    /** CREATE — ไม่เซ็ต id ให้ DB sequence จัดการเอง (ป้องกัน duplicate key) */
    private MachineType buildNewEntity(MachineTypeDTO dto) {
        return MachineType.builder()
                .machineGroupId(dto.getMachineGroupId())
                .machineGroupName(dto.getMachineGroupName())
                .machineTypeId(dto.getMachineTypeId())
                .machineTypeName(dto.getMachineTypeName())
                .status(dto.getStatus())
                .build();
    }

    /** UPDATE / context ที่ต้องการ id */
    private MachineType buildFromDTO(MachineTypeDTO dto) {
        MachineType.MachineTypeBuilder builder = MachineType.builder()
                .machineGroupId(dto.getMachineGroupId())
                .machineGroupName(dto.getMachineGroupName())
                .machineTypeId(dto.getMachineTypeId())
                .machineTypeName(dto.getMachineTypeName())
                .status(dto.getStatus());

        if (dto.getId() != null && dto.getId() > 0) {
            builder.id(dto.getId());
        }

        return builder.build();
    }

    private Update buildUpdateFromDTO(MachineTypeDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "machine_group_name", dto.getMachineGroupName());
        addIfNotNull(params, "machine_type_name", dto.getMachineTypeName());
        addIfNotNull(params, "status", dto.getStatus());
        return Update.from(params);
    }

    private Flux<MachineTypeListDTO> convertMachineTypeListDTOs(List<MachineType> list) {
        return Flux.fromIterable(list).map(MachineTypeListDTO::from);
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String field, Object value) {
        if (value != null) {
            params.put(SqlIdentifier.quoted(field), value);
        }
    }
}