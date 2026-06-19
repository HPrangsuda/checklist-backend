package com.acme.checklist.service;

import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.MaintenanceRecord;
import com.acme.checklist.entity.Member;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.file.FileUploadDTO;
import com.acme.checklist.payload.maintenance.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> update(String requestJson, List<FilePart> files) {
        MaintenanceDTO dto;
        try {
            dto = objectMapper.readValue(requestJson, MaintenanceDTO.class);
        } catch (Exception e) {
            return Mono.just(ApiResponse.error("MS001", "Invalid request format"));
        }

        Mono<List<String>> fileNamesMono = (files != null && !files.isEmpty())
                ? Flux.fromIterable(files)
                .flatMap(f -> fileStorageService.uploadFile(f, "maintenance").map(FileUploadDTO::getFileName))
                .collectList()
                : Mono.just(List.of());

        MaintenanceDTO finalDto = dto;
        return fileNamesMono
                .flatMap(fileNames -> validateData(finalDto, true)
                        .flatMap(validated -> {
                            Update update = buildUpdateFromDTO(validated);
                            return commonService.update(finalDto.getId(), update, MaintenanceRecord.class)
                                    .then(Mono.just(ApiResponse.<Void>success("MS001")));
                        }))
                .onErrorResume(e -> {
                    log.error("Failed to update the maintenance: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS001", e.getMessage()));
                });
    }

    public Mono<PagedResponse<MaintenanceResponseDTO>> getPage(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();
                    boolean hasKw   = StringUtils.hasText(keyword);

                    String roleFragment = switch (role) {
                        case "ADMIN"      -> "";
                        case "MANAGER"    -> "AND m.manager_id    = :memberId";
                        case "SUPERVISOR" -> "AND m.supervisor_id = :memberId";
                        default           -> "AND (m.responsible_person_id = :memberId " +
                                " OR mr.responsible_maintenance = :memberId)";
                    };

                    String kwFragment = hasKw
                            ? "AND (mr.machine_code ILIKE :kw OR mr.machine_name ILIKE :kw)"
                            : "";

                    String where = "WHERE 1=1 " + roleFragment + " " + kwFragment;

                    String countSql = """
                            SELECT COUNT(*)
                            FROM maintenance_record mr
                            LEFT JOIN machine m ON m.machine_code = mr.machine_code
                            """ + where;

                    // ★ เพิ่ม JOIN department + ดึง d.department AS department_name
                    String dataSql = """
                            SELECT
                                mr.id,
                                mr.machine_code,
                                mr.machine_name,
                                mr.years,
                                mr.round,
                                mr.due_date,
                                mr.plan_date,
                                mr.start_date,
                                mr.actual_date,
                                mr.status,
                                mr.maintenance_by,
                                mr.responsible_maintenance,
                                mr.note,
                                mr.attachment,
                                mr.checklist_record_id,
                                m.responsible_person_name AS responsible_maintenance_name,
                                m.department              AS machine_department_code,
                                d.department              AS machine_department_name
                            FROM maintenance_record mr
                            LEFT JOIN machine m ON m.machine_code = mr.machine_code
                            LEFT JOIN department d ON d.department_code = m.department
                            """ + where + """

                            ORDER BY mr.due_date ASC NULLS LAST
                            LIMIT :size OFFSET :offset
                            """;

                    String kwValue = hasKw ? "%" + keyword.trim() + "%" : null;

                    DatabaseClient.GenericExecuteSpec countSpec = template.getDatabaseClient().sql(countSql);
                    DatabaseClient.GenericExecuteSpec dataSpec  = template.getDatabaseClient().sql(dataSql);

                    if (!"ADMIN".equals(role)) {
                        countSpec = countSpec.bind("memberId", memberId);
                        dataSpec  = dataSpec.bind("memberId", memberId);
                    }
                    if (hasKw) {
                        countSpec = countSpec.bind("kw", kwValue);
                        dataSpec  = dataSpec.bind("kw", kwValue);
                    }

                    dataSpec = dataSpec
                            .bind("size",   size)
                            .bind("offset", (long) index * size);

                    Mono<Long> countMono = countSpec
                            .map((row, meta) -> {
                                Object v = row.get(0);
                                return v instanceof Number n ? n.longValue() : 0L;
                            })
                            .one()
                            .defaultIfEmpty(0L);

                    Flux<MaintenanceResponseDTO> dataFlux = dataSpec
                            .map((row, meta) -> MaintenanceResponseDTO.builder()
                                    .id(row.get("id", Long.class))
                                    .machineCode(row.get("machine_code", String.class))
                                    .machineName(row.get("machine_name", String.class))
                                    .years(row.get("years", String.class))
                                    .round(row.get("round", Integer.class))
                                    .dueDate(row.get("due_date", LocalDate.class))
                                    .planDate(row.get("plan_date", LocalDate.class))
                                    .startDate(row.get("start_date", LocalDate.class))
                                    .actualDate(row.get("actual_date", LocalDate.class))
                                    .status(row.get("status", String.class))
                                    .maintenanceBy(row.get("maintenance_by", String.class))
                                    .responsibleMaintenance(row.get("responsible_maintenance", Long.class))
                                    .responsibleMaintenanceName(row.get("responsible_maintenance_name", String.class))
                                    .machineDepartmentCode(row.get("machine_department_code", String.class)) // ★
                                    .machineDepartmentName(row.get("machine_department_name", String.class)) // ★
                                    .note(row.get("note", String.class))
                                    .attachment(row.get("attachment", String.class))
                                    .checklistRecordId(row.get("checklist_record_id", Long.class))
                                    .build())
                            .all();

                    return Mono.zip(countMono, dataFlux.collectList())
                            .map(tuple -> {
                                long total      = tuple.getT1();
                                int  totalPages = (int) Math.ceil((double) total / size);
                                return PagedResponse.<MaintenanceResponseDTO>builder()
                                        .success(true)
                                        .message("Success")
                                        .data(tuple.getT2())
                                        .totalElements(total)
                                        .totalPages(totalPages)
                                        .index(index)
                                        .size(size)
                                        .build();
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance page: {}", e.getMessage(), e);
                    return Mono.just(PagedResponse.<MaintenanceResponseDTO>builder()
                            .success(false)
                            .message(e.getMessage())
                            .data(List.of())
                            .totalElements(0L)
                            .totalPages(0)
                            .index(index)
                            .size(size)
                            .build());
                });
    }

    // ─── DEPARTMENT SUMMARY WITH ROLE ─────────────────────────────────────────

    public Flux<MaintenanceDepartmentSummaryDTO> getDepartmentSummaryWithRole() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();

                    // ทุก column เป็น BIGINT → ใช้ memberId ทั้งหมด (ปลอดภัยจาก SQL Injection)
                    String roleFilter = switch (role) {
                        case "ADMIN"      -> "";
                        case "MANAGER"    -> "AND m.manager_id = " + memberId;
                        case "SUPERVISOR" -> "AND m.supervisor_id = " + memberId;
                        // machine.responsible_person_id (BIGINT) หรือ maintenance_record.responsible_maintenance (BIGINT)
                        default           -> "AND (m.responsible_person_id = " + memberId +
                                " OR mr.responsible_maintenance = " + memberId + ")";
                    };

                    return template.getDatabaseClient()
                            .sql(buildDepartmentSummarySQL(roleFilter))
                            .map((row, metadata) -> mapDepartmentSummary(row))
                            .all()
                            .onErrorResume(e -> {
                                log.error("Error fetching maintenance department summary with role", e);
                                return Flux.empty();
                            });
                });
    }

    private String buildDepartmentSummarySQL(String roleFilter) {
        return """
            SELECT
                COALESCE(m.department, SUBSTRING(mr.machine_code FROM 6 FOR 3)) as department,
                CASE
                    WHEN d.department IS NOT NULL AND d.division IS NOT NULL AND d.division != '' THEN
                        d.department || ' - ' || d.division
                    WHEN d.department IS NOT NULL THEN
                        d.department
                    ELSE
                        'Department ' || COALESCE(m.department, SUBSTRING(mr.machine_code FROM 6 FOR 3))
                END as department_name,
                COUNT(*) as total,
                COUNT(CASE WHEN mr.status = 'Pass' THEN 1 END) as total_pass,
                COUNT(CASE WHEN mr.status = 'Not Pass' THEN 1 END) as total_not_pass,
                COUNT(CASE WHEN mr.actual_date IS NOT NULL
                           AND mr.actual_date <= mr.due_date THEN 1 END) as total_on_time,
                COUNT(CASE WHEN mr.actual_date IS NOT NULL
                           AND mr.actual_date > mr.due_date THEN 1 END) as total_overdue,
                COUNT(CASE WHEN mr.actual_date IS NOT NULL THEN 1 END) as total_completed,
                COUNT(CASE WHEN mr.actual_date IS NULL THEN 1 END) as total_pending
            FROM maintenance_record mr
            LEFT JOIN machine m ON mr.machine_code = m.machine_code
            LEFT JOIN department d ON m.department = d.department_code
            WHERE 1=1
              %s
            GROUP BY
                COALESCE(m.department, SUBSTRING(mr.machine_code FROM 6 FOR 3)),
                d.department, d.division
            HAVING COUNT(*) > 0
            ORDER BY total DESC
            """.formatted(roleFilter);
    }

    // ─── GET BY ID ────────────────────────────────────────────────────────────

    public Mono<ApiResponse<MaintenanceResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), MaintenanceRecord.class)
                .flatMap(maintenance -> {
                    List<Long> memberIds = new ArrayList<>();
                    if (maintenance.getCreatedBy() != null) memberIds.add(maintenance.getCreatedBy());
                    if (maintenance.getUpdatedBy() != null) memberIds.add(maintenance.getUpdatedBy());

                    Mono<Map<Long, Member>> membersMono = memberIds.isEmpty()
                            ? Mono.just(new HashMap<>())
                            : commonService.fetchMembersByIds(memberIds);

                    return membersMono.map(memberMap -> {
                        MaintenanceResponseDTO dto = MaintenanceResponseDTO.from(maintenance);
                        return ApiResponse.success("MS017", dto);
                    });
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Data not found")))
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ─── GET BY MACHINE CODE ──────────────────────────────────────────────────

    public Mono<ApiResponse<List<MaintenanceResponseDTO>>> getByMachineCode(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode))
                                .sort(Sort.by("due_date").ascending()),
                        MaintenanceRecord.class)
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) {
                        return Mono.just(ApiResponse.<List<MaintenanceResponseDTO>>error("MS018", "Data not found"));
                    }
                    List<MaintenanceResponseDTO> dtos = records.stream()
                            .map(MaintenanceResponseDTO::from)
                            .toList();
                    return Mono.just(ApiResponse.success("MS017", dtos));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance by machine code: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ─── VALIDATE ─────────────────────────────────────────────────────────────

    public Mono<MaintenanceDTO> validateData(MaintenanceDTO dto, boolean isUpdate) {
        if (!isUpdate && dto.getDueDate() == null) {
            return Mono.error(new ThrowException("MS001", "Maintenance due date is required"));
        }
        // ตรวจสอบว่า record มีอยู่จริง
        return template.selectOne(
                        Query.query(Criteria.where("id").is(dto.getId())),
                        MaintenanceRecord.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018", "Maintenance record not found")))
                .flatMap(existing -> Mono.just(dto));
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private Update buildUpdateFromDTO(MaintenanceDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "due_date",                dto.getDueDate());
        addIfNotNull(params, "plan_date",               dto.getPlanDate());
        addIfNotNull(params, "start_date",              dto.getStartDate());
        addIfNotNull(params, "actual_date",             dto.getActualDate());
        addIfNotNull(params, "status",                  dto.getStatus());
        addIfNotNull(params, "maintenance_by",          dto.getMaintenanceBy());
        addIfNotNull(params, "responsible_maintenance", dto.getResponsibleMaintenance());
        addIfNotNull(params, "note",                    dto.getNote());
        addIfNotNull(params, "attachment",              dto.getAttachment());
        return Update.from(params);
    }

    private Flux<MaintenanceListDTO> convertMaintenanceListDTOs(List<MaintenanceRecord> records) {
        return Flux.fromIterable(records).map(MaintenanceListDTO::from);
    }

    private MaintenanceDepartmentSummaryDTO mapDepartmentSummary(io.r2dbc.spi.Row row) {
        try {
            return MaintenanceDepartmentSummaryDTO.builder()
                    .department(row.get("department", String.class))
                    .departmentName(row.get("department_name", String.class))
                    .total(getLongValue(row, "total"))
                    .totalPass(getLongValue(row, "total_pass"))
                    .totalNotPass(getLongValue(row, "total_not_pass"))
                    .totalOnTime(getLongValue(row, "total_on_time"))
                    .totalOverdue(getLongValue(row, "total_overdue"))
                    .totalCompleted(getLongValue(row, "total_completed"))
                    .totalPending(getLongValue(row, "total_pending"))
                    .build();
        } catch (Exception e) {
            log.error("Error mapping maintenance department summary row", e);
            throw new RuntimeException("Error mapping maintenance department summary data", e);
        }
    }

    private Long getLongValue(io.r2dbc.spi.Row row, String columnName) {
        Object value = row.get(columnName);
        return switch (value) {
            case Long l -> l;
            case Number n -> n.longValue();
            case null, default -> 0L;
        };
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }
}