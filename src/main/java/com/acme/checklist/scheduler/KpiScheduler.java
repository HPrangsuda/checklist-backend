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
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KpiScheduler {

    private final R2dbcEntityTemplate template;

    private static final List<String> ACTIVE_STATUSES = List.of("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE");
    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    // ─── 1. สร้าง KPI ต้นเดือน ─────────────────────────────────────────────────
    // check_all = (เครื่อง non-MONTHLY × จำนวน Friday ทั้งเดือน) + (เครื่อง MONTHLY × 1)
    // fix ไว้ตั้งแต่ต้นเดือน ไม่เปลี่ยนจนกว่าจะมีการโอนเครื่อง
    @Scheduled(cron = "0 0 0 1 * ?", zone = "Asia/Bangkok")
    public void createKpiRecords() {
        log.info("createKpiRecords started");
        LocalDate today = LocalDate.now(BKK);
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate kpiStart    = firstFriday.with(DayOfWeek.MONDAY);
        LocalDate kpiEnd      = lastFriday; // ใช้ทั้งเดือนเสมอ

        log.info("createKpiRecords year={} month={} kpiStart={} kpiEnd={}", year, month, kpiStart, kpiEnd);

        fetchHistory(kpiStart, kpiEnd)
                .doOnNext(h -> log.info("History found: machineCode={} responsiblePersonId={}", h.getMachineCode(), h.getResponsiblePersonId()))
                .flatMap(h -> findActiveMachine(h.getMachineCode())
                        .map(machine -> {
                            LocalDate cs = clampStart(h.getEffectiveFrom(), kpiStart);
                            LocalDate ce = clampEnd(h.getEffectiveTo(), kpiEnd);
                            long contribution = "MONTHLY".equals(machine.getResetPeriod())
                                    ? 1L
                                    : countFridaysInRange(cs, ce);
                            log.info("Machine {} resetPeriod={} contribution={}", h.getMachineCode(), machine.getResetPeriod(), contribution);
                            return new MemberContribution(h.getResponsiblePersonId(), contribution);
                        })
                        .doOnSuccess(mc -> {
                            if (mc == null)
                                log.info("Machine {} is not active, skipping", h.getMachineCode());
                        })
                )
                .collectMultimap(MemberContribution::memberId, MemberContribution::contribution)
                .doOnNext(map -> log.info("Total members from history: {}", map.size()))
                .flatMap(contributionMap -> Flux.fromIterable(contributionMap.entrySet())
                        .flatMapSequential(entry -> {
                            Long memberId = entry.getKey();
                            long checkAll = entry.getValue().stream().mapToLong(Long::longValue).sum();
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

    // ─── 2. Recalculate checked รายวัน (ไม่แตะ check_all) ─────────────────────
    // check_all ถูก fix ตั้งแต่ต้นเดือน อัปเดตเฉพาะเมื่อมีการเปลี่ยนผู้รับผิดชอบ (recalculateCheckAll)
    // method นี้อัปเดตแค่ checked (จำนวนที่ตรวจสอบจริงสะสมถึงวันนี้)
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Bangkok")
    public void recalculateCurrentMonthKpi() {
        LocalDate today = LocalDate.now(BKK);
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate kpiStart    = firstFriday.with(DayOfWeek.MONDAY);
        // checked นับตั้งแต่ kpiStart ถึงวันนี้ (หรือ lastFriday ถ้าเลยแล้ว)
        LocalDate checkedEnd  = today.isAfter(lastFriday) ? lastFriday : today;

        log.info("recalculateCurrentMonthKpi year={} month={} kpiStart={} checkedEnd={}", year, month, kpiStart, checkedEnd);

        template.select(
                        Query.query(Criteria.where("years").is(year).and("months").is(month)),
                        Kpi.class)
                .doOnNext(kpi -> log.info("Processing KPI memberId={}", kpi.getMemberId()))
                .flatMapSequential(kpi -> {
                    Long memberId = kpi.getMemberId();

                    // checked = นับ recheck=true ในช่วง kpiStart→checkedEnd
                    // $1 = memberId (rh.responsible_person_id)
                    // $2 = memberId (cr.created_by)
                    // $3 = kpiStart (instant)
                    // $4 = checkedEnd 23:59:59 (instant)
                    Mono<Long> checkedMono = template.getDatabaseClient()
                            .sql("""
                                SELECT COUNT(*) FROM checklist_record cr
                                JOIN machine m ON cr.machine_code = m.machine_code
                                JOIN responsible_history rh
                                    ON rh.machine_code = cr.machine_code
                                    AND rh.responsible_person_id = $1
                                    AND DATE(cr.created_at AT TIME ZONE 'Asia/Bangkok') >= rh.effective_from
                                    AND (rh.effective_to IS NULL OR DATE(cr.created_at AT TIME ZONE 'Asia/Bangkok') <= rh.effective_to)
                                WHERE cr.created_by = $2
                                  AND cr.recheck = true
                                  AND cr.check_type = 'GENERAL'
                                  AND m.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')
                                  AND cr.created_at >= $3
                                  AND cr.created_at <= $4
                                  AND (
                                      cr.machine_note IS NULL
                                      OR cr.machine_note != 'Automatic recording'
                                      OR cr.reason_not_checked IS NULL
                                      OR UPPER(cr.reason_not_checked) NOT IN ('NO ACTION TAKEN', 'RESPONSIBLE PERSON DID NOT PERFORM')
                                  )
                                """)
                            .bind(0, memberId)
                            .bind(1, memberId)
                            .bind(2, kpiStart.atStartOfDay(BKK).toInstant())
                            .bind(3, checkedEnd.atTime(23, 59, 59).atZone(BKK).toInstant())
                            .map((row, meta) -> row.get(0, Long.class))
                            .one()
                            .defaultIfEmpty(0L)
                            .doOnSuccess(c -> log.info("checked memberId={} → {}", memberId, c));

                    // อัปเดตแค่ checked ไม่แตะ check_all
                    return checkedMono
                            .flatMap(checkedCount -> {
                                kpi.setChecked(checkedCount);
                                return template.update(kpi)
                                        .doOnSuccess(v -> log.info("Updated checked memberId={} → {}", memberId, checkedCount))
                                        .then();
                            })
                            .onErrorResume(e -> {
                                log.error("Failed to recalculate checked for memberId={}: {} - {}", memberId, e.getClass().getSimpleName(), e.getMessage(), e);
                                return Mono.empty();
                            });
                })
                .then()
                .doOnSuccess(v -> log.info("recalculateCurrentMonthKpi completed"))
                .doOnError(e -> log.error("recalculateCurrentMonthKpi pipeline error: {}", e.getMessage(), e))
                .subscribe(null, e -> log.error("recalculateCurrentMonthKpi failed: {}", e.getMessage(), e));
    }

    // ─── 3. Recalculate check_all เมื่อมีการเปลี่ยนผู้รับผิดชอบ ────────────────
    // เรียกจาก Service ที่ insert/update responsible_history
    // ส่ง memberId ทั้ง old owner และ new owner ที่ได้รับผลกระทบ
    public Mono<Void> recalculateCheckAll(Long memberId) {
        LocalDate today = LocalDate.now(BKK);
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstDay    = ym.atDay(1);
        LocalDate lastDay     = ym.atEndOfMonth();
        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate kpiStart    = firstFriday.with(DayOfWeek.MONDAY);
        LocalDate kpiEnd      = lastFriday; // check_all ใช้ทั้งเดือนเสมอ

        log.info("recalculateCheckAll memberId={} year={} month={} kpiStart={} kpiEnd={}", memberId, year, month, kpiStart, kpiEnd);

        Mono<Long> checkAllMono = fetchHistoryForMember(memberId, firstDay, lastDay)
                .flatMap(h -> findActiveMachine(h.getMachineCode())
                        .map(machine -> {
                            LocalDate cs = clampStart(h.getEffectiveFrom(), kpiStart);
                            LocalDate ce = clampEnd(h.getEffectiveTo(), kpiEnd);
                            long contribution = "MONTHLY".equals(machine.getResetPeriod())
                                    ? 1L
                                    : countFridaysInRange(cs, ce);
                            log.info("recalculateCheckAll machine={} resetPeriod={} contribution={}", h.getMachineCode(), machine.getResetPeriod(), contribution);
                            return contribution;
                        })
                        .defaultIfEmpty(0L)
                )
                .reduce(0L, Long::sum)
                .doOnSuccess(total -> log.info("recalculateCheckAll memberId={} newCheckAll={}", memberId, total));

        return checkAllMono
                .flatMap(newCheckAll -> template.getDatabaseClient()
                        .sql("""
                            UPDATE kpi SET check_all = $1
                            WHERE member_id = $2 AND years = $3 AND months = $4
                            """)
                        .bind(0, newCheckAll)
                        .bind(1, memberId)
                        .bind(2, year)
                        .bind(3, month)
                        .fetch()
                        .rowsUpdated()
                        .doOnSuccess(rows -> log.info("Updated check_all memberId={} → {} (rows={})", memberId, newCheckAll, rows))
                        .then()
                )
                .doOnError(e -> log.error("recalculateCheckAll failed memberId={}: {}", memberId, e.getMessage(), e));
    }

    // ─── INSERT with upsert (null-safe) ────────────────────────────────────────
    private Mono<Void> insertKpiIfAbsent(Long memberId, String employeeName,
                                         String year, String month,
                                         long checkAll,
                                         Long managerId, Long supervisorId) {
        // $1=memberId $2=employeeName $3=years $4=months $5=checkAll $6=checked $7=managerId $8=supervisorId
        DatabaseClient.GenericExecuteSpec spec = template.getDatabaseClient()
                .sql("""
                    INSERT INTO kpi (member_id, employee_name, years, months, check_all, checked, manager_id, supervisor_id)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                    ON CONFLICT (member_id, years, months) DO UPDATE
                      SET check_all     = EXCLUDED.check_all,
                          employee_name = EXCLUDED.employee_name,
                          manager_id    = EXCLUDED.manager_id,
                          supervisor_id = EXCLUDED.supervisor_id
                    """)
                .bind(0, memberId)
                .bind(1, employeeName)
                .bind(2, year)
                .bind(3, month)
                .bind(4, checkAll)
                .bind(5, 0L);

        spec = managerId != null
                ? spec.bind(6, managerId)
                : spec.bindNull(6, Long.class);

        spec = supervisorId != null
                ? spec.bind(7, supervisorId)
                : spec.bindNull(7, Long.class);

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

    // ดึง Machine active เพื่อตรวจ resetPeriod — คืน empty ถ้า status ไม่ active
    private Mono<Machine> findActiveMachine(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode)
                                .and("machine_status").in(ACTIVE_STATUSES)),
                        Machine.class)
                .next();
    }

    // ดึง responsible_history ทุก member ในช่วง kpiStart→kpiEnd
    private Flux<ResponsibleHistory> fetchHistory(LocalDate kpiStart, LocalDate kpiEnd) {
        return template.getDatabaseClient()
                .sql("""
                        SELECT machine_code, responsible_person_id, effective_from, effective_to
                        FROM responsible_history
                        WHERE effective_from <= $1
                        AND (effective_to IS NULL OR effective_to >= $2)
                        """)
                .bind(0, kpiEnd)
                .bind(1, kpiStart)
                .map((row, meta) -> ResponsibleHistory.builder()
                        .machineCode(row.get("machine_code", String.class))
                        .responsiblePersonId(row.get("responsible_person_id", Long.class))
                        .effectiveFrom(row.get("effective_from", LocalDate.class))
                        .effectiveTo(row.get("effective_to", LocalDate.class))
                        .build())
                .all();
    }

    // ดึง responsible_history เฉพาะ member คนเดียว ในช่วงทั้งเดือน
    private Flux<ResponsibleHistory> fetchHistoryForMember(Long memberId, LocalDate firstDay, LocalDate lastDay) {
        return template.getDatabaseClient()
                .sql("""
                        SELECT machine_code, responsible_person_id, effective_from, effective_to
                        FROM responsible_history
                        WHERE responsible_person_id = $1
                        AND effective_from <= $2
                        AND (effective_to IS NULL OR effective_to >= $3)
                        """)
                .bind(0, memberId)
                .bind(1, lastDay)
                .bind(2, firstDay)
                .map((row, meta) -> ResponsibleHistory.builder()
                        .machineCode(row.get("machine_code", String.class))
                        .responsiblePersonId(row.get("responsible_person_id", Long.class))
                        .effectiveFrom(row.get("effective_from", LocalDate.class))
                        .effectiveTo(row.get("effective_to", LocalDate.class))
                        .build())
                .all();
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

    // ─── Inner record ──────────────────────────────────────────────────────────
    private record MemberContribution(Long memberId, Long contribution) {}
}