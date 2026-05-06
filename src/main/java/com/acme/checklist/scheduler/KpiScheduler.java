package com.acme.checklist.scheduler;

import com.acme.checklist.entity.*;
import com.acme.checklist.entity.ResponsibleHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KpiScheduler {

    private final R2dbcEntityTemplate template;

    private static final List<String> ACTIVE_STATUSES = List.of("IN USE", "NOT IN USE", "UNDER MAINTENANCE");

    // ─── 1. สร้าง KPI ต้นเดือน ─────────────────────────────────────────────────
    @Scheduled(cron = "0 3 17 6 * ?")
    public void createKpiRecords() {
        LocalDate today = LocalDate.now();
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstDay = ym.atDay(1);
        LocalDate lastDay  = ym.atEndOfMonth();

        fetchHistory(firstDay, lastDay)
                .doOnNext(h -> log.info("History found: machineCode={} responsiblePersonId={}", h.getMachineCode(), h.getResponsiblePersonId()))
                .filterWhen(h -> isMachineActive(h.getMachineCode()))
                .doOnNext(h -> log.info("Active machine passed: machineCode={}", h.getMachineCode()))
                .collectMultimap(ResponsibleHistory::getResponsiblePersonId)
                .doOnNext(map -> log.info("Total members from history: {}", map.size()))
                .flatMap(historyMap -> Flux.fromIterable(historyMap.entrySet())
                        .flatMapSequential(entry -> {
                            Long memberId = entry.getKey();

                            long checkAll = entry.getValue().stream()
                                    .mapToLong(h -> countFridaysInRange(
                                            clampStart(h.getEffectiveFrom(), firstDay),
                                            clampEnd(h.getEffectiveTo(), lastDay)))
                                    .sum();

                            return template.selectOne(
                                            Query.query(Criteria.where("id").is(memberId)
                                                    .and("status").not("INACTIVE")),
                                            Member.class)
                                    .doOnSuccess(m -> { if (m == null) log.warn("Member NOT found or INACTIVE for memberId={}", memberId); })
                                    .flatMap(member -> insertKpiIfAbsent(
                                            memberId,
                                            member.getFirstName() + " " + member.getLastName(),
                                            year, month, checkAll,
                                            member.getManager(),
                                            member.getSupervisor()
                                    ))
                                    .onErrorResume(e -> {
                                        log.error("Failed memberId={}: {} - {}", memberId, e.getClass().getSimpleName(), e.getMessage(), e);
                                        return Mono.empty();
                                    });
                        }).then()
                )
                .doOnError(e -> log.error("Pipeline error: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e))
                .subscribe(null, e -> log.error("createKpiRecords failed: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e));
    }

    // ─── 2. Recalculate รายวัน ──────────────────────────────────────────────────
    @Scheduled(cron = "0 5 17 * * *")
    public void recalculateCurrentMonthKpi() {
        LocalDate today = LocalDate.now();
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstDay    = ym.atDay(1);
        LocalDate lastDay     = ym.atEndOfMonth();
        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate start       = firstFriday.with(DayOfWeek.MONDAY);
        LocalDate endDate     = today.isBefore(lastFriday) ? today : lastFriday;

        template.select(
                        Query.query(Criteria.where("years").is(year).and("months").is(month)),
                        Kpi.class)
                .flatMapSequential(kpi -> {
                    Long memberId = kpi.getMemberId();

                    Mono<Long> checkAllMono = fetchHistoryForMember(memberId, firstDay, lastDay)
                            .filterWhen(h -> isMachineActive(h.getMachineCode()))
                            .collectList()
                            .map(histories -> histories.stream()
                                    .mapToLong(h -> countFridaysInRange(
                                            clampStart(h.getEffectiveFrom(), firstDay),
                                            clampEnd(h.getEffectiveTo(), lastDay)))
                                    .sum());

                    Mono<Long> checkedMono = template.count(
                            Query.query(Criteria.where("created_by").is(memberId)
                                    .and("recheck").is(true)
                                    .and("check_type").is("GENERAL")
                                    .and("created_at").greaterThanOrEquals(
                                            start.atStartOfDay().toInstant(ZoneOffset.UTC))
                                    .and("created_at").lessThanOrEquals(
                                            endDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC))),
                            ChecklistRecord.class);

                    Mono<Member> memberMono = template.selectOne(
                            Query.query(Criteria.where("id").is(memberId)
                                    .and("status").not("INACTIVE")),
                            Member.class);

                    return Mono.zip(checkAllMono, checkedMono, memberMono)
                            .flatMap(tuple -> {
                                long newCheckAll  = tuple.getT1();
                                long checkedCount = tuple.getT2();
                                Member member     = tuple.getT3();

                                if (newCheckAll == 0) return Mono.empty();

                                kpi.setCheckAll(newCheckAll);
                                kpi.setChecked(checkedCount);
                                kpi.setManagerId(member.getManager());        // ← จาก Member
                                kpi.setSupervisorId(member.getSupervisor());  // ← จาก Member

                                return template.update(kpi)
                                        .doOnSuccess(v -> log.info("Updated KPI memberId={} → checkAll={}, checked={}",
                                                memberId, newCheckAll, checkedCount))
                                        .then();
                            })
                            .onErrorResume(e -> {
                                log.error("Failed to recalculate KPI for memberId={}: {} - {}", memberId, e.getClass().getSimpleName(), e.getMessage(), e);
                                return Mono.empty();
                            });
                })
                .then()
                .doOnError(e -> log.error("recalculateCurrentMonthKpi pipeline error: {}", e.getMessage(), e))
                .subscribe(null, e -> log.error("recalculateCurrentMonthKpi failed: {}", e.getMessage(), e));
    }

    // ─── INSERT with upsert (null-safe) ────────────────────────────────────────
    private Mono<Void> insertKpiIfAbsent(Long memberId, String employeeName,
                                         String year, String month,
                                         long checkAll,
                                         Long managerId, Long supervisorId) {
        DatabaseClient.GenericExecuteSpec spec = template.getDatabaseClient()
                .sql("""
                    INSERT INTO kpi (member_id, employee_name, years, months, check_all, checked, manager_id, supervisor_id)
                    VALUES (:memberId, :employeeName, :years, :months, :checkAll, :checked, :managerId, :supervisorId)
                    ON CONFLICT (member_id, years, months) DO NOTHING
                    """)
                .bind("memberId", memberId)
                .bind("employeeName", employeeName)
                .bind("years", year)
                .bind("months", month)
                .bind("checkAll", checkAll)
                .bind("checked", 0L);

        spec = managerId != null
                ? spec.bind("managerId", managerId)
                : spec.bindNull("managerId", Long.class);

        spec = supervisorId != null
                ? spec.bind("supervisorId", supervisorId)
                : spec.bindNull("supervisorId", Long.class);

        return spec.fetch()
                .rowsUpdated()
                .doOnSuccess(rows -> {
                    if (rows > 0)
                        log.info("✓ Created KPI for memberId={} {}-{}", memberId, year, month);
                    else
                        log.info("KPI already exists for memberId={} {}-{}", memberId, year, month);
                })
                .doOnError(e -> log.error("✗ Insert KPI failed for memberId={}: {}", memberId, e.getMessage()))
                .then();
    }

    // ─── HELPERS ───────────────────────────────────────────────────────────────

    private Flux<ResponsibleHistory> fetchHistory(LocalDate firstDay, LocalDate lastDay) {
        return template.getDatabaseClient()
                .sql("""
                        SELECT machine_code, responsible_person_id, effective_from, effective_to
                        FROM responsible_history
                        WHERE effective_from <= $1
                        AND (effective_to IS NULL OR effective_to >= $2)
                        """)
                .bind("$1", lastDay)
                .bind("$2", firstDay)
                .map((row, meta) -> ResponsibleHistory.builder()
                        .machineCode(row.get("machine_code", String.class))
                        .responsiblePersonId(row.get("responsible_person_id", Long.class))
                        .effectiveFrom(row.get("effective_from", LocalDate.class))
                        .effectiveTo(row.get("effective_to", LocalDate.class))
                        .build())
                .all();
    }

    private Flux<ResponsibleHistory> fetchHistoryForMember(Long memberId, LocalDate firstDay, LocalDate lastDay) {
        return template.getDatabaseClient()
                .sql("""
                        SELECT machine_code, responsible_person_id, effective_from, effective_to
                        FROM responsible_history
                        WHERE responsible_person_id = $1
                        AND effective_from <= $2
                        AND (effective_to IS NULL OR effective_to >= $3)
                        """)
                .bind("$1", memberId)
                .bind("$2", lastDay)
                .bind("$3", firstDay)
                .map((row, meta) -> ResponsibleHistory.builder()
                        .machineCode(row.get("machine_code", String.class))
                        .responsiblePersonId(row.get("responsible_person_id", Long.class))
                        .effectiveFrom(row.get("effective_from", LocalDate.class))
                        .effectiveTo(row.get("effective_to", LocalDate.class))
                        .build())
                .all();
    }

    private Mono<Boolean> isMachineActive(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode)
                                .and("machine_status").in(ACTIVE_STATUSES)),
                        Machine.class)
                .next()
                .map(m -> !"MONTHLY".equals(m.getResetPeriod()))
                .defaultIfEmpty(false);
    }

    private long countFridaysInRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) return 0;
        long count = 0;
        LocalDate d = from;
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY) count++;
            d = d.plusDays(1);
        }
        return count;
    }

    private LocalDate clampStart(LocalDate effectiveFrom, LocalDate monthStart) {
        return effectiveFrom.isBefore(monthStart) ? monthStart : effectiveFrom;
    }

    private LocalDate clampEnd(LocalDate effectiveTo, LocalDate monthEnd) {
        return (effectiveTo == null || effectiveTo.isAfter(monthEnd)) ? monthEnd : effectiveTo;
    }

    private LocalDate getFirstFridayOfMonth(YearMonth ym) {
        LocalDate d = ym.atDay(1);
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.plusDays(1);
        return d;
    }

    private LocalDate getLastFridayOfMonth(YearMonth ym) {
        LocalDate d = ym.atEndOfMonth();
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.minusDays(1);
        return d;
    }
}