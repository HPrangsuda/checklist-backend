package com.acme.checklist.service;

import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.dashboard.CalibrationStatsDTO;
import com.acme.checklist.payload.dashboard.MaintenanceStatsDTO;
import com.acme.checklist.payload.dashboard.SoonDTO;
import com.acme.checklist.payload.dashboard.SummaryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {
    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    private Mono<MemberPrincipal> getPrincipal() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal());
    }

    // ── Machine filter clause ─────────────────────────────────────────────────

    private record MachineFilter(String clause, Long memberId) {}

    private MachineFilter buildMachineFilter(String role, Long memberId) {
        return switch (role) {
            case "MEMBER" ->
                    new MachineFilter("m.responsible_person_id = $1", memberId);
            case "SUPERVISOR" ->
                    new MachineFilter(
                            "(m.responsible_person_id = $1 OR m.supervisor_id = $1)",
                            memberId);
            case "MANAGER" ->
                    new MachineFilter(
                            "(m.responsible_person_id = $1 OR m.manager_id = $1)",
                            memberId);
            default -> new MachineFilter(null, null);
        };
    }

    // ── getSummary ────────────────────────────────────────────────────────────

    public Mono<SummaryDTO> getSummary() {
        return getPrincipal().flatMap(principal -> {
            MachineFilter f = buildMachineFilter(principal.role(), principal.memberId());
            String machineWhere = f.clause() != null ? "WHERE " + f.clause() : "";
            String subWhere = f.clause() != null
                    ? "WHERE " + f.clause().replace("m.", "mc.")
                    : "";

            String sql = """
                SELECT
                    COUNT(DISTINCT m.id) AS total,
                    COUNT(DISTINCT CASE WHEN m.machine_status = 'READY TO USE' THEN m.id END) AS total_available,
                    (SELECT COUNT(DISTINCT cr.id)
                     FROM calibration_record cr
                     JOIN machine mc ON cr.machine_code = mc.machine_code
                     %s
                     AND cr.due_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '30 days'
                    ) AS total_calibration,
                    (SELECT COUNT(DISTINCT mr.id)
                     FROM maintenance_record mr
                     JOIN machine mc ON mr.machine_code = mc.machine_code
                     %s
                     AND mr.due_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '30 days'
                    ) AS total_maintenance
                FROM machine m
                %s
            """.formatted(subWhere, subWhere, machineWhere);

            var spec = template.getDatabaseClient().sql(sql);
            if (f.memberId() != null) spec = spec.bind(0, f.memberId());

            return spec.map((row, metadata) -> {
                        try {
                            return SummaryDTO.builder()
                                    .total(getLongValue(row, "total"))
                                    .totalAvailable(getLongValue(row, "total_available"))
                                    .totalMaintenance(getLongValue(row, "total_maintenance"))
                                    .totalCalibration(getLongValue(row, "total_calibration"))
                                    .build();
                        } catch (Exception e) {
                            log.error("Error mapping summary data", e);
                            throw new RuntimeException("Error mapping summary data", e);
                        }
                    })
                    .one()
                    .onErrorResume(e -> {
                        log.error("Error fetching summary", e);
                        return Mono.just(SummaryDTO.builder()
                                .total(0L).totalAvailable(0L)
                                .totalMaintenance(0L).totalCalibration(0L)
                                .build());
                    });
        });
    }

    // ── getSoon ───────────────────────────────────────────────────────────────

    public Mono<ListResponse<List<SoonDTO>>> getSoon() {
        return getPrincipal().flatMap(principal -> {
            MachineFilter f = buildMachineFilter(principal.role(), principal.memberId());
            String joinWhere = f.clause() != null ? "AND " + f.clause() : "";

            String calibrationSql = """
                SELECT DISTINCT ON (cr.id)
                    cr.id, m.machine_code, m.machine_name,
                    'calibration' AS type,
                    cr.due_date,
                    m.responsible_person_name AS assignee
                FROM calibration_record cr
                JOIN machine m ON cr.machine_code = m.machine_code
                WHERE cr.due_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '30 days'
                %s
                ORDER BY cr.id, cr.due_date ASC
            """.formatted(joinWhere);

            String maintenanceSql = """
                SELECT DISTINCT ON (mr.id)
                    mr.id, m.machine_code, m.machine_name,
                    'maintenance' AS type,
                    mr.due_date,
                    m.responsible_person_name AS assignee
                FROM maintenance_record mr
                JOIN machine m ON mr.machine_code = m.machine_code
                WHERE mr.due_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '30 days'
                %s
                ORDER BY mr.id, mr.due_date ASC
            """.formatted(joinWhere);

            var calSpec   = template.getDatabaseClient().sql(calibrationSql);
            var maintSpec = template.getDatabaseClient().sql(maintenanceSql);
            if (f.memberId() != null) {
                calSpec   = calSpec.bind(0, f.memberId());
                maintSpec = maintSpec.bind(0, f.memberId());
            }

            Flux<SoonDTO> calItems   = calSpec.map((row, meta) -> mapToSoonDTO(row)).all();
            Flux<SoonDTO> maintItems = maintSpec.map((row, meta) -> mapToSoonDTO(row)).all();

            return Flux.concat(calItems, maintItems)
                    .sort((a, b) -> a.getDueDate().compareTo(b.getDueDate()))
                    .take(10)
                    .collectList()
                    .map(items -> ListResponse.<List<SoonDTO>>builder()
                            .success(true).message("Success").data(items).build())
                    .onErrorResume(e -> {
                        log.error("Error fetching soon tasks", e);
                        return Mono.just(ListResponse.<List<SoonDTO>>builder()
                                .success(false)
                                .message("Error fetching soon tasks: " + e.getMessage())
                                .data(List.of()).build());
                    });
        });
    }

    // ── getMaintenanceStats ───────────────────────────────────────────────────

    public Mono<ListResponse<List<MaintenanceStatsDTO>>> getMaintenanceStats() {
        return getPrincipal().flatMap(principal -> {
            MachineFilter f = buildMachineFilter(principal.role(), principal.memberId());
            String currentYear = String.valueOf(LocalDate.now().getYear());

            // memberId เป็น $2 เพราะ $1 คือ year
            String joinWhere = f.clause() != null
                    ? "JOIN machine m ON mr.machine_code = m.machine_code AND "
                    + f.clause().replace("$1", "$2")
                    : "";

            String sql = """
                WITH months AS (SELECT generate_series(1, 12) AS month_num),
                stats AS (
                    SELECT
                        EXTRACT(MONTH FROM mr.due_date)::integer AS month_num,
                        COUNT(DISTINCT CASE WHEN mr.status = 'ON TIME' AND mr.actual_date <= mr.due_date THEN mr.id END) AS on_time,
                        COUNT(DISTINCT CASE WHEN mr.status = 'OVERDUE' AND mr.actual_date > mr.due_date THEN mr.id END) AS overdue
                    FROM maintenance_record mr
                    %s
                    WHERE mr.years = $1
                    GROUP BY EXTRACT(MONTH FROM mr.due_date)
                )
                SELECT m.month_num,
                       COALESCE(s.on_time, 0) AS on_time,
                       COALESCE(s.overdue, 0) AS overdue
                FROM months m
                LEFT JOIN stats s ON m.month_num = s.month_num
                ORDER BY m.month_num
            """.formatted(joinWhere);

            var spec = template.getDatabaseClient().sql(sql).bind(0, currentYear);
            if (f.memberId() != null) spec = spec.bind(1, f.memberId());

            return spec.map((row, metadata) -> {
                        Integer monthNum = row.get("month_num", Integer.class);
                        return MaintenanceStatsDTO.builder()
                                .month(getEngMonthAbbreviation(monthNum != null ? monthNum : 1))
                                .year(Integer.parseInt(currentYear))
                                .on_time(getLongValue(row, "on_time"))
                                .overdue(getLongValue(row, "overdue"))
                                .build();
                    })
                    .all().collectList()
                    .map(stats -> ListResponse.<List<MaintenanceStatsDTO>>builder()
                            .success(true).message("Success").data(stats).build())
                    .onErrorResume(e -> {
                        log.error("Error fetching maintenance stats", e);
                        return Mono.just(ListResponse.<List<MaintenanceStatsDTO>>builder()
                                .success(false)
                                .message("Error: " + e.getMessage())
                                .data(List.of()).build());
                    });
        });
    }

    // ── getCalibrationStats ───────────────────────────────────────────────────

    public Mono<ListResponse<List<CalibrationStatsDTO>>> getCalibrationStats() {
        return getPrincipal().flatMap(principal -> {
            MachineFilter f = buildMachineFilter(principal.role(), principal.memberId());
            String currentYear = String.valueOf(LocalDate.now().getYear());

            // memberId เป็น $2 เพราะ $1 คือ year
            String joinWhere = f.clause() != null
                    ? "JOIN machine m ON cr.machine_code = m.machine_code AND "
                    + f.clause().replace("$1", "$2")
                    : "";

            String sql = """
                WITH months AS (SELECT generate_series(1, 12) AS month_num),
                stats AS (
                    SELECT
                        EXTRACT(MONTH FROM cr.due_date)::integer AS month_num,
                        COUNT(DISTINCT CASE WHEN cr.calibration_status = 'ON TIME' AND cr.certificate_date <= cr.due_date THEN cr.id END) AS on_time,
                        COUNT(DISTINCT CASE WHEN cr.calibration_status = 'OVERDUE' AND cr.certificate_date > cr.due_date THEN cr.id END) AS overdue
                    FROM calibration_record cr
                    %s
                    WHERE cr.years = $1
                    GROUP BY EXTRACT(MONTH FROM cr.due_date)
                )
                SELECT m.month_num,
                       COALESCE(s.on_time, 0) AS on_time,
                       COALESCE(s.overdue, 0) AS overdue
                FROM months m
                LEFT JOIN stats s ON m.month_num = s.month_num
                ORDER BY m.month_num
            """.formatted(joinWhere);

            var spec = template.getDatabaseClient().sql(sql).bind(0, currentYear);
            if (f.memberId() != null) spec = spec.bind(1, f.memberId());

            return spec.map((row, metadata) -> {
                        Integer monthNum = row.get("month_num", Integer.class);
                        return CalibrationStatsDTO.builder()
                                .month(getEngMonthAbbreviation(monthNum != null ? monthNum : 1))
                                .year(Integer.parseInt(currentYear))
                                .on_time(getLongValue(row, "on_time"))
                                .overdue(getLongValue(row, "overdue"))
                                .build();
                    })
                    .all().collectList()
                    .map(stats -> ListResponse.<List<CalibrationStatsDTO>>builder()
                            .success(true).message("Success").data(stats).build())
                    .onErrorResume(e -> {
                        log.error("Error fetching calibration stats", e);
                        return Mono.just(ListResponse.<List<CalibrationStatsDTO>>builder()
                                .success(false)
                                .message("Error: " + e.getMessage())
                                .data(List.of()).build());
                    });
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SoonDTO mapToSoonDTO(io.r2dbc.spi.Row row) {
        try {
            return SoonDTO.builder()
                    .machineCode(row.get("machine_code", String.class))
                    .machineName(row.get("machine_name", String.class))
                    .type(row.get("type", String.class))
                    .dueDate(row.get("due_date", LocalDate.class))
                    .assignee(row.get("assignee", String.class) != null
                            ? row.get("assignee", String.class) : "Unassigned")
                    .build();
        } catch (Exception e) {
            log.error("Error mapping SoonDTO", e);
            throw new RuntimeException("Error mapping SoonDTO", e);
        }
    }

    private Long getLongValue(io.r2dbc.spi.Row row, String columnName) {
        Object value = row.get(columnName);
        if (value == null) return 0L;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }

    private String getEngMonthAbbreviation(int month) {
        return switch (month) {
            case 1 -> "Jan"; case 2 -> "Feb"; case 3 -> "Mar";
            case 4 -> "Apr"; case 5 -> "May"; case 6 -> "Jun";
            case 7 -> "Jul"; case 8 -> "Aug"; case 9 -> "Sep";
            case 10 -> "Oct"; case 11 -> "Nov"; case 12 -> "Dec";
            default -> "";
        };
    }
}