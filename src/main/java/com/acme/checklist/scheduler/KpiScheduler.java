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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KpiScheduler {

    private final R2dbcEntityTemplate template;

    private static final List<String> ACTIVE_STATUSES = List.of("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE");
    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    // ─── 1. สร้าง KPI ต้นเดือน ─────────────────────────────────────────────────
    // ช่วง KPI = จันทร์แรกของเดือน → ศุกร์สุดท้ายของเดือน
    @Scheduled(cron = "0 0 0 1 * ?", zone = "Asia/Bangkok")
    public void createKpiRecords() {
        log.info("createKpiRecords started");
        LocalDate today = LocalDate.now(BKK);
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstDay    = ym.atDay(1);
        LocalDate lastDay     = ym.atEndOfMonth();
        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate kpiStart    = firstFriday.with(DayOfWeek.MONDAY); // จันทร์แรก
        LocalDate kpiEnd      = lastFriday;                          // ศุกร์สุดท้าย

        log.info("createKpiRecords year={} month={} kpiStart={} kpiEnd={}", year, month, kpiStart, kpiEnd);

        fetchHistory(kpiStart, kpiEnd)
                .doOnNext(h -> log.info("History found: machineCode={} responsiblePersonId={}", h.getMachineCode(), h.getResponsiblePersonId()))
                .filterWhen(h -> isMachineActive(h.getMachineCode()))
                .doOnNext(h -> log.info("Active machine passed: machineCode={}", h.getMachineCode()))
                .collectMultimap(ResponsibleHistory::getResponsiblePersonId)
                .doOnNext(map -> log.info("Total members from history: {}", map.size()))
                .flatMap(historyMap -> Flux.fromIterable(historyMap.entrySet())
                        .flatMapSequential(entry -> {
                            Long memberId = entry.getKey();

                            // checkAll = นับ Friday ที่ member รับผิดชอบ machine ในช่วง kpiStart→kpiEnd
                            long checkAll = entry.getValue().stream()
                                    .mapToLong(h -> countFridaysInRange(
                                            clampStart(h.getEffectiveFrom(), kpiStart),
                                            clampEnd(h.getEffectiveTo(), kpiEnd)))
                                    .sum();

                            log.info("createKpiRecords memberId={} checkAll={}", memberId, checkAll);

                            return template.selectOne(
                                            Query.query(Criteria.where("id").is(memberId)
                                                    .and("status").not("INACTIVE")),
                                            Member.class)
                                    .doOnSuccess(m -> {
                                        if (m == null) log.warn("Member NOT found or INACTIVE for memberId={}", memberId);
                                        else log.info("Member found memberId={} name={}", memberId, m.getFirstName());
                                    })
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
    @Scheduled(cron = "0 5 14 * * *", zone = "Asia/Bangkok")
    public void recalculateCurrentMonthKpi() {
        log.info("recalculateCurrentMonthKpi started");
        LocalDate today = LocalDate.now(BKK);

        String year  = "2026";
        String month = "05";
        YearMonth ym = YearMonth.of(2026, 5);
//        String year  = String.valueOf(today.getYear());
//        String month = String.format("%02d", today.getMonthValue());
//        YearMonth ym = YearMonth.from(today);

        LocalDate firstDay    = ym.atDay(1);
        LocalDate lastDay     = ym.atEndOfMonth();
        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate kpiStart    = firstFriday.with(DayOfWeek.MONDAY); // จันทร์แรก
        LocalDate kpiEnd      = today.isBefore(lastFriday) ? today : lastFriday; // ถึงวันนี้ หรือศุกร์สุดท้าย

        log.info("recalculateCurrentMonthKpi year={} month={} kpiStart={} kpiEnd={}", year, month, kpiStart, kpiEnd);

        template.select(
                        Query.query(Criteria.where("years").is(year).and("months").is(month)),
                        Kpi.class)
                .doOnNext(kpi -> log.info("Processing KPI memberId={}", kpi.getMemberId()))
                .flatMapSequential(kpi -> {
                    Long memberId = kpi.getMemberId();

                    // checkAll = นับ Friday ที่ member รับผิดชอบ machine active ในช่วง kpiStart→kpiEnd
                    Mono<Long> checkAllMono = fetchHistoryForMember(memberId, firstDay, lastDay)
                            .filterWhen(h -> isMachineActive(h.getMachineCode()))
                            .collectList()
                            .map(histories -> {
                                long total = histories.stream()
                                        .mapToLong(h -> countFridaysInRange(
                                                clampStart(h.getEffectiveFrom(), kpiStart),
                                                clampEnd(h.getEffectiveTo(), kpiEnd)))
                                        .sum();
                                log.info("checkAll memberId={} → {}", memberId, total);
                                return total;
                            });

                    // checked = นับ recheck=true ในช่วง kpiStart→kpiEnd
                    // JOIN responsible_history เพื่อให้แน่ใจว่า member รับผิดชอบ machine นั้น
                    // ในวันที่บันทึกจริง (รองรับการเปลี่ยนผู้รับผิดชอบ)
                    // ระบบการันตีว่าแต่ละสัปดาห์มี recheck=true ได้แค่ 1 ต่อเครื่อง
                    // จึงนับ COUNT(*) ได้เลย
                    Mono<Long> checkedMono = template.getDatabaseClient()
                            .sql("""
                                SELECT COUNT(*) FROM checklist_record cr
                                JOIN machine m ON cr.machine_code = m.machine_code
                                JOIN responsible_history rh
                                    ON rh.machine_code = cr.machine_code
                                    AND rh.responsible_person_id = :memberId
                                    AND DATE(cr.created_at AT TIME ZONE 'Asia/Bangkok') >= rh.effective_from
                                    AND (rh.effective_to IS NULL OR DATE(cr.created_at AT TIME ZONE 'Asia/Bangkok') <= rh.effective_to)
                                WHERE cr.created_by = :memberId
                                  AND cr.recheck = true
                                  AND cr.check_type = 'GENERAL'
                                  AND m.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')
                                  AND cr.created_at >= :start
                                  AND cr.created_at <= :end
                                  AND (
                                      cr.machine_note IS NULL
                                      OR cr.machine_note != 'Automatic recording'
                                      OR cr.reason_not_checked IS NULL
                                      OR UPPER(cr.reason_not_checked) NOT IN ('NO ACTION TAKEN', 'RESPONSIBLE PERSON DID NOT PERFORM')
                                  )
                                """)
                            .bind("memberId", memberId)
                            .bind("start", kpiStart.atStartOfDay(BKK).toInstant())
                            .bind("end",   kpiEnd.atTime(23, 59, 59).atZone(BKK).toInstant())
                            .map((row, meta) -> row.get(0, Long.class))
                            .one()
                            .defaultIfEmpty(0L)
                            .doOnSuccess(c -> log.info("checked memberId={} → {}", memberId, c));

                    Mono<Member> memberMono = template.selectOne(
                                    Query.query(Criteria.where("id").is(memberId)
                                            .and("status").not("INACTIVE")),
                                    Member.class)
                            .doOnSuccess(m -> {
                                if (m == null) log.warn("Member NOT found or INACTIVE memberId={}", memberId);
                                else log.info("Member found memberId={}", memberId);
                            });

                    return Mono.zip(checkAllMono, checkedMono, memberMono)
                            .flatMap(tuple -> {
                                long newCheckAll  = tuple.getT1();
                                long checkedCount = tuple.getT2();
                                Member member     = tuple.getT3();

                                if (newCheckAll == 0) {
                                    log.warn("checkAll=0 skipping memberId={}", memberId);
                                    return Mono.empty();
                                }

                                kpi.setCheckAll(newCheckAll);
                                kpi.setChecked(checkedCount);
                                kpi.setManagerId(member.getManager());
                                kpi.setSupervisorId(member.getSupervisor());

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
                .doOnSuccess(v -> log.info("recalculateCurrentMonthKpi completed"))
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

    // ดึง responsible_history ทุก member ในช่วง kpiStart→kpiEnd
    private Flux<ResponsibleHistory> fetchHistory(LocalDate kpiStart, LocalDate kpiEnd) {
        return template.getDatabaseClient()
                .sql("""
                        SELECT machine_code, responsible_person_id, effective_from, effective_to
                        FROM responsible_history
                        WHERE effective_from <= $1
                        AND (effective_to IS NULL OR effective_to >= $2)
                        """)
                .bind("$1", kpiEnd)
                .bind("$2", kpiStart)
                .map((row, meta) -> ResponsibleHistory.builder()
                        .machineCode(row.get("machine_code", String.class))
                        .responsiblePersonId(row.get("responsible_person_id", Long.class))
                        .effectiveFrom(row.get("effective_from", LocalDate.class))
                        .effectiveTo(row.get("effective_to", LocalDate.class))
                        .build())
                .all();
    }

    // ดึง responsible_history เฉพาะ member คนเดียว ในช่วงทั้งเดือน (เผื่อ overlap ต้นเดือน/ปลายเดือน)
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

    // นับจำนวนวันศุกร์ในช่วง from→to (inclusive)
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

    private LocalDate clampStart(LocalDate effectiveFrom, LocalDate rangeStart) {
        return effectiveFrom.isBefore(rangeStart) ? rangeStart : effectiveFrom;
    }

    private LocalDate clampEnd(LocalDate effectiveTo, LocalDate rangeEnd) {
        return (effectiveTo == null || effectiveTo.isAfter(rangeEnd)) ? rangeEnd : effectiveTo;
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