package com.acme.checklist.scheduler;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.MachineChecklist;
import com.acme.checklist.entity.enums.MachineStatus;
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
    private static final ZoneId   ZONE             = ZoneId.of("Asia/Bangkok");
    private static final String   WEEKLY_CRON      = "0 0 0 * * 1";

    // ─── WEEKLY ───────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 5 0 * * MON", zone = "Asia/Bangkok")
    public void updateOverdueChecklistsWeek() {
        buildUpdateOverdue("WEEKLY")
                .then(resetMachineCheckStatus("WEEKLY"))
                .doOnSuccess(v -> log.info("[SCHEDULER] Weekly overdue + reset completed"))
                .doOnError(e -> log.error("[SCHEDULER] Weekly failed: {}", e.getMessage()))
                .block();
    }

    // ─── MONTHLY ──────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 0 1 * *", zone = "Asia/Bangkok")
    public void updateOverdueChecklistsMonth() {
        buildUpdateOverdue("MONTHLY")
                .then(resetMachineCheckStatus("MONTHLY"))
                .doOnSuccess(v -> log.info("[SCHEDULER] Monthly overdue + reset completed"))
                .doOnError(e -> log.error("[SCHEDULER] Monthly failed: {}", e.getMessage()))
                .block();
    }

    // ─── RESET MACHINE CHECKLIST ──────────────────────────────────────────────

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Bangkok")
    public void resetMachineChecklistByResetTime() {
        LocalDate today      = LocalDate.now(ZONE);
        DayOfWeek dow        = today.getDayOfWeek();
        int       dayOfMonth = today.getDayOfMonth();

        template.select(
                        Query.query(Criteria.where("check_status").is(true)
                                .and("reset_time").not(WEEKLY_CRON)),
                        MachineChecklist.class
                )
                .filter(item -> shouldResetToday(item.getResetTime(), dow, dayOfMonth))
                .flatMap(item -> template.update(MachineChecklist.class)
                        .matching(Query.query(Criteria.where("id").is(item.getId())))
                        .apply(Update.update("check_status", false))
                        .then()
                )
                .then()
                .block();
    }

    // ─── AUTO SAVE WEEKLY ─────────────────────────────────────────────────────

    @Scheduled(cron = "0 59 23 * * FRI", zone = "Asia/Bangkok")
    public void autoSaveWeeklyChecklistRecords() {
        LocalDate today  = LocalDate.now(ZONE);
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate friday = today.with(DayOfWeek.FRIDAY);

        Instant startOfWeek = monday.atStartOfDay(ZONE).toInstant();
        Instant endOfWeek   = friday.atTime(LocalTime.MAX).atZone(ZONE).toInstant();

        log.info("[CHECKLIST-AUTO] Weekly run — week {} ~ {}", monday, friday);

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in(MachineStatus.activeDbValues())
                                        .and("reset_period").is("WEEKLY")
                                        .and("check_status").is("PENDING")
                        ),
                        Machine.class
                )
                .concatMap(machine -> {
                    if (machine.getResponsiblePersonId() == null) {
                        return Mono.empty();
                    }
                    return hasChecklistRecord(machine, startOfWeek, endOfWeek)
                            .flatMap(exists -> {
                                if (exists) {
                                    return Mono.<Void>empty();
                                }
                                return saveDefaultRecord(machine);
                            });
                })
                .then()
                .block();
    }

    // ─── AUTO SAVE MONTHLY ────────────────────────────────────────────────────

    @Scheduled(cron = "0 55 23 1 * *", zone = "Asia/Bangkok")
    public void autoSaveMonthlyChecklistRecords() {
        LocalDate today               = LocalDate.now(ZONE);
        LocalDate firstDayOfPrevMonth = today.minusMonths(1).withDayOfMonth(1);
        LocalDate lastDayOfPrevMonth  = today.minusMonths(1)
                .withDayOfMonth(today.minusMonths(1).lengthOfMonth());

        Instant startOfPrevMonth = firstDayOfPrevMonth.atStartOfDay(ZONE).toInstant();
        Instant endOfPrevMonth   = lastDayOfPrevMonth.atTime(LocalTime.MAX).atZone(ZONE).toInstant();

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in(MachineStatus.activeDbValues())
                                        .and("reset_period").is("MONTHLY")
                                        .and("check_status").is("PENDING")
                        ),
                        Machine.class
                )
                .concatMap(machine -> {
                    if (machine.getResponsiblePersonId() == null) {
                        return Mono.empty();
                    }
                    return hasChecklistRecord(machine, startOfPrevMonth, endOfPrevMonth)
                            .flatMap(exists -> {
                                if (exists) {
                                    log.info("[CHECKLIST-AUTO] Skip machine id={} code={} (record exists in prev month)",
                                            machine.getId(), machine.getMachineCode());
                                    return Mono.<Void>empty();
                                }
                                return saveDefaultRecord(machine);
                            });
                })
                .then()
                .block();
    }

    // ─── CORE ─────────────────────────────────────────────────────────────────

    private Mono<Void> buildUpdateOverdue(String period) {
        List<String> statuses = List.of("PENDING SUPERVISOR", "PENDING MANAGER");

        return template.select(
                        Query.query(Criteria.where("reset_period").is(period)),
                        Machine.class
                )
                .map(Machine::getMachineCode)
                .collectList()
                .flatMap(machineCodes -> {
                    if (machineCodes.isEmpty()) {
                        return Mono.empty();
                    }

                    return Flux.fromIterable(statuses)
                            .flatMap(status -> template.update(
                                    Query.query(
                                            Criteria.where("checklist_status").is(status)
                                                    .and("machine_code").in(machineCodes)
                                                    .and("recheck").is(true)
                                    ),
                                    Update.update("checklist_status", status + "-OVERDUE"),
                                    ChecklistRecord.class
                            ).doOnNext(count -> log.info("[OVERDUE-{}] {} → {}-OVERDUE: {} records updated",
                                    period, status, status, count)))
                            .then();
                });
    }

    private Mono<Void> resetMachineCheckStatus(String period) {
        return template.update(
                Query.query(
                        Criteria.where("machine_status").in(MachineStatus.activeDbValues())
                                .and("reset_period").is(period)
                ),
                Update.update("check_status", "PENDING"),
                Machine.class
        ).then();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private boolean shouldResetToday(String cron, DayOfWeek dow, int dayOfMonth) {
        if (cron == null || cron.isBlank()) return false;
        try {
            String[] parts = cron.trim().split("\\s+");
            if (parts.length < 6) return false;

            String domField = parts[3];
            String dowField = parts[5];

            boolean domMatch = "*".equals(domField) || String.valueOf(dayOfMonth).equals(domField);
            boolean dowMatch = "*".equals(dowField)
                    || String.valueOf(dow.getValue()).equals(dowField)
                    || ("0".equals(dowField) && dow == DayOfWeek.SUNDAY)
                    || ("7".equals(dowField) && dow == DayOfWeek.SUNDAY);

            return domMatch && dowMatch;
        } catch (Exception e) {
            log.warn("[CHECKLIST-RESET] Invalid reset_time cron: {}", cron);
            return false;
        }
    }

    private Mono<Boolean> hasChecklistRecord(Machine machine, Instant from, Instant to) {
        return template.count(
                Query.query(
                        Criteria.where("machine_code").is(machine.getMachineCode())
                                .and("created_by").is(machine.getResponsiblePersonId())
                                .and("recheck").is(true)
                                .and("check_type").is("GENERAL")
                                .and("created_at").between(from, to)
                ),
                ChecklistRecord.class
        ).map(count -> {
            log.debug("[CHECKLIST-AUTO] hasChecklistRecord machine={} count={} from={} to={}",
                    machine.getMachineCode(), count, from, to);
            return count > 0;
        });
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
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[CHECKLIST-AUTO] Skip machine id={} code={} — member id={} not found in DB",
                            machine.getId(), machine.getMachineCode(), machine.getResponsiblePersonId());
                    return Mono.empty();
                }))
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

                    Mono<Void> updateMachine = template.update(Machine.class)
                            .matching(Query.query(Criteria.where("id").is(machine.getId())))
                            .apply(Update.update("check_status", checklistStatus)
                                    .set("machine_status", machine.getMachineStatus()))
                            .then();

                    return updateMachine
                            .then(template.insert(record))
                            .doOnSuccess(saved -> log.info("[CHECKLIST-AUTO] Created auto record machine id={} code={} status={}",
                                    machine.getId(), machine.getMachineCode(), checklistStatus))
                            .onErrorResume(e -> {
                                if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                                    log.warn("[CHECKLIST-AUTO] Skip duplicate machine id={} code={} (record already exists)",
                                            machine.getId(), machine.getMachineCode());
                                } else {
                                    log.error("[CHECKLIST-AUTO] Failed machine id={} code={}: {}",
                                            machine.getId(), machine.getMachineCode(), e.getMessage());
                                }
                                return Mono.empty();
                            })
                            .then();
                });
    }
}