package com.acme.checklist.service;

import com.acme.checklist.entity.Department;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.RegisterRequest;
import com.lark.oapi.Client;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserReq;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserReqBody;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserResp;
import com.lark.oapi.service.im.v1.model.*;
import com.lark.oapi.service.bitable.v1.model.*;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class LarkService {

    /** จำนวนเบอร์สูงสุดต่อ 1 request ของ contact batchGetId */
    public static final int MAX_MOBILES_PER_REQUEST = 50;

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);

    @Value("${lark.app-id}")
    private String appId;

    @Value("${lark.app-secret}")
    private String appSecret;

    @Value("${lark.base-app-token}")
    private String appToken;

    @Value("${lark.table-id}")
    private String tableId;

    @Value("${lark.department-table-id}")
    private String departmentTableId;

    private Client client;

    @PostConstruct
    public void init() {
        this.client = Client.newBuilder(appId, appSecret).build();
    }

    // ======================================================================
    // Messaging
    // ======================================================================

    /**
     * แปลงเบอร์โทร → open_id
     * คืน map ของ (เบอร์ตามที่ Lark ส่งกลับ → open_id) เฉพาะเบอร์ที่หาเจอ
     * เบอร์ที่ไม่อยู่ใน Lark directory หรืออยู่นอก availability scope ของแอปจะไม่อยู่ใน map
     */
    public Mono<Map<String, String>> batchGetOpenIdsByMobile(List<String> mobiles) {
        if (mobiles == null || mobiles.isEmpty()) {
            return Mono.just(Map.of());
        }

        // เกิน limit → แบ่งเป็นหลาย request เรียงกัน
        if (mobiles.size() > MAX_MOBILES_PER_REQUEST) {
            return Flux.fromIterable(mobiles)
                    .buffer(MAX_MOBILES_PER_REQUEST)
                    .concatMap(this::batchGetOpenIdsByMobile)
                    .collect(() -> new HashMap<String, String>(), Map::putAll)
                    .map(Collections::unmodifiableMap);
        }

        return Mono.fromCallable(() -> {
                    BatchGetIdUserReq req = BatchGetIdUserReq.newBuilder()
                            .userIdType("open_id")
                            .batchGetIdUserReqBody(BatchGetIdUserReqBody.newBuilder()
                                    .mobiles(mobiles.toArray(new String[0]))
                                    .build())
                            .build();

                    BatchGetIdUserResp resp = client.contact().user()
                            .batchGetId(req, RequestOptions.newBuilder().build());

                    if (!resp.success()) {
                        throw new LarkApiException("contact.user.batchGetId",
                                resp.getCode(), resp.getMsg(), resp.getRequestId());
                    }

                    Map<String, String> result = new HashMap<>();
                    if (resp.getData() != null && resp.getData().getUserList() != null) {
                        Arrays.stream(resp.getData().getUserList()).forEach(user -> {
                            if (user.getMobile() != null && user.getUserId() != null) {
                                result.put(user.getMobile(), user.getUserId());
                            } else {
                                log.debug("[LARK] mobile={} not resolved", maskMobile(user.getMobile()));
                            }
                        });
                    }

                    if (result.size() < mobiles.size()) {
                        log.warn("[LARK] batchGetId resolved {}/{} mobiles — the rest are not in Lark "
                                + "or outside the app's availability scope", result.size(), mobiles.size());
                    } else {
                        log.info("[LARK] batchGetId resolved {}/{} mobiles", result.size(), mobiles.size());
                    }
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> sendCardMessage(String openId, String cardJson) {
        return sendCardMessage(openId, cardJson, null);
    }

    /**
     * ส่งการ์ดข้อความ
     * @param uuid idempotency key (≤50 ตัวอักษร) — ส่ง uuid เดิมซ้ำภายใน 1 ชม. Lark จะไม่ส่งข้อความซ้ำ
     *             ช่วยกันข้อความเบิ้ลตอน retry หลัง timeout; ส่ง null ได้ถ้าไม่ต้องการ
     */
    public Mono<Void> sendCardMessage(String openId, String cardJson, String uuid) {
        return Mono.fromCallable(() -> {
                    if (openId == null || openId.isBlank()) {
                        throw new IllegalArgumentException("openId is blank");
                    }
                    if (cardJson == null || cardJson.isBlank()) {
                        throw new IllegalArgumentException("cardJson is blank");
                    }

                    CreateMessageReqBody.Builder body = CreateMessageReqBody.newBuilder()
                            .receiveId(openId)
                            .msgType("interactive")
                            .content(cardJson);
                    if (uuid != null && !uuid.isBlank()) {
                        body.uuid(uuid);
                    }

                    CreateMessageReq req = CreateMessageReq.newBuilder()
                            .receiveIdType("open_id")
                            .createMessageReqBody(body.build())
                            .build();

                    CreateMessageResp resp = client.im().message().create(req);

                    if (!resp.success()) {
                        throw new LarkApiException("im.message.create",
                                resp.getCode(), resp.getMsg(), resp.getRequestId());
                    }
                    return resp;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // ── Machine notification ─────────────────────────────────────────────

    public Mono<Void> sendMachineNotification(String openId, Machine machine) {
        return sendCardMessage(openId, buildMachineCardJson(machine));
    }

    private String buildMachineCardJson(Machine machine) {
        String machineCode     = escapeJson(orDash(machine.getMachineCode()));
        String machineName     = escapeJson(orDash(machine.getMachineName()));
        String responsibleName = escapeJson(orDash(machine.getResponsiblePersonName()));

        return "{"
                + "\"config\":{\"wide_screen_mode\":true},"
                + "\"header\":{"
                +   "\"title\":{\"tag\":\"plain_text\",\"content\":\"เพิ่มเครื่องจักรใหม่\"},"
                +   "\"template\":\"blue\""
                + "},"
                + "\"elements\":["
                +   "{\"tag\":\"div\",\"fields\":["
                +     "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**รหัสเครื่องจักร**\\n" + machineCode + "\"}},"
                +     "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**ชื่อเครื่องจักร**\\n" + machineName + "\"}}"
                +   "]},"
                +   "{\"tag\":\"div\",\"fields\":["
                +     "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**ผู้รับผิดชอบ**\\n" + responsibleName + "\"}}"
                +   "]}"
                + "]}";
    }

    // ── Register rejected notification ───────────────────────────────────

    /**
     * แจ้งผู้ลงทะเบียนว่าการลงทะเบียนถูกปฏิเสธ
     * ใช้ uuid "register-reject-{id}" กันข้อความเบิ้ลตอน retry
     */
    public Mono<Void> sendRegisterRejectedNotification(String openId, RegisterRequest reg,
                                                       String rejectedByName, Instant rejectedAt) {
        return sendCardMessage(openId,
                buildRegisterRejectedCardJson(reg, rejectedByName, rejectedAt),
                "register-reject-" + reg.getId());
    }

    private String buildRegisterRejectedCardJson(RegisterRequest reg, String rejectedByName, Instant rejectedAt) {
        String machineName  = escapeJson(orDash(reg.getMachineName()));
        String serialNumber = escapeJson(orDash(reg.getSerialNumber()));
        String rejectedBy   = escapeJson(orDash(rejectedByName));
        String rejectedDate = rejectedAt != null ? DATE_TIME_FMT.format(rejectedAt) : "-";
        String reason       = escapeJson(orDash(reg.getRejectReason()));

        return "{"
                + "\"config\":{\"wide_screen_mode\":true},"
                + "\"header\":{"
                +   "\"title\":{\"tag\":\"plain_text\",\"content\":\"❌ การลงทะเบียนเครื่องจักรถูกปฏิเสธ\"},"
                +   "\"template\":\"red\""
                + "},"
                + "\"elements\":["
                +   "{\"tag\":\"div\",\"fields\":["
                +     "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**ชื่อเครื่องจักร**\\n" + machineName + "\"}},"
                +     "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**Serial No**\\n" + serialNumber + "\"}}"
                +   "]},"
                +   "{\"tag\":\"div\",\"fields\":["
                +     "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**ปฏิเสธโดย**\\n" + rejectedBy + "\"}},"
                +     "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**วันที่ปฏิเสธ**\\n" + rejectedDate + "\"}}"
                +   "]},"
                +   "{\"tag\":\"hr\"},"
                +   "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**เหตุผล**\\n" + reason + "\"}}"
                + "]}";
    }

    // ======================================================================
    // Machine Bitable
    // ======================================================================

    public void updateRecord(String recordId, Map<String, Object> fields) throws Exception {
        UpdateAppTableRecordReq req = UpdateAppTableRecordReq.newBuilder()
                .appToken(appToken)
                .tableId(tableId)
                .recordId(recordId)
                .appTableRecord(AppTableRecord.newBuilder().fields(fields).build())
                .build();

        UpdateAppTableRecordResp resp = client.bitable().appTableRecord().update(req);
        if (!resp.success()) {
            throw new LarkApiException("bitable.machine.update", resp.getCode(), resp.getMsg(), resp.getRequestId());
        }
    }

    public Mono<Void> upsertMachineRecord(Machine machine) {
        return Mono.fromCallable(() -> {
                    log.info("[LARK][BASE] upsert machine id={} code={}", machine.getId(), machine.getMachineCode());

                    Map<String, Object> fields = new HashMap<>();
                    fields.put("app id",            String.valueOf(machine.getId()));
                    fields.put("รหัสเครื่องจักร",  nullSafe(machine.getMachineCode()));
                    fields.put("ชื่อเครื่องจักร",  nullSafe(machine.getMachineName()));
                    fields.put("กลุ่ม",            nullSafe(machine.getMachineGroupId()));
                    fields.put("ประเภท",           nullSafe(machine.getMachineTypeId()));
                    fields.put("แบรนด์",           nullSafe(machine.getBrand()));
                    fields.put("รุ่น",             nullSafe(machine.getModel()));
                    fields.put("หมายเลขซีเรียล",  nullSafe(machine.getSerialNumber()));
                    fields.put("หน่วยธุรกิจ",      nullSafe(machine.getBusinessUnit()));
                    fields.put("แผนก",             nullSafe(machine.getDepartment()));
                    fields.put("สถานะเครื่องจักร", nullSafe(machine.getMachineStatus()));
                    fields.put("สถานะการตรวจสอบ",  nullSafe(machine.getCheckStatus()));
                    fields.put("ผู้รับผิดชอบ",      nullSafe(machine.getResponsiblePersonName()));
                    fields.put("หมายเหตุ",         nullSafe(machine.getNote()));
                    if (machine.getRegisterDate() != null) {
                        long timestamp = machine.getRegisterDate()
                                .atStartOfDay(ZONE)
                                .toInstant()
                                .toEpochMilli();
                        fields.put("วันที่ลงทะเบียน", timestamp);
                    }

                    String recordId = findRecordIdByMachineId(String.valueOf(machine.getId()));

                    if (recordId != null) {
                        updateRecord(recordId, fields);
                        log.info("[LARK][BASE] Updated machine {} (id={})", machine.getMachineCode(), machine.getId());
                    } else {
                        CreateAppTableRecordReq req = CreateAppTableRecordReq.newBuilder()
                                .appToken(appToken)
                                .tableId(tableId)
                                .appTableRecord(AppTableRecord.newBuilder().fields(fields).build())
                                .build();
                        CreateAppTableRecordResp resp = client.bitable().appTableRecord().create(req);
                        if (!resp.success()) {
                            // เดิม log แล้ว return เงียบ → ตอนนี้ throw ให้ caller รู้ว่าไม่สำเร็จ
                            throw new LarkApiException("bitable.machine.create",
                                    resp.getCode(), resp.getMsg(), resp.getRequestId());
                        }
                        log.info("[LARK][BASE] Created machine {} (id={})", machine.getMachineCode(), machine.getId());
                    }
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public String findRecordIdByMachineId(String machineId) throws Exception {
        return findRecordId(tableId, "app id", machineId, "bitable.machine.search");
    }

    // ======================================================================
    // Department Bitable
    // ======================================================================

    public Mono<Void> upsertDepartmentRecord(Department department) {
        return Mono.fromCallable(() -> {
                    log.info("[LARK][BASE] upsert department id={} name={}", department.getId(), department.getDepartment());

                    Map<String, Object> fields = new HashMap<>();
                    fields.put("id",             String.valueOf(department.getId()));
                    fields.put("businessUnit",   nullSafe(department.getBusinessUnit()));
                    fields.put("department",     nullSafe(department.getDepartment()));
                    fields.put("departmentCode", nullSafe(department.getDepartmentCode()));
                    fields.put("division",       nullSafe(department.getDivision()));
                    fields.put("status",         nullSafe(department.getStatus()));

                    String recordId = findRecordIdByDepartmentId(String.valueOf(department.getId()));

                    if (recordId != null) {
                        updateDepartmentRecord(recordId, fields);
                        log.info("[LARK][BASE] Updated department {} (id={})", department.getDepartment(), department.getId());
                    } else {
                        CreateAppTableRecordReq req = CreateAppTableRecordReq.newBuilder()
                                .appToken(appToken)
                                .tableId(departmentTableId)
                                .appTableRecord(AppTableRecord.newBuilder().fields(fields).build())
                                .build();
                        CreateAppTableRecordResp resp = client.bitable().appTableRecord().create(req);
                        if (!resp.success()) {
                            throw new LarkApiException("bitable.department.create",
                                    resp.getCode(), resp.getMsg(), resp.getRequestId());
                        }
                        log.info("[LARK][BASE] Created department {} (id={})", department.getDepartment(), department.getId());
                    }
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public String findRecordIdByDepartmentId(String departmentId) throws Exception {
        return findRecordId(departmentTableId, "id", departmentId, "bitable.department.search");
    }

    public void updateDepartmentRecord(String recordId, Map<String, Object> fields) throws Exception {
        UpdateAppTableRecordReq req = UpdateAppTableRecordReq.newBuilder()
                .appToken(appToken)
                .tableId(departmentTableId)
                .recordId(recordId)
                .appTableRecord(AppTableRecord.newBuilder().fields(fields).build())
                .build();

        UpdateAppTableRecordResp resp = client.bitable().appTableRecord().update(req);
        if (!resp.success()) {
            throw new LarkApiException("bitable.department.update", resp.getCode(), resp.getMsg(), resp.getRequestId());
        }
    }

    // ======================================================================
    // Shared Bitable helper
    // ======================================================================

    private String findRecordId(String table, String fieldName, String value, String operation) throws Exception {
        SearchAppTableRecordReq req = SearchAppTableRecordReq.newBuilder()
                .appToken(appToken)
                .tableId(table)
                .searchAppTableRecordReqBody(SearchAppTableRecordReqBody.newBuilder()
                        .fieldNames(new String[]{fieldName})
                        .filter(FilterInfo.newBuilder()
                                .conjunction("and")
                                .conditions(new Condition[]{
                                        Condition.newBuilder()
                                                .fieldName(fieldName)
                                                .operator("is")
                                                .value(new String[]{value})
                                                .build()
                                })
                                .build())
                        .build())
                .build();

        SearchAppTableRecordResp resp = client.bitable().appTableRecord().search(req);
        if (!resp.success()) {
            throw new LarkApiException(operation, resp.getCode(), resp.getMsg(), resp.getRequestId());
        }

        if (resp.getData() == null) return null;
        AppTableRecord[] items = resp.getData().getItems();
        if (items == null || items.length == 0) return null;
        if (items.length > 1) {
            log.warn("[LARK][BASE] {} found {} records for {}={} — using the first one",
                    operation, items.length, fieldName, value);
        }
        return items[0].getRecordId();
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    private String orDash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }

    /** escape ครบตามสเปก JSON (รวม newline / tab / control chars) */
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

    /** +66812345678 → +66****678 (ไม่ให้เบอร์เต็มหลุดลง log) */
    private String maskMobile(String mobile) {
        if (mobile == null) return "null";
        if (mobile.length() <= 6) return "***";
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 3);
    }

    // ======================================================================
    // Exception
    // ======================================================================

    /** error จาก Lark API พร้อม code และ requestId (ใช้แจ้ง Lark support ได้) */
    @Getter
    public static class LarkApiException extends RuntimeException {
        private final String operation;
        private final int code;
        private final String requestId;

        public LarkApiException(String operation, int code, String msg, String requestId) {
            super("Lark " + operation + " failed: code=" + code + ", msg=" + msg + ", requestId=" + requestId);
            this.operation = operation;
            this.code = code;
            this.requestId = requestId;
        }
    }
}