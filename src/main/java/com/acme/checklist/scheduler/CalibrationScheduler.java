package com.acme.checklist.scheduler;

import com.acme.checklist.entity.CalibrationRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Member;
import com.acme.checklist.service.EmailService;
import com.acme.checklist.service.LarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class CalibrationScheduler {

    private final LarkService larkService;
    private final EmailService emailService;
    private final R2dbcEntityTemplate template;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Scheduled(cron = "0 0 0 20 12 *", zone = "Asia/Bangkok")
    public void createNextYearCalibrationRecords() {
        int currentYear = LocalDate.now().getYear();
        int nextYear = currentYear + 1;

        template.getDatabaseClient()
                .sql("SELECT * FROM calibration_record WHERE years = $1")
                .bind(0, String.valueOf(currentYear))
                .map((row, metadata) -> {
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
                    r.setDueDate(dueDate != null ? dueDate.withYear(nextYear) : null);
                    r.setYears(nextYear);
                    return r;
                })
                .all()
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) {
                        log.info("[COPY] No calibration records found for year={} — skipping", currentYear);
                        return Mono.empty();
                    }

                    return template.getDatabaseClient()
                            .sql("SELECT COUNT(*) as cnt FROM calibration_record WHERE years = $1")
                            .bind(0, String.valueOf(nextYear))
                            .map((row, metadata) -> {
                                Long cnt = row.get("cnt", Long.class);
                                return cnt != null ? cnt : 0L;
                            })
                            .one()
                            .flatMap(count -> {
                                if (count > 0) {
                                    log.warn("[COPY] Records for year={} already exist ({} records) — skipping", nextYear, count);
                                    return Mono.empty();
                                }

                                log.info("[COPY] Creating {} calibration records for year={}", records.size(), nextYear);

                                return Flux.fromIterable(records)
                                        .concatMap(r -> {
                                            DatabaseClient.GenericExecuteSpec spec = template.getDatabaseClient()
                                                    .sql("""
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
                                                    .doOnSuccess(v -> log.info("[COPY] ✓ Inserted machineCode={}, year={}", r.getMachineCode(), nextYear))
                                                    .doOnError(e -> log.error("[COPY] ✗ Insert failed machineCode={}: {}", r.getMachineCode(), e.getMessage()));
                                        })
                                        .then();
                            });
                })
                .onErrorResume(e -> {
                    log.error("[COPY] Failed to create next year calibration records: {}", e.getMessage(), e);
                    return Mono.empty();
                })
                .subscribe();
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, int index, T value, Class<T> type) {
        return value != null ? spec.bind(index, value) : spec.bindNull(index, type);
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Bangkok")
    public void sendDailyToResponsibleAndSupervisor() {
        fetchDueCalibrations()
                .collectList()
                .flatMap(calibrations -> {
                    if (calibrations.isEmpty()) return Mono.empty();
                    return fetchMachinesAndGroupByRole(calibrations, Role.RESPONSIBLE_SUPERVISOR);
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    @Scheduled(cron = "0 5 9 * * MON,WED", zone = "Asia/Bangkok")
    public void sendWeeklyToManager() {
        fetchDueCalibrations()
                .collectList()
                .flatMap(calibrations -> {
                    if (calibrations.isEmpty()) return Mono.empty();
                    return fetchMachinesAndGroupByRole(calibrations, Role.MANAGER);
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private Flux<CalibrationRecord> fetchDueCalibrations() {
        LocalDate today = LocalDate.now();
        LocalDate in30Days = today.plusDays(30);

        return template.select(
                Query.query(
                        Criteria.where("due_date").lessThanOrEquals(in30Days)
                                .and("start_date").isNull()
                ).sort(Sort.by("due_date").ascending()),
                CalibrationRecord.class
        ).doOnNext(c -> log.info("[FETCH] id={}, machineCode={}, machine_name={}, dueDate={}, startDate={}",
                c.getId(), c.getMachineCode(), c.getMachineName(), c.getDueDate(), c.getStartDate()));
    }

    private Mono<Void> fetchMachinesAndGroupByRole(List<CalibrationRecord> calibrations, Role role) {

        Map<String, List<CalibrationRecord>> byMachineCode = calibrations.stream()
                .filter(c -> c.getMachineCode() != null)
                .collect(Collectors.groupingBy(CalibrationRecord::getMachineCode));

        return template.select(
                        Query.query(Criteria.where("machine_code").in(byMachineCode.keySet())),
                        Machine.class
                )
                .collectList()
                .flatMap(machines -> {

                    Map<Long, Map<Long, CalibrationRecord>> byPersonMap = new HashMap<>();

                    machines.forEach(machine -> {
                        List<CalibrationRecord> records = byMachineCode.getOrDefault(
                                machine.getMachineCode(), List.of()
                        );
                        if (records.isEmpty()) return;

                        List<Long> personIds = switch (role) {
                            case RESPONSIBLE_SUPERVISOR -> Stream.of(
                                            machine.getResponsiblePersonId(),
                                            machine.getSupervisorId()
                                    )
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .toList();

                            case MANAGER -> Stream.of(machine.getManagerId())
                                    .filter(Objects::nonNull)
                                    .toList();
                        };

                        personIds.forEach(personId -> {
                            Map<Long, CalibrationRecord> existing = byPersonMap.computeIfAbsent(
                                    personId, k -> new LinkedHashMap<>()
                            );
                            records.forEach(r -> existing.put(r.getId(), r));
                        });
                    });

                    Map<Long, List<CalibrationRecord>> byPerson = byPersonMap.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> new ArrayList<>(e.getValue().values())
                            ));

                    byPerson.forEach((id, records) ->
                            log.info("  → personId={}, records={}", id, records.size())
                    );

                    if (byPerson.isEmpty()) return Mono.empty();

                    return fetchMembersAndSendByMemberId(byPerson, role);
                });
    }

    private Mono<Void> fetchMembersAndSendByMemberId(Map<Long, List<CalibrationRecord>> byPerson, Role role) {

        Set<Long> memberIds = byPerson.keySet();

        return template.select(
                        Query.query(Criteria.where("id").in(memberIds)),
                        Member.class
                )
                .collectList()
                .flatMap(members -> {
                    if (members.isEmpty()) {
                        log.warn("[SEND] No members found for memberIds: {} — skipping", memberIds);
                        return Mono.empty();
                    }

                    // --- Email ---
                    Mono<Void> emailMono = Flux.fromIterable(members)
                            .filter(m -> m.getEmail() != null && !m.getEmail().isBlank())
                            .flatMap(member -> {
                                List<CalibrationRecord> empRecords = byPerson.getOrDefault(
                                        member.getId(), List.of()
                                );
                                if (empRecords.isEmpty()) return Mono.empty();

                                String subject = "Calibration Due Date Reminder - "
                                        + LocalDate.now().format(DateTimeFormatter.ISO_DATE);
                                String emailContent = buildEmailContent(empRecords);

                                log.info("[SEND] → Email to memberId={}, email={}, records={}",
                                        member.getId(), member.getEmail(), empRecords.size());

                                return emailService.sendReminder(member.getEmail(), subject, emailContent)
                                        .doOnSuccess(v -> log.info("[SEND] ✓ Email sent to memberId={}", member.getId()))
                                        .onErrorResume(e -> {
                                            log.warn("[SEND] ✗ Email failed memberId={}: {}", member.getId(), e.getMessage());
                                            return Mono.empty();
                                        });
                            })
                            .then();

                    // --- Lark ---
                    Map<String, Long> mobileToMemberId = new HashMap<>();
                    members.stream()
                            .filter(m -> m.getMobiles() != null && !m.getMobiles().isBlank())
                            .forEach(m -> mobileToMemberId.put(toInternationalFormat(m.getMobiles()), m.getId()));

                    Mono<Void> larkMono = mobileToMemberId.isEmpty()
                            ? Mono.fromRunnable(() -> log.warn("[SEND] No valid mobiles — skipping Lark"))
                            : larkService.batchGetOpenIdsByMobile(new ArrayList<>(mobileToMemberId.keySet()))
                            .flatMap(openIdMap -> {
                                if (openIdMap.isEmpty()) return Mono.empty();

                                return Flux.fromIterable(mobileToMemberId.entrySet())
                                        .flatMap(entry -> {
                                            String mobile = entry.getKey();
                                            Long memberId = entry.getValue();
                                            String openId = openIdMap.get(mobile);

                                            if (openId == null) return Mono.empty();

                                            List<CalibrationRecord> empRecords = byPerson.getOrDefault(memberId, List.of());
                                            if (empRecords.isEmpty()) return Mono.empty();

                                            String message = buildMessage(empRecords, role);
                                            return larkService.sendCardMessage(openId, message)
                                                    .doOnSuccess(v -> log.info("[SEND] ✓ Lark sent to memberId={}", memberId))
                                                    .onErrorResume(e -> Mono.empty());
                                        })
                                        .then();
                            });

                    return Mono.when(emailMono, larkMono);
                });
    }

    private String buildEmailContent(List<CalibrationRecord> calibrations) {
        StringBuilder content = new StringBuilder();
        content.append("<html><body>");
        content.append("<h2>Calibration Due Date Reminder</h2>");
        content.append("<p>The following calibrations are due within the next 30 days:</p>");
        content.append("<table border='1' cellpadding='10' cellspacing='0' style='border-collapse: collapse;'>");
        content.append("<thead>");
        content.append("<tr style='background-color: #f2f2f2;'>");
        content.append("<th>Machine Code</th>");
        content.append("<th>Machine Name</th>");
        content.append("<th>Due Date</th>");
        content.append("<th>Days Remaining</th>");
        content.append("</tr>");
        content.append("</thead>");
        content.append("<tbody>");

        LocalDate today = LocalDate.now();

        for (CalibrationRecord r : calibrations) {
            long daysRemaining = ChronoUnit.DAYS.between(today, r.getDueDate());

            String statusLabel;
            if (daysRemaining < 0) {
                statusLabel = "🔴 เลยกำหนดมา " + Math.abs(daysRemaining) + " วัน";
            } else if (daysRemaining <= 7) {
                statusLabel = "🔴 อีก " + daysRemaining + " วัน";
            } else if (daysRemaining <= 14) {
                statusLabel = "🟡 อีก " + daysRemaining + " วัน";
            } else {
                statusLabel = "🟢 อีก " + daysRemaining + " วัน";
            }

            content.append("<tr>");
            content.append("<td>").append(escapeHtml(r.getMachineCode())).append("</td>");
            content.append("<td>").append(escapeHtml(r.getMachineName())).append("</td>");
            content.append("<td>").append(r.getDueDate().format(FMT)).append("</td>");
            content.append("<td>").append(statusLabel).append("</td>");
            content.append("</tr>");
        }

        content.append("</tbody>");
        content.append("</table>");
        content.append("<br>");
        content.append("<p><strong>Note:</strong>");
        content.append(" <span style='padding:2px 6px;'>🔴</span> Overdue or due within 7 days &nbsp;");
        content.append(" <span style='padding:2px 6px;'>🟡</span> Due within 14 days &nbsp;");
        content.append(" <span style='padding:2px 6px;'>🟢</span> Due within 30 days</p>");
        content.append("<p>Please ensure these calibrations are completed on time.</p>");
        content.append("</body></html>");

        return content.toString();
    }

    private String buildMessage(List<CalibrationRecord> records, Role role) {
        LocalDate today = LocalDate.now();

        String header = switch (role) {
            case RESPONSIBLE_SUPERVISOR -> "Calibration Reminder";
            case MANAGER -> "Calibration Reminder (Manager)";
        };

        StringBuilder elements = new StringBuilder();

        records.forEach(r -> {
            long daysLeft = ChronoUnit.DAYS.between(today, r.getDueDate());

            String color;
            String daysLabel;

            if (daysLeft < 0) {
                color = "red";
                daysLabel = "เลยกำหนดมา " + Math.abs(daysLeft) + " วัน";
            } else if (daysLeft <= 7) {
                color = "red";
                daysLabel = "อีก " + daysLeft + " วัน";
            } else if (daysLeft <= 14) {
                color = "yellow";
                daysLabel = "อีก " + daysLeft + " วัน";
            } else {
                color = "green";
                daysLabel = "อีก " + daysLeft + " วัน";
            }

            elements.append("""
                    {
                           "tag": "column_set",
                           "flex_mode": "none",
                           "background_style": "grey",
                           "columns": [
                               {
                                   "tag": "column",
                                   "width": "weighted",
                                   "weight": 1,
                                   "elements": [
                                       {
                                           "tag": "markdown",
                                           "content": "**%s - %s**\\n<font color='%s'>Due: %s (%s)</font>"
                                       }
                                   ]
                               }
                           ]
                       },
               """.formatted(
                    escapeJson(r.getMachineCode()),
                    escapeJson(r.getMachineName()),
                    color,
                    r.getDueDate().format(FMT),
                    daysLabel
            ));
        });

        String elementsStr = elements.toString().trim();
        if (elementsStr.endsWith(",")) {
            elementsStr = elementsStr.substring(0, elementsStr.length() - 1);
        }

        return """
                {
                  "config": { "wide_screen_mode": true },
                  "header": {
                    "title": { "tag": "plain_text", "content": "%s" },
                    "template": "blue"
                  },
                  "elements": [
                    {
                      "tag": "markdown",
                      "content": "📅 วันที่: %s | 🔔 รายการที่ต้องดำเนินการ %d รายการ"
                    },
                    { "tag": "hr" },
                    %s,
                    { "tag": "hr" },
                    {
                      "tag": "markdown",
                      "content": "<font color='red'>🔴 Overdue/≤7วัน</font>  <font color='yellow'>🟡 ≤14วัน</font>  <font color='green'>🟢 ≤30วัน</font>"
                    }
                  ]
                }
                """.formatted(
                escapeJson(header),
                today.format(FMT),
                records.size(),
                elementsStr
        );
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String escapeHtml(String text) {
        if (text == null) return "-";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String toInternationalFormat(String mobile) {
        if (mobile.startsWith("0")) return "+66" + mobile.substring(1);
        if (mobile.startsWith("66") && !mobile.startsWith("+66")) return "+" + mobile;
        return mobile;
    }

    private enum Role {
        RESPONSIBLE_SUPERVISOR,
        MANAGER
    }
}