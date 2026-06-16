package com.acme.checklist.scheduler;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChecklistScheduler {

    private final R2dbcEntityTemplate template;
    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    // ─── WEEKLY ───────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 1 0 * * MON", zone = "Asia/Bangkok")
    public void updateOverdueChecklistsWeek() {
        buildUpdateOverdue("WEEKLY")
                .then(resetMachineCheckStatus("WEEKLY"))
                .doOnSuccess(v -> log.info("[SCHEDULER] Weekly overdue + reset completed"))
                .doOnError(e -> log.error("[SCHEDULER] Weekly failed: {}", e.getMessage()))
                .block();
    }

    // ─── MONTHLY ──────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Bangkok")
    public void updateOverdueChecklistsMonth() {
        buildUpdateOverdue("MONTHLY")
                .then(resetMachineCheckStatus("MONTHLY"))
                .doOnSuccess(v -> log.info("[SCHEDULER] Monthly overdue + reset completed"))
                .doOnError(e -> log.error("[SCHEDULER] Monthly failed: {}", e.getMessage()))
                .block();
    }

    // ─── AUTO SAVE WEEKLY ─────────────────────────────────────────────────────
    @Scheduled(cron = "0 59 23 * * FRI", zone = "Asia/Bangkok")
    public void autoSaveWeeklyChecklistRecords() {
        LocalDate today  = LocalDate.now(ZONE);
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate friday = today.with(DayOfWeek.FRIDAY);

        Instant startOfWeek = monday.atStartOfDay(ZONE).toInstant();
        Instant endOfWeek   = friday.plusDays(1).atStartOfDay(ZONE).toInstant().minusNanos(1);

        log.info("[CHECKLIST-AUTO] Weekly run — week {} ~ {}", monday, friday);

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                        .and("reset_period").is("WEEKLY")
                                        .and("check_status").is("PENDING")
                        ),
                        Machine.class
                )
                .flatMap(machine -> hasChecklistRecord(machine, startOfWeek, endOfWeek)
                        .flatMap(exists -> {
                            if (exists) {
                                log.info("[CHECKLIST-AUTO] Skip machine id={} code={} (recheck record exists)",
                                        machine.getId(), machine.getMachineCode());
                                return Mono.empty();
                            }
                            return saveDefaultRecord(machine);
                        })
                )
                .then()
                .block();
    }

    // ─── AUTO SAVE MONTHLY ────────────────────────────────────────────────────
    @Scheduled(cron = "0 55 23 1 * *", zone = "Asia/Bangkok")
    public void autoSaveMonthlyChecklistRecords() {
        LocalDate today               = LocalDate.now(ZONE);
        LocalDate firstDayOfPrevMonth = today.minusMonths(1).withDayOfMonth(1);
        LocalDate lastDayOfPrevMonth  = today.minusMonths(1).withDayOfMonth(
                today.minusMonths(1).lengthOfMonth());

        Instant startOfPrevMonth = firstDayOfPrevMonth.atStartOfDay(ZONE).toInstant();
        Instant endOfPrevMonth   = lastDayOfPrevMonth.plusDays(1).atStartOfDay(ZONE)
                .toInstant().minusNanos(1);

        log.info("[CHECKLIST-AUTO] Monthly run — {} ~ {}", firstDayOfPrevMonth, lastDayOfPrevMonth);

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                        .and("reset_period").is("MONTHLY")
                                        .and("check_status").is("PENDING")
                        ),
                        Machine.class
                )
                .flatMap(machine -> hasChecklistRecord(machine, startOfPrevMonth, endOfPrevMonth)
                        .flatMap(exists -> {
                            if (exists) {
                                log.info("[CHECKLIST-AUTO] Skip machine id={} code={} (recheck record exists)",
                                        machine.getId(), machine.getMachineCode());
                                return Mono.empty();
                            }
                            return saveDefaultRecord(machine);
                        })
                )
                .then()
                .block();
    }

    // ─── CORE ─────────────────────────────────────────────────────────────────

    private Mono<Void> buildUpdateOverdue(String period) {
        // ใช้ machine.id แทน machine_code เพราะ machine_code อาจซ้ำในฐานข้อมูล
        return template.select(
                        Query.query(Criteria.where("reset_period").is(period)),
                        Machine.class
                )
                .map(Machine::getId)
                .collectList()
                .flatMap(machineIds -> {
                    if (machineIds.isEmpty()) {
                        log.info("[OVERDUE-{}] No machines found for period", period);
                        return Mono.empty();
                    }

                    List<String> statuses = List.of("PENDING SUPERVISOR", "PENDING MANAGER");
                    return Flux.fromIterable(statuses)
                            .flatMap(status -> {
                                Query query = Query.query(
                                        Criteria.where("checklist_status").is(status)
                                                .and("machine_id").in(machineIds)
                                );
                                Update update = Update.update("checklist_status", status + "-OVERDUE");
                                return template.update(query, update, ChecklistRecord.class);
                            })
                            .then();
                });
    }

    private Mono<Void> resetMachineCheckStatus(String period) {
        return template.update(
                Query.query(
                        Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                .and("reset_period").is(period)
                ),
                Update.update("check_status", "PENDING"),
                Machine.class
        ).then();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private Mono<Boolean> hasChecklistRecord(Machine machine, Instant from, Instant to) {
        return template.count(
                Query.query(
                        Criteria.where("machine_code").is(machine.getMachineCode())
                                .and("created_by").is(machine.getResponsiblePersonId())
                                .and("recheck").is(true)
                                .and("check_type").is("GENERAL")
                                .and("created_at").greaterThanOrEquals(from)
                                .and("created_at").lessThanOrEquals(to)
                ),
                ChecklistRecord.class
        ).map(count -> count > 0);
    }

    private Mono<Void> saveDefaultRecord(Machine machine) {
        if (machine.getResponsiblePersonId() == null) {
            log.warn("[CHECKLIST-AUTO] Skip machine id={} code={} — responsiblePersonId is null",
                    machine.getId(), machine.getMachineCode());
            return Mono.empty();
        }

        return template.selectOne(
                        Query.query(Criteria.where("id").is(machine.getResponsiblePersonId())),
                        Member.class
                )
                .switchIfEmpty(Mono.error(new RuntimeException(
                        "Member not found: " + machine.getResponsiblePersonId())))
                .flatMap(member -> {
                    String checklistStatus = machine.getSupervisorId() != null
                            ? "PENDING SUPERVISOR"
                            : "PENDING MANAGER";

                    ChecklistRecord record = ChecklistRecord.builder()
                            .checkType("GENERAL")
                            .recheck(true)
                            .machineCode(machine.getMachineCode())
                            .machineName(machine.getMachineName())
                            .machineStatus(machine.getMachineStatus())
                            .machineChecklist("")
                            .machineNote("Automatic recording")
                            .userId(member.getUserName())
                            .userName(machine.getResponsiblePersonName())
                            .supervisor(machine.getSupervisorId())
                            .dateSupervisorChecked(null)
                            .manager(machine.getManagerId())
                            .dateManagerChecked(null)
                            .checklistStatus(checklistStatus)
                            .reasonNotChecked("NO ACTION TAKEN")
                            .build();
                    record.setCreatedBy(machine.getResponsiblePersonId());

                    return template.insert(record)
                            .flatMap(saved -> template.update(Machine.class)
                                    .matching(Query.query(
                                            Criteria.where("id").is(machine.getId())
                                    ))
                                    .apply(Update.update("check_status", checklistStatus)
                                            .set("machine_status", machine.getMachineStatus()))
                                    .then())
                            .doOnSuccess(v -> log.info("[CHECKLIST-AUTO] Created auto record machine id={} code={} status={}",
                                    machine.getId(), machine.getMachineCode(), checklistStatus))
                            .onErrorResume(e -> {
                                log.error("[CHECKLIST-AUTO] Failed machine id={} code={}: {}",
                                        machine.getId(), machine.getMachineCode(), e.getMessage());
                                return Mono.empty();
                            });
                });
    }
}