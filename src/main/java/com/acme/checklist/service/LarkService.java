package com.acme.checklist.service;

import com.lark.oapi.Client;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserReq;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserReqBody;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserResp;
import com.lark.oapi.service.im.v1.model.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

@Slf4j
@Service
public class LarkService {

    @Value("${lark.app-id}")
    private String appId;

    @Value("${lark.app-secret}")
    private String appSecret;

    private Client client;

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
}