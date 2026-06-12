package com.acme.checklist.service;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
public class LarkService {

    @Value("${lark.app-id}")
    private String appId;

    @Value("${lark.app-secret}")
    private String appSecret;

    private Client client;

    @Value("${lark.base-app-token}")
    private String appToken;

    @Value("${lark.table-id}")
    private String tableId;

    @PostConstruct
    public void init() {
        this.client = Client.newBuilder(appId, appSecret).build();
    }

    public Mono<Map<String, String>> batchGetOpenIdsByMobile(List<String> mobiles) {
        return Mono.fromCallable(() -> {
                    log.info("=== Lark batchGetId mobiles input: {} ===", mobiles);

                    BatchGetIdUserReq req = BatchGetIdUserReq.newBuilder()
                            .userIdType("open_id")
                            .batchGetIdUserReqBody(BatchGetIdUserReqBody.newBuilder()
                                    .mobiles(mobiles.toArray(new String[0]))
                                    .build())
                            .build();

                    BatchGetIdUserResp resp = client.contact().user()
                            .batchGetId(req, RequestOptions.newBuilder().build());

                    if (!resp.success()) {
                        log.error("Lark batchGetId failed: code={}, msg={}", resp.getCode(), resp.getMsg());
                        throw new RuntimeException("Lark batchGetId error: " + resp.getMsg());
                    }

                    // Map<mobile, open_id>
                    Map<String, String> result = new HashMap<>();
                    if (resp.getData() != null && resp.getData().getUserList() != null) {
                        Arrays.stream(resp.getData().getUserList()).forEach(user -> {
                            log.info("  → mobile={}, open_id={}", user.getMobile(), user.getUserId());
                            if (user.getMobile() != null && user.getUserId() != null) {
                                result.put(user.getMobile(), user.getUserId());
                            }
                        });
                    }

                    log.info("Resolved {} open_ids from {} mobiles", result.size(), mobiles.size());
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> sendCardMessage(String openId, String cardJson) {
        return Mono.fromCallable(() -> {
                    CreateMessageReq req = CreateMessageReq.newBuilder()
                            .receiveIdType("open_id")
                            .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                    .receiveId(openId)
                                    .msgType("interactive")
                                    .content(cardJson)
                                    .build())
                            .build();

                    CreateMessageResp resp = client.im().message().create(req);

                    if (!resp.success()) {
                        throw new RuntimeException("Lark API error: " + resp.getMsg());
                    }

                    return resp;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // LarkService.java — เพิ่ม method นี้
    public Mono<Void> sendRegisterNotification(String openId, RegisterRequest registerRequest) {
        String cardJson = buildRegisterCardJson(registerRequest);
        return sendCardMessage(openId, cardJson);
    }

    private String buildRegisterCardJson(RegisterRequest req) {
        String machineName = req.getMachineName() != null ? req.getMachineName() : "-";
        String department  = req.getDepartment()  != null ? req.getDepartment()  : "-";
        String serialNo    = req.getSerialNumber() != null ? req.getSerialNumber() : "-";

        return """
        {
          "type": "template",
          "data": {
            "template_id": "AAqkOi53Gw8W5",
            "template_variable": {
              "title": "🔔 มีการลงทะเบียนเครื่องจักรใหม่",
              "machine_name": "%s",
              "department": "%s",
              "serial_number": "%s"
            }
          }
        }
        """.formatted(machineName, department, serialNo);
    }

    public void updateRecord(String recordId, Map<String, Object> fields) throws Exception {
        UpdateAppTableRecordReq req = UpdateAppTableRecordReq.newBuilder()
                .appToken(appToken)
                .tableId(tableId)
                .recordId(recordId)
                .appTableRecord(AppTableRecord.newBuilder()
                        .fields(fields)
                        .build())
                .build();

        UpdateAppTableRecordResp resp = client.bitable().appTableRecord().update(req);
        if (!resp.success()) throw new RuntimeException("Update failed: " + resp.getMsg());
    }

    public Mono<Void> upsertMachineRecord(Machine machine) {
        return Mono.fromCallable(() -> {

                    Map<String, Object> fields = new HashMap<>();
                    fields.put("id",               String.valueOf(machine.getId()));
                    fields.put("รหัสเครื่องจักร",  nullSafe(machine.getMachineCode()));
                    fields.put("ชื่อเครื่องจักร",  nullSafe(machine.getMachineName()));
                    fields.put("กลุ่ม", nullSafe(machine.getMachineGroupId()));
                    fields.put("ประเภท", nullSafe(machine.getMachineTypeId()));
                    fields.put("แบรนด์",           nullSafe(machine.getBrand()));
                    fields.put("รุ่น",             nullSafe(machine.getModel()));
                    fields.put("หมายเลขซีเรียล",  nullSafe(machine.getSerialNumber()));
                    fields.put("หน่วยธุรกิจ",      nullSafe(machine.getBusinessUnit()));
                    fields.put("แผนก",            nullSafe(machine.getDepartment()));
                    fields.put("สถานะเครื่องจักร", nullSafe(machine.getMachineStatus()));
                    fields.put("สถานะการตรวจสอบ",  nullSafe(machine.getCheckStatus()));
                    fields.put("ผู้รับผิดชอบ",      nullSafe(machine.getResponsiblePersonName()));
                    fields.put("หมายเหตุ",         nullSafe(machine.getNote()));
                    if (machine.getRegisterDate() != null) {
                        long timestamp = machine.getRegisterDate()
                                .atStartOfDay(ZoneId.of("Asia/Bangkok"))
                                .toInstant()
                                .toEpochMilli();
                        fields.put("วันที่ลงทะเบียน", timestamp);
                    }

                    String recordId = findRecordIdByMachineId(String.valueOf(machine.getId()));

                    if (recordId != null) {
                        updateRecord(recordId, fields);
                        log.info("Updated machine {} (id={}) in Lark Base", machine.getMachineCode(), machine.getId());
                    } else {
                        CreateAppTableRecordReq req = CreateAppTableRecordReq.newBuilder()
                                .appToken(appToken)
                                .tableId(tableId)
                                .appTableRecord(AppTableRecord.newBuilder()
                                        .fields(fields)
                                        .build())
                                .build();
                        CreateAppTableRecordResp resp = client.bitable().appTableRecord().create(req);
                        if (!resp.success()) {
                            log.error("Failed machine {} (id={}): {}", machine.getMachineCode(), machine.getId(), resp.getMsg());
                            return null;
                        }
                        log.info("Created machine {} (id={}) in Lark Base", machine.getMachineCode(), machine.getId());
                    }
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public String findRecordIdByMachineId(String machineId) throws Exception {
        SearchAppTableRecordReq req = SearchAppTableRecordReq.newBuilder()
                .appToken(appToken)
                .tableId(tableId)
                .searchAppTableRecordReqBody(SearchAppTableRecordReqBody.newBuilder()
                        .fieldNames(new String[]{"id"})
                        .filter(FilterInfo.newBuilder()
                                .conjunction("and")
                                .conditions(new Condition[]{
                                        Condition.newBuilder()
                                                .fieldName("id")
                                                .operator("is")
                                                .value(new String[]{machineId})
                                                .build()
                                })
                                .build())
                        .build())
                .build();

        SearchAppTableRecordResp resp = client.bitable().appTableRecord().search(req);
        if (!resp.success()) throw new RuntimeException("Search failed: " + resp.getMsg());

        AppTableRecord[] items = resp.getData().getItems();
        if (items == null || items.length == 0) return null;
        return items[0].getRecordId();
    }

    // helper แปลง null เป็น string ว่าง
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}