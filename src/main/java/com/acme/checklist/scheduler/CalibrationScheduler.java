package com.acme.checklist.scheduler;

import com.acme.checklist.entity.CalibrationRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Department;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.enums.RoleType;
import com.acme.checklist.service.EmailService;
import com.acme.checklist.service.LarkService;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.acme.checklist.scheduler.SchedulerLocks.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CalibrationScheduler {

    private final LarkService larkService;
    private final EmailService emailService;
    private final R2dbcEntityTemplate template;
    private final ReactiveTransactionManager transactionManager;

    /** cron ของการแจ้ง ADMIN / DEPARTMENT_ADMIN — ใช้ทั้งตั้งเวลา และเช็คว่า "วันนี้เป็นวันแจ้ง admin" หรือไม่ */
    @Value("${scheduler.calibration.admins-cron:0 0 9 * * MON}")
    private String adminsCron;

    // ======================================================================
    // Config
    // ======================================================================

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** เริ่มแจ้งเตือนเมื่อเหลือไม่เกินกี่วันก่อนถึง due date */
    private static final int DUE_WITHIN_DAYS = 45;
    private static final int RED_WITHIN_DAYS = 7;
    private static final int YELLOW_WITHIN_DAYS = 14;

    /** จำนวนข้อความที่ส่งพร้อมกันสูงสุด (กัน rate limit ของ Lark / SMTP) */
    private static final int SEND_CONCURRENCY = 3;

    /** จำนวนรายการสูงสุดต่อ 1 การ์ด Lark (กันการ์ดใหญ่เกิน limit) — เกินจะแบ่งเป็นหลายการ์ด */
    private static final int MAX_RECORDS_PER_CARD = 30;

    /** retry สูงสุด 3 ครั้ง รอ 2s → 4s → 8s (ไม่เกิน 10s) */
    private static final int MAX_RETRIES = 3;

    // ======================================================================
    // Job 1: คัดลอก calibration record ไปปีถัดไป (25 ธ.ค. 01:05)
    // ======================================================================

    @Scheduled(cron = "0 5 1 25 12 *", zone = "Asia/Bangkok")
    @SchedulerLock(name = CALIBRATION_COPY_NEXT_YEAR,
            lockAtLeastFor = COPY_LOCK_AT_LEAST, lockAtMostFor = COPY_LOCK_AT_MOST)
    public void createNextYearCalibrationRecords() {
        int currentYear = LocalDate.now(ZONE).getYear();
        int nextYear = currentYear + 1;
        DatabaseClient db = template.getDatabaseClient();
        TransactionalOperator tx = TransactionalOperator.create(transactionManager);

        Mono<Void> copy = db.sql("SELECT COUNT(*) AS cnt FROM calibration_record WHERE years = $1")
                .bind(0, String.valueOf(nextYear))
                .map((row, meta) -> Optional.ofNullable(row.get("cnt", Long.class)).orElse(0L))
                .one()
                .defaultIfEmpty(0L)
                .flatMap(existing -> {
                    if (existing > 0) {
                        log.warn("[COPY] Records for year={} already exist ({} records) — skipping", nextYear, existing);
                        return Mono.empty();
                    }

                    // อ่านให้ครบก่อน แล้วค่อย insert (ห้าม insert ระหว่างที่ยัง stream ผล SELECT บน connection เดียวกัน)
                    return db.sql("SELECT * FROM calibration_record WHERE years = $1")
                            .bind(0, String.valueOf(currentYear))
                            .map((row, meta) -> toNextYearRecord(row, nextYear))
                            .all()
                            .collectList()
                            .flatMap(records -> {
                                if (records.isEmpty()) {
                                    log.info("[COPY] No calibration records found for year={} — nothing to copy", currentYear);
                                    return Mono.empty();
                                }
                                log.info("[COPY] Creating {} calibration records for year={}", records.size(), nextYear);

                                return Flux.fromIterable(records)
                                        .concatMap(r -> insertCopy(db, r, nextYear))
                                        .count()
                                        .doOnNext(total -> log.info("[COPY] ✓ Created {} records for year={}", total, nextYear))
                                        .then();
                            });
                });

        // ทั้งหมดอยู่ใน transaction เดียว: insert พังกลางทาง → rollback ทั้งหมด รอบหน้าทำใหม่ได้
        tx.transactional(copy)
                .onErrorResume(e -> {
                    log.error("[COPY] ✗ Failed — rolled back, no records created for year={}: {}",
                            nextYear, e.getMessage(), e);
                    return Mono.empty();
                })
                .subscribe();
    }

    private CalibrationRecord toNextYearRecord(Row row, int nextYear) {
        CalibrationRecord r = new CalibrationRecord();
        r.setMachineCode(row.get("machine_code", String.class));
        r.setMachineName(row.get("machine_name", String.class));
        r.setCriteria(row.get("criteria", String.class));
        r.setMeasuringRange(row.get("measuring_range", String.class));
        r.setAccuracy(row.get("accuracy", String.class));
        r.setCalibrationRange(row.get("calibration_range", String.class));
        r.setPermissibleCapacity(row.get("permissible_capacity", String.class));
        r.setResolution(row.get("resolution", String.class));
        r.setMaxUncertainty(row.get("max_uncertainty", String.class));
        r.setMpe(row.get("mpe", String.class));
        r.setNote(row.get("note", String.class));
        LocalDate dueDate = row.get("due_date", LocalDate.class);
        r.setDueDate(dueDate != null ? dueDate.withYear(nextYear) : null); // 29 ก.พ. → 28 ก.พ. อัตโนมัติ
        r.setYears(String.valueOf(nextYear));
        return r;
    }

    private Mono<Long> insertCopy(DatabaseClient db, CalibrationRecord r, int nextYear) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO calibration_record
                (machine_code, machine_name, criteria, measuring_range, accuracy,
                 calibration_range, permissible_capacity, resolution, max_uncertainty,
                 mpe, note, due_date, years)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
                """);

        spec = bindNullable(spec, 0, r.getMachineCode(), String.class);
        spec = bindNullable(spec, 1, r.getMachineName(), String.class);
        spec = bindNullable(spec, 2, r.getCriteria(), String.class);
        spec = bindNullable(spec, 3, r.getMeasuringRange(), String.class);
        spec = bindNullable(spec, 4, r.getAccuracy(), String.class);
        spec = bindNullable(spec, 5, r.getCalibrationRange(), String.class);
        spec = bindNullable(spec, 6, r.getPermissibleCapacity(), String.class);
        spec = bindNullable(spec, 7, r.getResolution(), String.class);
        spec = bindNullable(spec, 8, r.getMaxUncertainty(), String.class);
        spec = bindNullable(spec, 9, r.getMpe(), String.class);
        spec = bindNullable(spec, 10, r.getNote(), String.class);
        spec = bindNullable(spec, 11, r.getDueDate(), LocalDate.class);
        spec = spec.bind(12, String.valueOf(nextYear));

        return spec.then()
                .doOnSuccess(v -> log.debug("[COPY] ✓ Inserted machineCode={}, year={}", r.getMachineCode(), nextYear))
                .doOnError(e -> log.error("[COPY] ✗ Insert failed machineCode={}: {}", r.getMachineCode(), e.getMessage()))
                .thenReturn(1L);
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, int index, T value, Class<T> type) {
        return value != null ? spec.bind(index, value) : spec.bindNull(index, type);
    }

    // ======================================================================
    // Job 2–4: แจ้งเตือน
    // ======================================================================

    /** ผู้รับผิดชอบ + หัวหน้างาน: จันทร์–ศุกร์ 09:00 */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Bangkok")
    @SchedulerLock(name = CALIBRATION_NOTIFY_DAILY,
            lockAtLeastFor = NOTIFY_LOCK_AT_LEAST, lockAtMostFor = NOTIFY_LOCK_AT_MOST)
    public void sendDailyToResponsibleAndSupervisor() {
        runNotifyJob("RESPONSIBLE_SUPERVISOR",
                calibrations -> notifyByMachineRole(calibrations, Role.RESPONSIBLE_SUPERVISOR));
    }

    /** ผู้จัดการ: จันทร์และพุธ 09:05 */
    @Scheduled(cron = "0 5 9 * * MON,WED", zone = "Asia/Bangkok")
    @SchedulerLock(name = CALIBRATION_NOTIFY_MANAGER,
            lockAtLeastFor = NOTIFY_LOCK_AT_LEAST, lockAtMostFor = NOTIFY_LOCK_AT_MOST)
    public void sendWeeklyToManager() {
        runNotifyJob("MANAGER",
                calibrations -> notifyByMachineRole(calibrations, Role.MANAGER));
    }

    /**
     * ADMIN และ DEPARTMENT_ADMIN: ทุกวันจันทร์ 09:00
     * - ADMIN            : ได้รับทุกรายการ
     * - DEPARTMENT_ADMIN : เฉพาะเครื่องที่อยู่แผนกเดียวกับตัวเอง
     * ทำทีละ role และแยก error กัน
     */
    @Scheduled(cron = "${scheduler.calibration.admins-cron:0 0 9 * * MON}", zone = "Asia/Bangkok")
    @SchedulerLock(name = CALIBRATION_NOTIFY_ADMINS,
            lockAtLeastFor = NOTIFY_LOCK_AT_LEAST, lockAtMostFor = NOTIFY_LOCK_AT_MOST)
    public void sendWeeklyToAdmins() {
        runNotifyJob("ADMINS", calibrations ->
                notifyAdmins(calibrations, Role.ADMIN)
                        .onErrorResume(e -> logRoleFailure(Role.ADMIN, e))
                        .then(Mono.defer(() -> notifyAdmins(calibrations, Role.DEPARTMENT_ADMIN))
                                .onErrorResume(e -> logRoleFailure(Role.DEPARTMENT_ADMIN, e))));
    }

    private void runNotifyJob(String jobName, Function<List<CalibrationRecord>, Mono<Void>> action) {
        long startedAt = System.currentTimeMillis();
        log.info("[JOB][{}] ▶ start", jobName);

        fetchDueCalibrations()
                .collectList()
                .flatMap(calibrations -> {
                    if (calibrations.isEmpty()) {
                        log.info("[JOB][{}] No calibrations due within {} days — nothing to send", jobName, DUE_WITHIN_DAYS);
                        return Mono.empty();
                    }
                    log.info("[JOB][{}] {} calibrations due within {} days", jobName, calibrations.size(), DUE_WITHIN_DAYS);
                    return action.apply(calibrations);
                })
                .doOnSuccess(v -> log.info("[JOB][{}] ■ done in {} ms", jobName, System.currentTimeMillis() - startedAt))
                .onErrorResume(e -> {
                    log.error("[JOB][{}] ✗ Failed after {} ms: {}",
                            jobName, System.currentTimeMillis() - startedAt, e.getMessage(), e);
                    return Mono.empty();
                })
                .subscribe();
    }

    private Mono<Void> logRoleFailure(Role role, Throwable e) {
        log.error("[JOB][{}] ✗ Failed: {}", role, e.getMessage(), e);
        return Mono.empty();
    }

    private Flux<CalibrationRecord> fetchDueCalibrations() {
        LocalDate dueLimit = LocalDate.now(ZONE).plusDays(DUE_WITHIN_DAYS);

        return template.select(
                        Query.query(
                                Criteria.where("due_date").lessThanOrEquals(dueLimit)
                                        .and("start_date").isNull()
                        ).sort(Sort.by("due_date").ascending()),
                        CalibrationRecord.class
                )
                .filter(c -> c.getDueDate() != null)
                .doOnNext(c -> log.debug("[FETCH] id={}, machineCode={}, dueDate={}",
                        c.getId(), c.getMachineCode(), c.getDueDate()));
    }

    // ======================================================================
    // หาผู้รับ: จาก machine (ผู้รับผิดชอบ / หัวหน้างาน / ผู้จัดการ)
    // ======================================================================

    private Mono<Void> notifyByMachineRole(List<CalibrationRecord> calibrations, Role role) {

        Map<String, List<CalibrationRecord>> byMachineCode = calibrations.stream()
                .filter(c -> c.getMachineCode() != null)
                .collect(Collectors.groupingBy(CalibrationRecord::getMachineCode));

        if (byMachineCode.isEmpty()) return Mono.empty();

        return template.select(
                        Query.query(Criteria.where("machine_code").in(byMachineCode.keySet())),
                        Machine.class
                )
                .collectList()
                .flatMap(machines -> {
                    Set<String> foundCodes = machines.stream()
                            .map(Machine::getMachineCode)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    byMachineCode.keySet().stream()
                            .filter(code -> !foundCodes.contains(code))
                            .forEach(code -> log.warn("[{}] machineCode={} has calibration due but no machine record — nobody notified",
                                    role, code));

                    Map<Long, Map<Long, CalibrationRecord>> byPersonMap = new HashMap<>();

                    machines.forEach(machine -> {
                        List<CalibrationRecord> records = byMachineCode.getOrDefault(machine.getMachineCode(), List.of());
                        if (records.isEmpty()) return;

                        List<Long> personIds = switch (role) {
                            case RESPONSIBLE_SUPERVISOR -> Stream.of(
                                            machine.getResponsiblePersonId(),
                                            machine.getSupervisorId())
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .toList();
                            case MANAGER -> Stream.of(machine.getManagerId())
                                    .filter(Objects::nonNull)
                                    .toList();
                            // admin roles ไม่ได้ผูกกับ machine — ใช้ notifyAdmins แทน
                            case DEPARTMENT_ADMIN, ADMIN -> List.of();
                        };

                        if (personIds.isEmpty()) {
                            log.warn("[{}] machineCode={} has no assigned person for this role — nobody notified",
                                    role, machine.getMachineCode());
                            return;
                        }

                        personIds.forEach(personId -> {
                            Map<Long, CalibrationRecord> existing =
                                    byPersonMap.computeIfAbsent(personId, k -> new LinkedHashMap<>());
                            records.forEach(r -> existing.put(r.getId(), r));
                        });
                    });

                    Map<Long, List<CalibrationRecord>> byPerson = new HashMap<>();
                    byPersonMap.forEach((personId, recs) -> byPerson.put(personId, new ArrayList<>(recs.values())));

                    if (byPerson.isEmpty()) {
                        log.warn("[{}] No recipients resolved — nothing to send", role);
                        return Mono.empty();
                    }
                    // วันที่มีแจ้ง admin: คนที่เป็น ADMIN / DEPARTMENT_ADMIN จะได้ชุดรวมจาก job admin แทน
                    return excludeAdminsOnAdminDay(byPerson, role)
                            .flatMap(filtered -> filtered.isEmpty() ? Mono.<Void>empty() : sendToMembers(filtered, role));
                });
    }

    // ======================================================================
    // หาผู้รับ: ADMIN / DEPARTMENT_ADMIN
    // - role อยู่ในคอลัมน์ member.role_type (enum RoleType) และนับเฉพาะ status = ACTIVE
    // - แผนกของ admin = member.department_id, แผนกของเครื่อง = machine.department
    //   เทียบกันผ่านตาราง department (รองรับทั้งรหัส / ชื่อ / id) ด้วย DepartmentMatcher
    // ======================================================================

    private Mono<Void> notifyAdmins(List<CalibrationRecord> records, Role role) {

        Mono<List<Member>> adminsMono = template.select(
                Query.query(Criteria.where("role_type").is(toRoleType(role).name())
                        .and("status").is("ACTIVE")),
                Member.class
        ).collectList();

        if (role == Role.ADMIN) {
            return adminsMono.flatMap(admins -> {
                if (admins.isEmpty()) {
                    log.warn("[{}] No active members with this role — skipping", role);
                    return Mono.empty();
                }
                Map<Long, List<CalibrationRecord>> byPerson = new HashMap<>();
                admins.forEach(a -> byPerson.put(a.getId(), records));
                return sendToMembers(byPerson, role);
            });
        }

        Set<String> machineCodes = records.stream()
                .map(CalibrationRecord::getMachineCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (machineCodes.isEmpty()) return Mono.empty();

        Mono<List<Machine>> machinesMono = template.select(
                Query.query(Criteria.where("machine_code").in(machineCodes)),
                Machine.class
        ).collectList();

        Mono<List<Department>> departmentsMono = template.select(Query.empty(), Department.class).collectList();

        return Mono.zip(adminsMono, machinesMono, departmentsMono)
                .flatMap(tuple -> {
                    List<Member> admins = tuple.getT1();
                    List<Machine> machines = tuple.getT2();
                    DepartmentMatcher matcher = DepartmentMatcher.of(tuple.getT3());

                    if (admins.isEmpty()) {
                        log.warn("[{}] No active members with this role — skipping", role);
                        return Mono.empty();
                    }

                    // แผนก (key กลาง) → machine codes
                    Map<String, Set<String>> codesByDept = new HashMap<>();
                    Set<String> unresolvedMachineDepts = new TreeSet<>();
                    for (Machine m : machines) {
                        if (m.getMachineCode() == null) continue;
                        String deptKey = matcher.resolve(m.getDepartment());
                        if (deptKey == null) {
                            unresolvedMachineDepts.add(String.valueOf(m.getDepartment()));
                            continue;
                        }
                        codesByDept.computeIfAbsent(deptKey, k -> new HashSet<>()).add(m.getMachineCode());
                    }
                    if (!unresolvedMachineDepts.isEmpty()) {
                        log.warn("[{}] machine.department values not found in department table: {}",
                                role, unresolvedMachineDepts);
                    }

                    Map<Long, List<CalibrationRecord>> byPerson = new HashMap<>();

                    admins.forEach(admin -> {
                        // เครื่องในแผนก + เครื่องที่ตัวเองเป็นผู้รับผิดชอบ/หัวหน้างาน/ผู้จัดการ
                        // → วันแจ้ง admin ได้ข้อความชุดเดียวที่ครบทุกรายการ
                        Set<String> codes = new HashSet<>(personalMachineCodes(machines, admin.getId()));

                        String deptKey = matcher.resolve(admin.getDepartmentId());
                        if (deptKey == null) {
                            log.warn("[{}] memberId={} department_id='{}' not found in department table — only personal machines included",
                                    role, admin.getId(), admin.getDepartmentId());
                        } else {
                            codes.addAll(codesByDept.getOrDefault(deptKey, Set.of()));
                        }

                        List<CalibrationRecord> adminRecords = records.stream()
                                .filter(r -> r.getMachineCode() != null && codes.contains(r.getMachineCode()))
                                .toList();
                        if (adminRecords.isEmpty()) {
                            log.info("[{}] memberId={} (department_id={}) — nothing due",
                                    role, admin.getId(), admin.getDepartmentId());
                            return;
                        }
                        byPerson.put(admin.getId(), adminRecords);
                    });

                    if (byPerson.isEmpty()) return Mono.empty();
                    return sendToMembers(byPerson, role);
                });
    }

    private Set<String> personalMachineCodes(List<Machine> machines, Long memberId) {
        return machines.stream()
                .filter(m -> m.getMachineCode() != null && (
                        memberId.equals(m.getResponsiblePersonId())
                                || memberId.equals(m.getSupervisorId())
                                || memberId.equals(m.getManagerId())))
                .map(Machine::getMachineCode)
                .collect(Collectors.toSet());
    }

    /**
     * วันที่มีแจ้ง admin: ตัด ADMIN / DEPARTMENT_ADMIN ออกจากการแจ้งตาม role อื่น
     * เพราะชุดของ admin ครอบคลุมรายการเหล่านั้นอยู่แล้ว → ได้รับแค่ชุดเดียว
     * วันอื่น (เช่น พุธของ MANAGER) ส่งตามปกติ
     */
    private Mono<Map<Long, List<CalibrationRecord>>> excludeAdminsOnAdminDay(Map<Long, List<CalibrationRecord>> byPerson, Role role) {
        if (!isAdminNotifyDay()) return Mono.just(byPerson);

        return template.select(
                        Query.query(Criteria.where("id").in(byPerson.keySet())
                                .and("role_type").in(RoleType.ADMIN.name(), RoleType.DEPARTMENT_ADMIN.name())
                                .and("status").is("ACTIVE")),
                        Member.class)
                .map(Member::getId)
                .collect(Collectors.toSet())
                .map(adminIds -> {
                    if (adminIds.isEmpty()) return byPerson;
                    log.info("[{}] Admin notification day — skip {} admin member(s) here (they get the combined admin message): {}",
                            role, adminIds.size(), adminIds);
                    Map<Long, List<CalibrationRecord>> filtered = new HashMap<>(byPerson);
                    filtered.keySet().removeAll(adminIds);
                    return filtered;
                });
    }

    /** วันนี้ตรงกับ cron ของการแจ้ง admin หรือไม่ */
    private boolean isAdminNotifyDay() {
        try {
            LocalDate today = LocalDate.now(ZONE);
            ZonedDateTime next = CronExpression.parse(adminsCron).next(today.atStartOfDay(ZONE).minusSeconds(1));
            return next != null && next.toLocalDate().equals(today);
        } catch (IllegalArgumentException e) {
            log.warn("[ADMIN] invalid admins cron '{}' — admins will also get role-based messages: {}", adminsCron, e.getMessage());
            return false;
        }
    }

    private RoleType toRoleType(Role role) {
        return switch (role) {
            case ADMIN -> RoleType.ADMIN;
            case DEPARTMENT_ADMIN -> RoleType.DEPARTMENT_ADMIN;
            default -> throw new IllegalArgumentException("Not an admin role: " + role);
        };
    }

    // ======================================================================
    // ส่งข้อความ (Email + Lark)
    // ======================================================================

    private Mono<Void> sendToMembers(Map<Long, List<CalibrationRecord>> rawByPerson, Role role) {

        // เรียงตาม due date ก่อนเสมอ
        Comparator<CalibrationRecord> order = Comparator
                .comparing(CalibrationRecord::getDueDate)
                .thenComparing(CalibrationRecord::getMachineCode, Comparator.nullsLast(Comparator.naturalOrder()));
        Map<Long, List<CalibrationRecord>> byPerson = new HashMap<>();
        rawByPerson.forEach((id, recs) -> byPerson.put(id, recs.stream().sorted(order).toList()));

        Set<Long> memberIds = byPerson.keySet();
        log.info("[SEND][{}] Recipients={}", role, memberIds.size());

        return template.select(
                        Query.query(Criteria.where("id").in(memberIds)),
                        Member.class
                )
                .collectList()
                .flatMap(members -> {
                    if (members.isEmpty()) {
                        log.warn("[SEND][{}] No members found for memberIds={} — skipping", role, memberIds);
                        return Mono.empty();
                    }

                    Set<Long> foundIds = members.stream().map(Member::getId).collect(Collectors.toSet());
                    memberIds.stream()
                            .filter(id -> !foundIds.contains(id))
                            .forEach(id -> log.warn("[SEND][{}] memberId={} not found in member table — skipped", role, id));

                    SendStats stats = new SendStats();
                    LocalDate today = LocalDate.now(ZONE);

                    return resolveResponsibleNames(byPerson, role)
                            .flatMap(responsible -> Mono.when(
                                    sendEmails(members, byPerson, role, today, stats, responsible),
                                    sendLarkMessages(members, byPerson, role, today, stats, responsible)))
                            .doFinally(signal -> log.info(
                                    "[SEND][{}] SUMMARY members={} | email ok={} fail={} skip={} | lark cards ok={} fail={} | lark members skip={}",
                                    role, members.size(),
                                    stats.emailOk.get(), stats.emailFail.get(), stats.emailSkip.get(),
                                    stats.larkOk.get(), stats.larkFail.get(), stats.larkSkip.get()));
                });
    }

    // ---------------------------------------------------------------- Email

    private Mono<Void> sendEmails(List<Member> members,
                                  Map<Long, List<CalibrationRecord>> byPerson,
                                  Role role,
                                  LocalDate today,
                                  SendStats stats,
                                  Map<Long, String> responsible) {
        String subject = "Calibration Due Date Reminder - " + today.format(DateTimeFormatter.ISO_DATE);

        return Flux.fromIterable(members)
                .flatMap(member -> {
                    List<CalibrationRecord> records = byPerson.getOrDefault(member.getId(), List.of());
                    if (records.isEmpty()) return Mono.empty();

                    if (member.getEmail() == null || member.getEmail().isBlank()) {
                        stats.emailSkip.incrementAndGet();
                        log.warn("[SEND][{}] memberId={} has no email — skipped", role, member.getId());
                        return Mono.empty();
                    }

                    String content = buildEmailContent(records, today, responsible);

                    return emailService.sendReminder(member.getEmail(), subject, content)
                            .retryWhen(retrySpec("email memberId=" + member.getId()))
                            .doOnSuccess(v -> {
                                stats.emailOk.incrementAndGet();
                                log.info("[SEND][{}] ✓ Email sent memberId={}, records={}",
                                        role, member.getId(), records.size());
                            })
                            .onErrorResume(e -> {
                                stats.emailFail.incrementAndGet();
                                log.error("[SEND][{}] ✗ Email FAILED memberId={}, email={}: {}",
                                        role, member.getId(), member.getEmail(), rootMessage(e));
                                return Mono.empty();
                            });
                }, SEND_CONCURRENCY)
                .then();
    }

    // ---------------------------------------------------------------- Lark

    private Mono<Void> sendLarkMessages(List<Member> members,
                                        Map<Long, List<CalibrationRecord>> byPerson,
                                        Role role,
                                        LocalDate today,
                                        SendStats stats,
                                        Map<Long, String> responsible) {

        // key = เบอร์แบบตัวเลขล้วน (ใช้เทียบกับผลจาก Lark), เบอร์เดียวอาจมีหลาย member
        Map<String, List<Long>> memberIdsByMobileKey = new LinkedHashMap<>();
        Map<String, String> e164ByMobileKey = new LinkedHashMap<>();

        members.forEach(m -> {
            if (byPerson.getOrDefault(m.getId(), List.of()).isEmpty()) return;

            String e164 = toInternationalFormat(m.getMobiles());
            if (e164 == null) {
                stats.larkSkip.incrementAndGet();
                log.warn("[SEND][{}] memberId={} has no valid mobile — Lark skipped", role, m.getId());
                return;
            }
            String key = mobileKey(e164);
            memberIdsByMobileKey.computeIfAbsent(key, k -> new ArrayList<>()).add(m.getId());
            e164ByMobileKey.putIfAbsent(key, e164);
        });

        if (memberIdsByMobileKey.isEmpty()) {
            log.warn("[SEND][{}] No valid mobiles — skipping Lark", role);
            return Mono.empty();
        }

        // เบอร์ → openId ทีละ batch เรียงกัน; batch ไหนพังก็ไม่กระทบ batch อื่น
        Mono<Map<String, String>> openIdByKeyMono = Flux.fromIterable(new ArrayList<>(e164ByMobileKey.values()))
                .buffer(LarkService.MAX_MOBILES_PER_REQUEST)
                .concatMap(batch -> larkService.batchGetOpenIdsByMobile(batch)
                        .retryWhen(retrySpec("lark openId lookup (" + batch.size() + " mobiles)"))
                        .onErrorResume(e -> {
                            log.error("[SEND][{}] ✗ Lark openId lookup FAILED for {} mobiles: {}",
                                    role, batch.size(), rootMessage(e));
                            return Mono.just(Map.<String, String>of());
                        }))
                .collect(() -> new HashMap<String, String>(),
                        (acc, part) -> part.forEach((mobile, openId) -> {
                            String key = mobileKey(toInternationalFormat(mobile));
                            if (key != null) acc.put(key, openId);
                        }))
                .map(Collections::unmodifiableMap);

        return openIdByKeyMono.flatMap(openIdByKey ->
                Flux.fromIterable(memberIdsByMobileKey.entrySet())
                        .flatMapIterable(entry -> entry.getValue().stream()
                                .map(memberId -> Map.entry(entry.getKey(), memberId))
                                .toList())
                        .flatMap(pair -> {
                            Long memberId = pair.getValue();
                            String openId = openIdByKey.get(pair.getKey());

                            if (openId == null) {
                                stats.larkSkip.incrementAndGet();
                                log.warn("[SEND][{}] memberId={} — mobile not found in Lark "
                                        + "(check app availability scope / mobile in Lark directory) — skipped", role, memberId);
                                return Mono.empty();
                            }
                            return sendLarkCards(openId, memberId, byPerson.get(memberId), role, today, stats, responsible);
                        }, SEND_CONCURRENCY)
                        .then());
    }

    private Mono<Void> sendLarkCards(String openId, Long memberId, List<CalibrationRecord> records,
                                     Role role, LocalDate today, SendStats stats,
                                     Map<Long, String> responsible) {
        List<List<CalibrationRecord>> pages = partition(records, MAX_RECORDS_PER_CARD);
        int totalPages = pages.size();

        return Flux.range(0, totalPages)
                .concatMap(i -> {
                    int page = i + 1;
                    String card = buildLarkCard(pages.get(i), role, page, totalPages, records.size(), today, responsible);
                    // uuid คงที่ต่อ (วัน, role, member, หน้า) → retry หลัง timeout จะไม่ส่งซ้ำ
                    String dedupeKey = idempotencyKey(today, role, memberId, page);

                    return larkService.sendCardMessage(openId, card, dedupeKey)
                            .retryWhen(retrySpec("lark memberId=" + memberId + " card=" + page + "/" + totalPages))
                            .doOnSuccess(v -> {
                                stats.larkOk.incrementAndGet();
                                log.info("[SEND][{}] ✓ Lark sent memberId={}, card={}/{}, records={}",
                                        role, memberId, page, totalPages, pages.get(page - 1).size());
                            })
                            .onErrorResume(e -> {
                                stats.larkFail.incrementAndGet();
                                log.error("[SEND][{}] ✗ Lark FAILED memberId={}, card={}/{}: {}",
                                        role, memberId, page, totalPages, rootMessage(e));
                                return Mono.empty();
                            });
                })
                .then();
    }

    // ======================================================================
    // ผู้รับผิดชอบของแต่ละรายการ (แสดงใน Email / Lark)
    // Calibration: machine.responsible_person_name ของเครื่องนั้น
    // ======================================================================

    /** คืน map: calibration record id → ชื่อผู้รับผิดชอบ */
    private Mono<Map<Long, String>> resolveResponsibleNames(Map<Long, List<CalibrationRecord>> byPerson, Role role) {
        Map<Long, CalibrationRecord> recordsById = distinctRecords(byPerson);
        Set<String> codes = recordsById.values().stream()
                .map(CalibrationRecord::getMachineCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (codes.isEmpty()) return Mono.just(new HashMap<>());

        return template.select(Query.query(Criteria.where("machine_code").in(codes)), Machine.class)
                .filter(m -> m.getMachineCode() != null
                        && m.getResponsiblePersonName() != null && !m.getResponsiblePersonName().isBlank())
                .collectMap(Machine::getMachineCode, m -> m.getResponsiblePersonName().trim())
                .map(nameByCode -> {
                    Map<Long, String> out = new HashMap<>();
                    recordsById.forEach((id, r) -> {
                        String name = nameByCode.get(r.getMachineCode());
                        if (name != null) out.put(id, name);
                    });
                    return out;
                })
                .onErrorResume(e -> {
                    log.warn("[SEND][{}] Cannot load responsible names — sending without them: {}", role, e.getMessage());
                    return Mono.just(new HashMap<>());
                });
    }

    private Map<Long, CalibrationRecord> distinctRecords(Map<Long, List<CalibrationRecord>> byPerson) {
        Map<Long, CalibrationRecord> out = new LinkedHashMap<>();
        byPerson.values().forEach(list -> list.forEach(r -> {
            if (r.getId() != null) out.putIfAbsent(r.getId(), r);
        }));
        return out;
    }

    private String responsibleOf(CalibrationRecord r, Map<Long, String> responsible) {
        if (r.getId() == null) return "-";
        String name = responsible.get(r.getId());
        return (name == null || name.isBlank()) ? "-" : name;
    }

    // ======================================================================
    // Content builders
    // ======================================================================

    private String buildEmailContent(List<CalibrationRecord> records, LocalDate today, Map<Long, String> responsible) {
        StringBuilder html = new StringBuilder();
        html.append("<html><body>")
                .append("<h2>Calibration Due Date Reminder</h2>")
                .append("<p>The following calibrations are due within the next ").append(DUE_WITHIN_DAYS).append(" days:</p>")
                .append("<table border='1' cellpadding='10' cellspacing='0' style='border-collapse: collapse;'>")
                .append("<thead><tr style='background-color: #f2f2f2;'>")
                .append("<th>Machine Code</th><th>Machine Name</th><th>Responsible Person</th><th>Due Date</th><th>Days Remaining</th>")
                .append("</tr></thead><tbody>");

        for (CalibrationRecord r : records) {
            DueStatus s = dueStatus(r.getDueDate(), today);
            html.append("<tr>")
                    .append("<td>").append(escapeHtml(r.getMachineCode())).append("</td>")
                    .append("<td>").append(escapeHtml(r.getMachineName())).append("</td>")
                    .append("<td>").append(escapeHtml(responsibleOf(r, responsible))).append("</td>")
                    .append("<td>").append(r.getDueDate().format(FMT)).append("</td>")
                    .append("<td>").append(s.emoji()).append(' ').append(escapeHtml(s.label())).append("</td>")
                    .append("</tr>");
        }

        html.append("</tbody></table><br>")
                .append("<p><strong>Note:</strong>")
                .append(" 🔴 Overdue or due within ").append(RED_WITHIN_DAYS).append(" days &nbsp;")
                .append(" 🟡 Due within ").append(YELLOW_WITHIN_DAYS).append(" days &nbsp;")
                .append(" 🟢 Due within ").append(DUE_WITHIN_DAYS).append(" days</p>")
                .append("<p>Please ensure these calibrations are completed on time.</p>")
                .append("</body></html>");
        return html.toString();
    }

    private String buildLarkCard(List<CalibrationRecord> records, Role role,
                                 int page, int totalPages, int totalCount, LocalDate today,
                                 Map<Long, String> responsible) {

        String title = totalPages > 1
                ? role.cardTitle + " (" + page + "/" + totalPages + ")"
                : role.cardTitle;

        String summary = "📅 วันที่: " + today.format(FMT) + " | 🔔 รายการที่ต้องดำเนินการ " + totalCount + " รายการ";

        List<String> rows = new ArrayList<>();
        for (CalibrationRecord r : records) {
            DueStatus s = dueStatus(r.getDueDate(), today);
            String content = "**" + nullToDash(r.getMachineCode()) + " - " + nullToDash(r.getMachineName()) + "**\n"
                    + "👤 ผู้รับผิดชอบ: " + responsibleOf(r, responsible) + "\n"
                    + "<font color='" + s.color() + "'>Due: " + r.getDueDate().format(FMT) + " (" + s.label() + ")</font>";
            rows.add("{\"tag\":\"column_set\",\"flex_mode\":\"none\",\"background_style\":\"grey\","
                    + "\"columns\":[{\"tag\":\"column\",\"width\":\"weighted\",\"weight\":1,"
                    + "\"elements\":[" + markdownElement(content) + "]}]}");
        }

        String legend = "<font color='red'>🔴 Overdue/≤" + RED_WITHIN_DAYS + "วัน</font>  "
                + "<font color='yellow'>🟡 ≤" + YELLOW_WITHIN_DAYS + "วัน</font>  "
                + "<font color='green'>🟢 ≤" + DUE_WITHIN_DAYS + "วัน</font>";

        return "{"
                + "\"config\":{\"wide_screen_mode\":true},"
                + "\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"" + escapeJson(title) + "\"},\"template\":\"blue\"},"
                + "\"elements\":["
                + markdownElement(summary) + ","
                + "{\"tag\":\"hr\"},"
                + String.join(",", rows) + ","
                + "{\"tag\":\"hr\"},"
                + markdownElement(legend)
                + "]}";
    }

    private String markdownElement(String content) {
        return "{\"tag\":\"markdown\",\"content\":\"" + escapeJson(content) + "\"}";
    }

    private record DueStatus(String color, String emoji, String label) {}

    private DueStatus dueStatus(LocalDate dueDate, LocalDate today) {
        long daysLeft = ChronoUnit.DAYS.between(today, dueDate);
        if (daysLeft < 0) return new DueStatus("red", "🔴", "เลยกำหนดมา " + Math.abs(daysLeft) + " วัน");

        String label = daysLeft == 0 ? "ครบกำหนดวันนี้" : "อีก " + daysLeft + " วัน";
        if (daysLeft <= RED_WITHIN_DAYS) return new DueStatus("red", "🔴", label);
        if (daysLeft <= YELLOW_WITHIN_DAYS) return new DueStatus("yellow", "🟡", label);
        return new DueStatus("green", "🟢", label);
    }

    // ======================================================================
    // Retry / error helpers
    // ======================================================================

    private RetryBackoffSpec retrySpec(String what) {
        return Retry.backoff(MAX_RETRIES, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(10))
                .filter(this::isRetryable)
                .doBeforeRetry(s -> log.warn("[RETRY] {} attempt={}/{} cause={}",
                        what, s.totalRetries() + 1, MAX_RETRIES, s.failure().getMessage()));
    }

    /** error ถาวร (เช่น อีเมลผิดรูปแบบ, input ไม่ถูกต้อง) ไม่ต้อง retry ให้เสียเวลา */
    private boolean isRetryable(Throwable e) {
        if (e instanceof EmailService.EmailSendException ex) return ex.isRetryable();
        return !(e instanceof IllegalArgumentException);
    }

    /** retry ครบแล้วจะได้ RetryExhaustedException — ดึงสาเหตุจริงออกมา log */
    private String rootMessage(Throwable e) {
        Throwable t = (Exceptions.isRetryExhausted(e) && e.getCause() != null) ? e.getCause() : e;
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static final class SendStats {
        final AtomicInteger emailOk = new AtomicInteger();
        final AtomicInteger emailFail = new AtomicInteger();
        final AtomicInteger emailSkip = new AtomicInteger();
        final AtomicInteger larkOk = new AtomicInteger();
        final AtomicInteger larkFail = new AtomicInteger();
        final AtomicInteger larkSkip = new AtomicInteger();
    }

    // ======================================================================
    // Utils
    // ======================================================================

    private String idempotencyKey(LocalDate date, Role role, Long memberId, int page) {
        String raw = "calibration|" + date + "|" + role + "|" + memberId + "|" + page;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> pages = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            pages.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return pages;
    }

    private String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }

    /** escape ครบตามสเปก JSON (รวม newline / tab / control chars ที่มักติดมาจาก Excel) */
    private String escapeJson(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "-";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * แปลงเบอร์เป็นรูปแบบสากล เช่น 081-234-5678 → +66812345678
     * ถ้ามีหลายเบอร์ในช่องเดียว (คั่นด้วย , ; /) ใช้เบอร์แรก
     */
    private String toInternationalFormat(String raw) {
        if (raw == null) return null;

        String first = Arrays.stream(raw.split("[,;/]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
        if (first == null) return null;

        String s = first.replaceAll("[\\s\\-().]", "");
        if (s.startsWith("00")) s = "+" + s.substring(2);
        else if (s.startsWith("0")) s = "+66" + s.substring(1);
        else if (s.startsWith("66")) s = "+" + s;

        return s.matches("\\+?\\d{8,15}") ? s : null;
    }

    private String mobileKey(String e164) {
        return e164 == null ? null : e164.replaceAll("\\D", "");
    }

    private enum Role {
        RESPONSIBLE_SUPERVISOR("Calibration Reminder"),
        MANAGER("Calibration Reminder (Manager)"),
        DEPARTMENT_ADMIN("Calibration Reminder (Department Admin)"),
        ADMIN("Calibration Reminder (Admin)");

        private final String cardTitle;

        Role(String cardTitle) {
            this.cardTitle = cardTitle;
        }
    }
}